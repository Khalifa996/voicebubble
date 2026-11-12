// Deciding when Claude's tidy-up can be skipped, to save time on short messages.
//
// Whisper already returns punctuated, capitalised text. For a short message with nothing
// for Claude to fix (no fillers, corrections, spoken punctuation, numbers to format or
// repeated words), the tidy-up adds a second or so and changes nothing. This errs heavily
// on the side of sending it to Claude: anything slightly unusual goes there.

export const QUICK_MAX_WORDS = 12;

const FILLER = /\b(u+m+|u+h+|e+r+|e+r+m+|hmm+|ah+|uh-huh|you know|i guess|kind of|sort of)\b/i;
const CUES =
  /\b(scratch that|no wait|wait no|i mean|sorry|actually|make that|delete that|new line|new paragraph|next line|full stop|full-stop|period|comma|colon|question mark|exclamation mark|exclamation point|bullet|quote|unquote|dash|hyphen)\b/i;
const NUMBERS =
  /\b(zero|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred|thousand|million|half|quarter|pounds?|quid|pence|p|percent|per cent|o'clock|dot com|dot co|at gmail|at hotmail|at outlook)\b/i;
const REPEATED_WORD = /\b(\w+)\s+\1\b/i;

/** True if [text] is short and has nothing in it for Claude to fix. */
export function isAlreadyClean(text) {
  const trimmed = typeof text === "string" ? text.trim() : "";
  const words = trimmed.split(/\s+/).filter(Boolean);
  if (words.length === 0 || words.length > QUICK_MAX_WORDS) return false;
  if (FILLER.test(trimmed) || CUES.test(trimmed) || NUMBERS.test(trimmed) || REPEATED_WORD.test(trimmed)) {
    return false;
  }
  // Whisper's usual shape: starts with a capital, ends with punctuation. Anything else, let Claude look.
  if (!/^[A-Z"'(£]/.test(trimmed)) return false;
  if (!/[.!?]["')]?$/.test(trimmed)) return false;
  return true;
}

/**
 * Whether the quick path can be taken at all. It can't when the text continues a sentence
 * already in the box (Claude has to fit it in), or when a habit needs Claude's judgement.
 */
export function canSkipCleanup({ text, context = "", habits = [] }) {
  if (!isAlreadyClean(text)) return false;
  if (context.trim() && !/[.!?\n]\s*$/.test(context)) return false;
  if (habits.includes("lowercase_start")) return false;
  return true;
}

/** The small fixes Claude would otherwise make on the quick path. */
export function quickFinish(text, habits = []) {
  let out = text.trim();
  if (habits.includes("no_final_full_stop") && /[^.]\.$/.test(out)) out = out.slice(0, -1);
  return out;
}
