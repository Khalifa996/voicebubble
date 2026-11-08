// VoiceBubble clean-up Worker.
//
// POST /clean   { text, app?, vocabulary? }  ->  { text, style, model, usage }
// GET  /health                               ->  { ok, configured, model }
//
// The phone sends the raw transcript here; this Worker adds the Claude API key
// (which never leaves Cloudflare) and returns the tidied-up text. Any failure
// returns a non-200 so the app can fall back to pasting the raw transcript.

import {
  buildSystemPrompt,
  buildUserMessage,
  extractCleaned,
  parseVocabulary,
  styleForApp,
} from "./prompt.js";

export const DEFAULT_MODEL = "claude-haiku-5-5";
export const MAX_INPUT_CHARS = 12000;

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
    return json({ ok: true, configured: Boolean(env.APP_TOKEN && env.ANTHROPIC_API_KEY), model });
  }
  if (pathname !== "/clean") return json({ error: "not_found" }, 404);
  if (request.method !== "POST") return json({ error: "method_not_allowed" }, 405, { allow: "POST" });
  if (!env.APP_TOKEN || !env.ANTHROPIC_API_KEY) return json({ error: "worker_not_configured" }, 500);
  if (!(await isAuthorised(request, env.APP_TOKEN))) return json({ error: "unauthorised" }, 401);

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

  const result = await callClaude({
    apiKey: env.ANTHROPIC_API_KEY.trim(),
    model,
    system: buildSystemPrompt({ style, vocabulary }),
    transcript: text,
  });
  if (!result.ok) {
    console.warn("clean-up failed", result.error);
    return json({ error: result.error }, 502);
  }

  // Clean-up should only ever shrink or lightly reformat the text. A much longer
  // reply means the model answered or rewrote it, so let the app use the raw text.
  if (result.text.length > text.length * 2 + 200) return json({ error: "suspicious_output" }, 502);

  return json({ text: result.text, style, model, usage: result.usage ?? null });
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
