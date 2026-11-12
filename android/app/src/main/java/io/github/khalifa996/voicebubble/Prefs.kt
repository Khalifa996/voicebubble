package io.github.khalifa996.voicebubble

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/** Settings shared by the settings screen and the bubble service (same process, same file). */
class Prefs(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var workerUrl: String
        get() = sp.getString(KEY_URL, "").orEmpty()
        set(value) = sp.edit().putString(KEY_URL, value.trim().trimEnd('/')).apply()

    var appToken: String
        get() = sp.getString(KEY_TOKEN, "").orEmpty()
        set(value) = sp.edit().putString(KEY_TOKEN, value.trim()).apply()

    var aiCleanup: Boolean
        get() = sp.getBoolean(KEY_AI, true)
        set(value) = sp.edit().putBoolean(KEY_AI, value).apply()

    /** Record the mic ourselves and transcribe with Whisper via the Worker (most accurate). */
    var cloudSpeech: Boolean
        get() = sp.getBoolean(KEY_CLOUD_SPEECH, true)
        set(value) = sp.edit().putBoolean(KEY_CLOUD_SPEECH, value).apply()

    /** Let Claude see the text just before the cursor so new text carries on from it. */
    var useContext: Boolean
        get() = sp.getBoolean(KEY_USE_CONTEXT, true)
        set(value) = sp.edit().putBoolean(KEY_USE_CONTEXT, value).apply()

    /** Skip Claude's tidy-up when a short message comes back from Whisper already clean. */
    var quickShortMessages: Boolean
        get() = sp.getBoolean(KEY_QUICK, true)
        set(value) = sp.edit().putBoolean(KEY_QUICK, value).apply()

    var preferOnDevice: Boolean
        get() = sp.getBoolean(KEY_ON_DEVICE, true)
        set(value) = sp.edit().putBoolean(KEY_ON_DEVICE, value).apply()

    /**
     * Sound bars driven by the real mic volume. Off by default: on some phones measuring the
     * mic alongside the recogniser costs accuracy. Turned off automatically on a mic failure.
     */
    var liveBars: Boolean
        get() = sp.getBoolean(KEY_LIVE_BARS, false)
        set(value) = sp.edit().putBoolean(KEY_LIVE_BARS, value).apply()

    /** The last dictation, kept on the phone only, so you can see what was heard vs typed. */
    var lastHeard: String
        get() = sp.getString(KEY_LAST_HEARD, "").orEmpty()
        set(value) = sp.edit().putString(KEY_LAST_HEARD, value).apply()

    var lastTyped: String
        get() = sp.getString(KEY_LAST_TYPED, "").orEmpty()
        set(value) = sp.edit().putString(KEY_LAST_TYPED, value).apply()

    var lastHow: String
        get() = sp.getString(KEY_LAST_HOW, "").orEmpty()
        set(value) = sp.edit().putString(KEY_LAST_HOW, value).apply()

    var language: String
        get() = sp.getString(KEY_LANGUAGE, DEFAULT_LANGUAGE).orEmpty().ifBlank { DEFAULT_LANGUAGE }
        set(value) = sp.edit().putString(KEY_LANGUAGE, value.trim()).apply()

    var vocabulary: String
        get() = sp.getString(KEY_VOCAB, "").orEmpty()
        set(value) = sp.edit().putString(KEY_VOCAB, value).apply()

    /** Where the user has dragged the bubble, relative to its default spot above the keyboard. */
    var offsetX: Int
        get() = sp.getInt(KEY_OFFSET_X, 0)
        set(value) = sp.edit().putInt(KEY_OFFSET_X, value).apply()

    var offsetY: Int
        get() = sp.getInt(KEY_OFFSET_Y, 0)
        set(value) = sp.edit().putInt(KEY_OFFSET_Y, value).apply()

    /** Words you typed in yourself, then words learned from your corrections. */
    val vocabularyList: List<String>
        get() = (lines(vocabulary) + learnedList)
            .distinctBy { it.lowercase() }
            .take(MAX_VOCAB)

    // ---- Learning from corrections ----

    var learnFromCorrections: Boolean
        get() = sp.getBoolean(KEY_LEARN, true)
        set(value) = sp.edit().putBoolean(KEY_LEARN, value).apply()

    /** Learned words, oldest first, one per line. Editable in the app. */
    var learnedWords: String
        get() = sp.getString(KEY_LEARNED, "").orEmpty()
        set(value) = sp.edit().putString(KEY_LEARNED, value).apply()

    val learnedList: List<String>
        get() = lines(learnedWords)

    /** Adds words you corrected; returns the ones that were new. Keeps the newest [MAX_LEARNED]. */
    fun addLearned(words: List<String>): List<String> {
        val known = (lines(vocabulary) + learnedList).map { it.lowercase() }.toMutableSet()
        val added = words.map { it.trim() }.filter { it.isNotEmpty() && known.add(it.lowercase()) }
        if (added.isNotEmpty()) learnedWords = (learnedList + added).takeLast(MAX_LEARNED).joinToString("\n")
        return added
    }

    /**
     * Per-app habits, counted from your edits: did you keep or delete the final full stop,
     * and did you lower-case the first letter. Stored as JSON: { package: { counter: n } }.
     */
    fun recordHabits(appPackage: String, keptFinalStop: Boolean?, loweredStart: Boolean?) {
        if (keptFinalStop == null && loweredStart == null) return
        val all = habitJson()
        val app = all.optJSONObject(appPackage) ?: JSONObject()
        fun bump(key: String) {
            app.put(key, app.optInt(key, 0) + 1)
        }
        when (keptFinalStop) {
            true -> bump(STOP_KEPT)
            false -> bump(STOP_REMOVED)
            null -> Unit
        }
        when (loweredStart) {
            true -> bump(START_LOWERED)
            false -> bump(START_KEPT)
            null -> Unit
        }
        all.put(appPackage, app)
        sp.edit().putString(KEY_HABITS, all.toString()).apply()
    }

    /** Habit codes for one app, understood by the Worker. */
    fun habitsFor(appPackage: String?): List<String> {
        val app = appPackage?.let { habitJson().optJSONObject(it) } ?: return emptyList()
        val habits = mutableListOf<String>()
        if (isHabit(app.optInt(STOP_REMOVED, 0), app.optInt(STOP_KEPT, 0))) habits += HABIT_NO_FINAL_STOP
        if (isHabit(app.optInt(START_LOWERED, 0), app.optInt(START_KEPT, 0))) habits += HABIT_LOWERCASE_START
        return habits
    }

    /** Every app with at least one habit, for the settings screen. */
    fun allHabits(): Map<String, List<String>> {
        val all = habitJson()
        return all.keys().asSequence()
            .associateWith { habitsFor(it) }
            .filterValues { it.isNotEmpty() }
    }

    fun forgetHabits() = sp.edit().remove(KEY_HABITS).apply()

    /** Twice or more, and more often than not. */
    private fun isHabit(did: Int, didNot: Int) = did >= 2 && did > didNot

    private fun habitJson(): JSONObject = try {
        JSONObject(sp.getString(KEY_HABITS, "{}").orEmpty().ifBlank { "{}" })
    } catch (e: Exception) {
        JSONObject()
    }

    private fun lines(text: String): List<String> =
        text.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }

    val isCleanupConfigured: Boolean
        get() = workerUrl.startsWith("https://") && appToken.isNotEmpty()

    /** Called when the last-dictation record changes. Keep a reference: prefs hold listeners weakly. */
    class Listener(private val onLastDictation: () -> Unit) : SharedPreferences.OnSharedPreferenceChangeListener {
        override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
            if (key in WATCHED_KEYS) onLastDictation()
        }
    }

    fun addListener(listener: Listener) = sp.registerOnSharedPreferenceChangeListener(listener)

    fun removeListener(listener: Listener) = sp.unregisterOnSharedPreferenceChangeListener(listener)

    companion object {
        const val DEFAULT_LANGUAGE = "en-GB"
        const val HABIT_NO_FINAL_STOP = "no_final_full_stop"
        const val HABIT_LOWERCASE_START = "lowercase_start"
        private const val MAX_VOCAB = 100
        private const val MAX_LEARNED = 60
        private const val KEY_LEARN = "learn_from_corrections"
        private const val KEY_LEARNED = "learned_words"
        private const val KEY_HABITS = "habits"
        private const val STOP_KEPT = "stop_kept"
        private const val STOP_REMOVED = "stop_removed"
        private const val START_KEPT = "start_kept"
        private const val START_LOWERED = "start_lowered"
        private const val FILE = "voicebubble"
        private const val KEY_URL = "worker_url"
        private const val KEY_TOKEN = "app_token"
        private const val KEY_AI = "ai_cleanup"
        private const val KEY_ON_DEVICE = "prefer_on_device"
        // New key so everyone starts with live bars off (see liveBars).
        private const val KEY_LIVE_BARS = "live_bars_v2"
        private const val KEY_CLOUD_SPEECH = "cloud_speech"
        private const val KEY_QUICK = "quick_short_messages"
        private const val KEY_USE_CONTEXT = "use_context"
        private const val KEY_LAST_HEARD = "last_heard"
        private const val KEY_LAST_TYPED = "last_typed"
        private const val KEY_LAST_HOW = "last_how"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_VOCAB = "vocabulary"
        private const val KEY_OFFSET_X = "offset_x"
        private const val KEY_OFFSET_Y = "offset_y"

        /** Changes the settings screen redraws for (last dictation, learned words, habits). */
        private val WATCHED_KEYS = setOf(KEY_LAST_HEARD, KEY_LAST_TYPED, KEY_LAST_HOW, KEY_LEARNED, KEY_HABITS)
    }
}
