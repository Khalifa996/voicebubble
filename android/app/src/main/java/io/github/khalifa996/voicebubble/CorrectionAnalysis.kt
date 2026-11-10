package io.github.khalifa996.voicebubble

import kotlin.math.max

/**
 * Works out what you corrected after a dictation, by comparing what was typed with what
 * the box says a little later. Pure logic, no Android, so it can be tested on its own.
 *
 * What it learns:
 * - Words you fixed: names ("Shivon" → "Siobhan"), capitals ("folio" → "Folio"), and
 *   spellings close to what was heard. Common words are never learned, so swapping
 *   "there" for "their" once can't teach it the wrong thing everywhere.
 * - Habits: whether you delete the full stop at the end, and whether you lower-case
 *   the first letter of a message.
 */
object CorrectionAnalysis {

    class Findings(
        /** Words or short phrases to add to your vocabulary. */
        val words: List<String>,
        /** True: you kept the final full stop. False: you deleted it. Null: can't tell. */
        val keptFinalStop: Boolean?,
        /** True: you lower-cased the first letter. False: you left it. Null: can't tell. */
        val loweredStart: Boolean?,
    )

    private const val MAX_WORDS_PER_DICTATION = 3
    private const val MAX_PHRASE_WORDS = 3

    /**
     * Your edited version of what was typed, if the box still has the same text around it
     * ([before] and [after] are the box's text either side at the moment it was typed).
     * Anything you've typed straight after it is included; that's ignored later.
     */
    fun extract(current: String, before: String, after: String): String? {
        if (current.length < before.length + after.length) return null
        if (!current.startsWith(before) || !current.endsWith(after)) return null
        return current.substring(before.length, current.length - after.length)
    }

    /** How much of what was typed is still there, in order (0 to 1). Low means "replaced", not "corrected". */
    fun overlap(inserted: String, edited: String): Double {
        val a = words(inserted).map { it.lowercase() }
        if (a.isEmpty()) return 0.0
        val b = words(edited).map { it.lowercase() }
        return lcsLength(a, b).toDouble() / a.size
    }

    fun analyse(inserted: String, edited: String, atStartOfBox: Boolean): Findings {
        val a = words(inserted)
        val b = words(edited)
        return Findings(
            words = learnedWords(a, b),
            keptFinalStop = finalStop(inserted, edited, a, b),
            loweredStart = loweredStart(inserted, edited, a, b, atStartOfBox),
        )
    }

    // ---- Words ----

    private fun learnedWords(a: List<String>, b: List<String>): List<String> {
        val learned = mutableListOf<String>()
        for (hunk in diff(a, b)) {
            if (learned.size >= MAX_WORDS_PER_DICTATION) break
            val (aStart, aEnd, bStart, bEnd) = hunk
            val aLen = aEnd - aStart
            val bLen = bEnd - bStart
            // Only straight swaps of a few words. Pure additions or deletions are style, not spelling.
            if (aLen !in 1..MAX_PHRASE_WORDS || bLen !in 1..MAX_PHRASE_WORDS) continue

            val from = a.subList(aStart, aEnd).joinToString(" ")
            val to = b.subList(bStart, bEnd).joinToString(" ")
            if (to == from || to.length < 3 || to.length > 40) continue
            if (to.lowercase() in COMMON_WORDS) continue
            if (to.split(' ').all { it.lowercase() in COMMON_WORDS }) continue

            val caseOnly = to.equals(from, ignoreCase = true)
            if (caseOnly) {
                // Lower-casing something isn't worth learning, and capitalising the first
                // word of a sentence is just grammar.
                if (to.none { it.isUpperCase() }) continue
                val sentenceStart = bStart == 0 || b[bStart - 1].last() in ".!?"
                if (sentenceStart) continue
            }

            val looksLikeName = to.any { it.isUpperCase() || it.isDigit() }
            val soundsAlike = similarity(from.lowercase(), to.lowercase()) >= 0.5
            if (looksLikeName || soundsAlike) learned += to
        }
        return learned
    }

    // ---- Habits ----

    private fun finalStop(inserted: String, edited: String, a: List<String>, b: List<String>): Boolean? {
        val typed = inserted.trimEnd()
        if (!typed.endsWith('.') || typed.endsWith("...")) return null
        // Only judge it if the message still ends where the dictation ended.
        if (a.isEmpty() || b.isEmpty() || !a.last().equals(b.last(), ignoreCase = true)) return null
        return edited.trimEnd().endsWith('.')
    }

    private fun loweredStart(
        inserted: String,
        edited: String,
        a: List<String>,
        b: List<String>,
        atStartOfBox: Boolean,
    ): Boolean? {
        if (!atStartOfBox || a.isEmpty() || b.isEmpty()) return null
        if (!a.first().equals(b.first(), ignoreCase = true)) return null
        // "I" and names keep their capitals whatever your habit.
        if (a.first() == "I" || a.first().drop(1).any { it.isUpperCase() }) return null
        val typedFirst = inserted.trimStart().firstOrNull() ?: return null
        val editedFirst = edited.trimStart().firstOrNull() ?: return null
        if (!typedFirst.isUpperCase()) return null
        return editedFirst.isLowerCase()
    }

    // ---- Text helpers ----

    /** Words with the punctuation around them removed (inner apostrophes and hyphens kept). */
    fun words(text: String): List<String> =
        text.split(Regex("\\s+"))
            .map { it.trim { c -> !c.isLetterOrDigit() && c != '£' && c != '&' } }
            .filter { it.isNotEmpty() }

    /** Runs of words that differ between [a] and [b]: (aStart, aEnd, bStart, bEnd), ends exclusive. */
    fun diff(a: List<String>, b: List<String>): List<IntArray> {
        val table = lcsTable(a, b)
        val hunks = mutableListOf<IntArray>()
        var i = 0
        var j = 0
        var hunkA = 0
        var hunkB = 0
        fun closeHunk() {
            if (i > hunkA || j > hunkB) hunks += intArrayOf(hunkA, i, hunkB, j)
        }
        while (i < a.size && j < b.size) {
            if (a[i] == b[j]) {
                closeHunk()
                i++
                j++
                hunkA = i
                hunkB = j
            } else if (table[i + 1][j] >= table[i][j + 1]) {
                i++
            } else {
                j++
            }
        }
        i = a.size
        j = b.size
        closeHunk()
        return hunks
    }

    private operator fun IntArray.component4() = this[3]

    private fun lcsTable(a: List<String>, b: List<String>): Array<IntArray> {
        // table[i][j] = longest common run of a[i..] and b[j..]
        val table = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) {
            for (j in b.indices.reversed()) {
                table[i][j] = if (a[i] == b[j]) table[i + 1][j + 1] + 1 else max(table[i + 1][j], table[i][j + 1])
            }
        }
        return table
    }

    private fun lcsLength(a: List<String>, b: List<String>) = lcsTable(a, b)[0][0]

    /** 1 means identical, 0 means nothing in common (edit distance relative to length). */
    fun similarity(x: String, y: String): Double {
        if (x.isEmpty() && y.isEmpty()) return 1.0
        var previous = IntArray(y.length + 1) { it }
        for (i in 1..x.length) {
            val current = IntArray(y.length + 1)
            current[0] = i
            for (j in 1..y.length) {
                val cost = if (x[i - 1] == y[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + cost)
            }
            previous = current
        }
        return 1.0 - previous[y.length].toDouble() / max(x.length, y.length)
    }

    /**
     * Everyday words, including easily confused ones (there/their, your/you're...). Learning
     * one of these would make it "correct" the word everywhere, so they're never learned.
     */
    private val COMMON_WORDS = setOf(
        "the", "and", "for", "are", "but", "not", "you", "all", "any", "can", "had", "her", "was", "one",
        "our", "out", "day", "get", "has", "him", "his", "how", "man", "new", "now", "old", "see", "two",
        "way", "who", "boy", "did", "its", "let", "put", "say", "she", "too", "use", "yes", "yet", "got",
        "that", "with", "have", "this", "will", "your", "from", "they", "know", "want", "been", "good",
        "much", "some", "time", "very", "when", "come", "here", "just", "like", "long", "make", "many",
        "more", "only", "over", "such", "take", "than", "them", "well", "were", "what", "then", "there",
        "their", "they're", "you're", "it's", "where", "we're", "were", "which", "witch", "would", "wood",
        "could", "should", "right", "write", "hear", "here", "weather", "whether", "buy", "bye", "four",
        "fore", "won", "one", "sea", "meet", "meat", "through", "threw", "whole", "hole", "peace", "piece",
        "accept", "except", "affect", "effect", "lose", "loose", "quite", "quiet", "knew", "know", "no",
        "two", "to", "too", "be", "bee", "by", "or", "oh", "owe", "so", "sew", "sow", "of", "off", "in",
        "inn", "on", "an", "a", "i", "it", "is", "as", "at", "we", "us", "me", "my", "he", "do", "go", "if",
        "up", "am", "ok", "okay", "yeah", "yep", "nope", "about", "after", "again", "also", "back", "because",
        "before", "being", "both", "call", "came", "does", "done", "down", "each", "even", "every", "find",
        "first", "give", "going", "gonna", "gotta", "great", "into", "last", "later", "little", "look",
        "made", "maybe", "most", "need", "never", "next", "other", "people", "really", "said", "same",
        "still", "sure", "thanks", "thank", "thing", "things", "think", "those", "today", "tomorrow",
        "tonight", "under", "until", "week", "while", "work", "year", "yesterday", "morning", "evening",
        "night", "hello", "hiya", "cheers", "please", "sorry", "lol", "haha", "wanna", "kinda", "sorta",
        "aren't", "can't", "couldn't", "didn't", "doesn't", "don't", "hadn't", "hasn't", "haven't", "isn't",
        "shouldn't", "wasn't", "weren't", "won't", "wouldn't", "i'm", "i've", "i'll", "i'd", "you've",
        "you'll", "you'd", "he's", "she's", "we've", "we'll", "we'd", "they've", "they'll", "they'd",
        "that's", "there's", "what's", "let's", "theirs", "there's", "ours", "yours",
    )
}
