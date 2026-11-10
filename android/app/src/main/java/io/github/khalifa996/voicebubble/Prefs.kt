package io.github.khalifa996.voicebubble

import android.content.Context
import android.content.SharedPreferences

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

    val vocabularyList: List<String>
        get() = vocabulary.split('\n', ',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_VOCAB)

    val isCleanupConfigured: Boolean
        get() = workerUrl.startsWith("https://") && appToken.isNotEmpty()

    /** Called when the last-dictation record changes. Keep a reference: prefs hold listeners weakly. */
    class Listener(private val onLastDictation: () -> Unit) : SharedPreferences.OnSharedPreferenceChangeListener {
        override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
            if (key == KEY_LAST_HEARD || key == KEY_LAST_TYPED || key == KEY_LAST_HOW) onLastDictation()
        }
    }

    fun addListener(listener: Listener) = sp.registerOnSharedPreferenceChangeListener(listener)

    fun removeListener(listener: Listener) = sp.unregisterOnSharedPreferenceChangeListener(listener)

    companion object {
        const val DEFAULT_LANGUAGE = "en-GB"
        private const val MAX_VOCAB = 100
        private const val FILE = "voicebubble"
        private const val KEY_URL = "worker_url"
        private const val KEY_TOKEN = "app_token"
        private const val KEY_AI = "ai_cleanup"
        private const val KEY_ON_DEVICE = "prefer_on_device"
        // New key so everyone starts with live bars off (see liveBars).
        private const val KEY_LIVE_BARS = "live_bars_v2"
        private const val KEY_CLOUD_SPEECH = "cloud_speech"
        private const val KEY_USE_CONTEXT = "use_context"
        private const val KEY_LAST_HEARD = "last_heard"
        private const val KEY_LAST_TYPED = "last_typed"
        private const val KEY_LAST_HOW = "last_how"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_VOCAB = "vocabulary"
        private const val KEY_OFFSET_X = "offset_x"
        private const val KEY_OFFSET_Y = "offset_y"
    }
}
