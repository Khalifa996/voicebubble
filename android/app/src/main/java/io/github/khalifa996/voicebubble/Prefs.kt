package io.github.khalifa996.voicebubble

import android.content.Context

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

    var preferOnDevice: Boolean
        get() = sp.getBoolean(KEY_ON_DEVICE, true)
        set(value) = sp.edit().putBoolean(KEY_ON_DEVICE, value).apply()

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

    companion object {
        const val DEFAULT_LANGUAGE = "en-GB"
        private const val MAX_VOCAB = 100
        private const val FILE = "voicebubble"
        private const val KEY_URL = "worker_url"
        private const val KEY_TOKEN = "app_token"
        private const val KEY_AI = "ai_cleanup"
        private const val KEY_ON_DEVICE = "prefer_on_device"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_VOCAB = "vocabulary"
        private const val KEY_OFFSET_X = "offset_x"
        private const val KEY_OFFSET_Y = "offset_y"
    }
}
