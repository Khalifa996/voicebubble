// VoiceBubble Worker.
//
// POST /transcribe  WAV audio (up to ~30 s)                 ->  { text }
// POST /dictate     framed: options JSON + last WAV piece    ->  { heard, text, cleaned, skipped, error?, ms }
// POST /clean       { text, app?, vocabulary?, context?, habits? } ->  { text, style, model, usage }
// GET  /health                                              ->  { ok, configured, speech, model }
//
// /transcribe turns one piece of a recording into text with Whisper on Workers AI,
// while you're still talking. /dictate finishes a dictation in a single round trip:
// it transcribes the last piece, joins it to the earlier ones, and tidies the lot up
// with Claude (or skips Claude when the text is already clean). /clean is the
// text-only tidy-up used by the offline path. The Claude API key never leaves Cloudflare.

import {
  buildSystemPrompt,
  buildUserMessage,
  extractCleaned,
  parseContext,
  parseHabits,
  parseVocabulary,
  styleForApp,
} from "./prompt.js";
import { canSkipCleanup, quickFinish } from "./quick.js";

export const DEFAULT_MODEL = "claude-haiku-5-5";
export const MAX_INPUT_CHARS = 12000;
export const SPEECH_MODEL = "@cf/openai/whisper-large-v3-turbo";
// The app sends at most 28 s of 16 kHz mono audio per request (about 0.9 MB).
export const MAX_AUDIO_BYTES = 2 * 1024 * 1024;
const MAX_OPTIONS_BYTES = 64 * 1024;
const PROMPT_TAIL_CHARS = 200;

const ANTHROPIC_URL = "https://api.anthropic.com/v1/messages";
const ANTHROPIC_VERSION = "2023-06-01";
const UPSTREAM_TIMEOUT_MS = 20000;

export default {
  async fetch(request, env) {
    try {
      return await handle(request, env);
    } catch (err) {
      console.error("unhandled", err);
      return json({ error: "internal_error" }, 500);
    }
  },
};

export async function handle(request, env) {
  const { pathname } = new URL(request.url);
  const model = env.MODEL || DEFAULT_MODEL;

  if (pathname === "/health") {
    return json({
      ok: true,
      configured: Boolean(env.APP_TOKEN && env.ANTHROPIC_API_KEY),
      speech: Boolean(env.AI),
      model,
    });
  }
  if (!["/clean", "/transcribe", "/dictate"].includes(pathname)) return json({ error: "not_found" }, 404);
  if (request.method !== "POST") return json({ error: "method_not_allowed" }, 405, { allow: "POST" });
  if (!env.APP_TOKEN || !env.ANTHROPIC_API_KEY) return json({ error: "worker_not_configured" }, 500);
  if (!(await isAuthorised(request, env.APP_TOKEN))) return json({ error: "unauthorised" }, 401);
  if (pathname === "/transcribe") return transcribe(request, env);
  if (pathname === "/dictate") return dictate(request, env, model);

  let body;
  try {
    body = await request.json();
  } catch {
    return json({ error: "bad_json" }, 400);
  }

  const text = typeof body?.text === "string" ? body.text.trim() : "";
  if (!text) return json({ error: "empty_text" }, 400);
  if (text.length > MAX_INPUT_CHARS) return json({ error: "too_long", max: MAX_INPUT_CHARS }, 413);

  const style = styleForApp(body.app);
  const vocabulary = parseVocabulary(body.vocabulary);
  const context = parseContext(body.context);
  const habits = parseHabits(body.habits);

  const result = await cleanUp(env, model, { text, style, vocabulary, context, habits });
  if (!result.ok) {
    console.warn("clean-up failed", result.error);
    return json({ error: result.error }, 502);
  }
  return json({ text: result.text, style, model, usage: result.usage ?? null });
}

// Claude tidy-up shared by /clean and /dictate.
async function cleanUp(env, model, { text, style, vocabulary, context, habits }) {
  const result = await callClaude({
    apiKey: env.ANTHROPIC_API_KEY.trim(),
    model,
    system: buildSystemPrompt({ style, vocabulary, context, habits }),
    transcript: text,
  });
  if (!result.ok) return result;
  // Clean-up should only ever shrink or lightly reformat the text. A much longer
  // reply means the model answered or rewrote it, so let the app use the raw text.
  if (result.text.length > text.length * 2 + 200) return { ok: false, error: "suspicious_output" };
  return result;
}

// Speech to text for one piece of a recording, sent while you're still talking.
// Extra details arrive as headers:
//   x-language    e.g. "en-GB" (only the "en" part is used)
//   x-vocabulary  URL-encoded names and terms, one per line, to help spelling
//   x-previous    URL-encoded end of the text so far, so the pieces join up naturally
async function transcribe(request, env) {
  if (!env.AI) return json({ error: "speech_not_set_up" }, 500);

  const audio = new Uint8Array(await request.arrayBuffer());
  if (audio.length === 0) return json({ error: "empty_audio" }, 400);
  if (audio.length > MAX_AUDIO_BYTES) return json({ error: "too_long", max: MAX_AUDIO_BYTES }, 413);

  const result = await runWhisper(env, audio, {
    language: request.headers.get("x-language"),
    vocabulary: parseVocabulary(safeDecode(request.headers.get("x-vocabulary"))),
    previous: safeDecode(request.headers.get("x-previous")),
  });
  if (!result.ok) return json({ error: "transcription_failed" }, 502);
  return json({ text: result.text });
}

// Finish a dictation in one round trip. The body is framed so it can carry both the
// options and the audio without base64:
//   4 bytes   length N of the options JSON (little-endian)
//   N bytes   options JSON: { language, vocabulary, previous, clean, quick, app, context, habits }
//   the rest  the last piece of audio as WAV (may be empty if there's nothing new)
// Always answers 200 with whatever it managed, so the phone never loses the text.
async function dictate(request, env, model) {
  const body = new Uint8Array(await request.arrayBuffer());
  if (body.length < 4) return json({ error: "bad_request" }, 400);
  const optionsLength = new DataView(body.buffer, body.byteOffset, 4).getUint32(0, true);
  if (optionsLength > MAX_OPTIONS_BYTES || 4 + optionsLength > body.length) return json({ error: "bad_request" }, 400);

  let options;
  try {
    options = JSON.parse(new TextDecoder().decode(body.subarray(4, 4 + optionsLength)));
  } catch {
    return json({ error: "bad_json" }, 400);
  }
  const audio = body.subarray(4 + optionsLength);
  if (audio.length > MAX_AUDIO_BYTES) return json({ error: "too_long", max: MAX_AUDIO_BYTES }, 413);

  const previous = typeof options.previous === "string" ? options.previous.trim().slice(0, MAX_INPUT_CHARS) : "";
  const vocabulary = parseVocabulary(options.vocabulary);
  const ms = { whisper: 0, claude: 0 };

  // 1. The last piece of audio (a WAV header alone is 44 bytes).
  let piece = "";
  if (audio.length > 44) {
    if (!env.AI) return json({ error: "speech_not_set_up" }, 500);
    const started = Date.now();
    const result = await runWhisper(env, audio, { language: options.language, vocabulary, previous });
    ms.whisper = Date.now() - started;
    if (!result.ok) {
      return json({ heard: previous, text: previous, cleaned: false, skipped: false, error: "transcription_failed", ms });
    }
    piece = result.text;
  }
  const heard = [previous, piece].filter(Boolean).join(" ").trim().slice(0, MAX_INPUT_CHARS);
  if (!heard || options.clean !== true) {
    return json({ heard, text: heard, cleaned: false, skipped: false, ms });
  }

  // 2. Tidy-up, unless it's already clean and the phone allows skipping it.
  const context = parseContext(options.context);
  const habits = parseHabits(options.habits);
  if (options.quick === true && canSkipCleanup({ text: heard, context, habits })) {
    return json({ heard, text: quickFinish(heard, habits), cleaned: false, skipped: true, ms });
  }
  const started = Date.now();
  const result = await cleanUp(env, model, {
    text: heard,
    style: styleForApp(options.app),
    vocabulary,
    context,
    habits,
  });
  ms.claude = Date.now() - started;
  if (!result.ok) {
    console.warn("clean-up failed", result.error);
    return json({ heard, text: heard, cleaned: false, skipped: false, error: result.error, ms });
  }
  return json({ heard, text: result.text, cleaned: true, skipped: false, ms });
}

// Whisper large v3 turbo on Workers AI. The initial prompt gives it your names and terms,
// and the end of the text so far, which helps a piece carry on from the one before.
async function runWhisper(env, audio, { language, vocabulary = [], previous = "" }) {
  const input = { audio: toBase64(audio), vad_filter: true };
  const code = (typeof language === "string" ? language : "").split(/[-_]/)[0].toLowerCase();
  if (/^[a-z]{2,3}$/.test(code)) input.language = code;

  const prompt = [];
  if (vocabulary.length) prompt.push(`Names and terms: ${vocabulary.join(", ")}.`);
  const tail = (typeof previous === "string" ? previous : "").trim().slice(-PROMPT_TAIL_CHARS);
  if (tail) prompt.push(tail);
  if (prompt.length) input.initial_prompt = prompt.join(" ");

  try {
    const result = await env.AI.run(SPEECH_MODEL, input);
    return { ok: true, text: typeof result?.text === "string" ? result.text.trim() : "" };
  } catch (err) {
    console.warn("transcription failed", err?.message ?? err);
    return { ok: false };
  }
}

function safeDecode(value) {
  try {
    return decodeURIComponent(value ?? "");
  } catch {
    return "";
  }
}

// Base64 in pieces, so a large recording doesn't overflow the call stack.
export function toBase64(bytes) {
  let binary = "";
  for (let i = 0; i < bytes.length; i += 0x8000) {
    binary += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  }
  return btoa(binary);
}

async function callClaude({ apiKey, model, system, transcript }) {
  const payload = {
    model,
    max_tokens: maxTokensFor(transcript),
    // Fastest setting for a short, simple task: no thinking, low effort.
    thinking: { type: "disabled" },
    output_config: { effort: "low" },
    system,
    messages: [{ role: "user", content: buildUserMessage(transcript) }],
  };

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), UPSTREAM_TIMEOUT_MS);
  let res;
  try {
    res = await fetch(ANTHROPIC_URL, {
      method: "POST",
      headers: {
        "x-api-key": apiKey,
        "anthropic-version": ANTHROPIC_VERSION,
        "content-type": "application/json",
      },
      body: JSON.stringify(payload),
      signal: controller.signal,
    });
  } catch (err) {
    return { ok: false, error: err?.name === "AbortError" ? "upstream_timeout" : "upstream_unreachable" };
  } finally {
    clearTimeout(timer);
  }

  if (!res.ok) {
    let type = "";
    try {
      type = (await res.json())?.error?.type ?? "";
    } catch {
      // Body wasn't JSON; the status code is enough.
    }
    return { ok: false, error: `upstream_${res.status}${type ? `_${type}` : ""}` };
  }

  const data = await res.json();
  if (data.stop_reason === "refusal") return { ok: false, error: "refused" };
  if (data.stop_reason === "max_tokens") return { ok: false, error: "truncated" };

  const output = (data.content ?? [])
    .filter((block) => block?.type === "text")
    .map((block) => block.text)
    .join("");
  return { ok: true, text: extractCleaned(output), usage: data.usage };
}

// Cleaned text is about the same length as the input. Leave generous headroom.
export function maxTokensFor(transcript) {
  return Math.min(8192, Math.ceil(transcript.length / 2) + 512);
}

async function isAuthorised(request, expectedToken) {
  const header = request.headers.get("authorization") ?? "";
  const match = header.match(/^Bearer\s+(.+)$/i);
  if (!match) return false;
  return constantTimeEqual(match[1].trim(), expectedToken.trim());
}

// Compare SHA-256 digests so the comparison takes the same time whatever the input.
async function constantTimeEqual(a, b) {
  const enc = new TextEncoder();
  const [da, db] = await Promise.all([
    crypto.subtle.digest("SHA-256", enc.encode(a)),
    crypto.subtle.digest("SHA-256", enc.encode(b)),
  ]);
  const va = new Uint8Array(da);
  const vb = new Uint8Array(db);
  let diff = 0;
  for (let i = 0; i < va.length; i++) diff |= va[i] ^ vb[i];
  return diff === 0;
}

function json(data, status = 200, extraHeaders = {}) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
      ...extraHeaders,
    },
  });
}
