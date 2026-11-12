package io.github.khalifa996.voicebubble

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Setup checklist and settings. The bubble itself lives in [BubbleService]. */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs

    private lateinit var micStatus: TextView
    private lateinit var micButton: Button
    private lateinit var a11yStatus: TextView
    private lateinit var a11yButton: Button
    private lateinit var restrictedRow: View
    private lateinit var batteryStatus: TextView
    private lateinit var batteryButton: Button
    private lateinit var speechStatus: TextView
    private lateinit var speechButton: Button

    private lateinit var aiSwitch: Switch
    private lateinit var workerUrl: EditText
    private lateinit var appToken: EditText
    private lateinit var testResult: TextView
    private lateinit var language: EditText
    private lateinit var onDeviceSwitch: Switch
    private lateinit var liveBarsSwitch: Switch
    private lateinit var cloudSwitch: Switch
    private lateinit var learnSwitch: Switch
    private lateinit var quickSwitch: Switch
    private lateinit var learnedWords: EditText
    private lateinit var habitsView: TextView
    private var loadedLearned = ""
    private lateinit var contextSwitch: Switch
    private lateinit var vocabulary: EditText
    private lateinit var lastDictation: TextView

    private var supportChecker: SpeechRecognizer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        micStatus = findViewById(R.id.micStatus)
        micButton = findViewById(R.id.micButton)
        a11yStatus = findViewById(R.id.a11yStatus)
        a11yButton = findViewById(R.id.a11yButton)
        restrictedRow = findViewById(R.id.restrictedRow)
        batteryStatus = findViewById(R.id.batteryStatus)
        batteryButton = findViewById(R.id.batteryButton)
        speechStatus = findViewById(R.id.speechStatus)
        speechButton = findViewById(R.id.speechButton)
        aiSwitch = findViewById(R.id.aiSwitch)
        workerUrl = findViewById(R.id.workerUrl)
        appToken = findViewById(R.id.appToken)
        testResult = findViewById(R.id.testResult)
        language = findViewById(R.id.language)
        onDeviceSwitch = findViewById(R.id.onDeviceSwitch)
        liveBarsSwitch = findViewById(R.id.liveBarsSwitch)
        cloudSwitch = findViewById(R.id.cloudSwitch)
        learnSwitch = findViewById(R.id.learnSwitch)
        quickSwitch = findViewById(R.id.quickSwitch)
        learnedWords = findViewById(R.id.learnedWords)
        habitsView = findViewById(R.id.habitsView)
        contextSwitch = findViewById(R.id.contextSwitch)
        vocabulary = findViewById(R.id.vocabulary)
        lastDictation = findViewById(R.id.lastDictation)

        applyInsets()
        loadFields()
        wireButtons()
    }

    override fun onResume() {
        super.onResume()
        // The bubble can switch live bars off by itself, so re-read it.
        liveBarsSwitch.isChecked = prefs.liveBars
        cloudSwitch.isChecked = prefs.cloudSpeech
        refreshStatus()
        showLastDictation()
        showLearning()
        // Dictating into "Try it" doesn't pause this screen, so update when the bubble saves a result.
        prefs.addListener(lastDictationListener)
    }

    private val lastDictationListener = Prefs.Listener {
        showLastDictation()
        showLearning()
    }

    /** Learned words and habits. Leaves the words box alone while you're editing it. */
    private fun showLearning() {
        val stored = prefs.learnedWords
        if (!learnedWords.hasFocus() && learnedWords.text.toString() == loadedLearned) {
            learnedWords.setText(stored)
            loadedLearned = stored
        }
        val habits = prefs.allHabits()
        habitsView.text = if (habits.isEmpty()) {
            getString(R.string.habits_none)
        } else {
            getString(R.string.habits_title) + "\n" + habits.entries.joinToString("\n") { (app, codes) ->
                val described = codes.joinToString(", ") { code ->
                    when (code) {
                        Prefs.HABIT_NO_FINAL_STOP -> getString(R.string.habit_no_final_stop)
                        Prefs.HABIT_LOWERCASE_START -> getString(R.string.habit_lowercase_start)
                        else -> code
                    }
                }
                "• ${appName(app)}: $described"
            }
        }
    }

    private fun appName(appPackage: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(appPackage, 0)).toString()
    } catch (e: Exception) {
        appPackage
    }

    private fun showLastDictation() {
        val heard = prefs.lastHeard
        lastDictation.text = if (heard.isEmpty()) {
            getString(R.string.last_empty)
        } else {
            val typed = prefs.lastTyped.ifEmpty { getString(R.string.last_nothing_typed) }
            getString(R.string.last_format, heard, typed, prefs.lastHow)
        }
    }

    override fun onPause() {
        prefs.removeListener(lastDictationListener)
        saveFields()
        super.onPause()
    }

    override fun onDestroy() {
        supportChecker?.destroy()
        supportChecker = null
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MIC && !hasMic() && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            // Denied for good: the only way back is the app's settings page.
            openAppInfo()
        }
        refreshStatus()
    }

    // ---- Layout ----

    /** Keep content clear of the status bar, navigation bar and keyboard (Android 15 draws edge to edge). */
    private fun applyInsets() {
        val root = findViewById<View>(R.id.root)
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime(),
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun loadFields() {
        aiSwitch.isChecked = prefs.aiCleanup
        workerUrl.setText(prefs.workerUrl)
        appToken.setText(prefs.appToken)
        language.setText(prefs.language)
        onDeviceSwitch.isChecked = prefs.preferOnDevice
        liveBarsSwitch.isChecked = prefs.liveBars
        cloudSwitch.isChecked = prefs.cloudSpeech
        contextSwitch.isChecked = prefs.useContext
        learnSwitch.isChecked = prefs.learnFromCorrections
        quickSwitch.isChecked = prefs.quickShortMessages
        loadedLearned = prefs.learnedWords
        learnedWords.setText(loadedLearned)
        vocabulary.setText(prefs.vocabulary)
    }

    private fun saveFields() {
        val languageChanged = language.text.toString().trim() != prefs.language
        prefs.aiCleanup = aiSwitch.isChecked
        prefs.workerUrl = workerUrl.text.toString()
        prefs.appToken = appToken.text.toString()
        prefs.language = language.text.toString()
        prefs.preferOnDevice = onDeviceSwitch.isChecked
        prefs.liveBars = liveBarsSwitch.isChecked
        prefs.cloudSpeech = cloudSwitch.isChecked
        prefs.learnFromCorrections = learnSwitch.isChecked
        prefs.quickShortMessages = quickSwitch.isChecked
        // Only write learned words if you edited them here, so new ones learned meanwhile aren't lost.
        val editedLearned = learnedWords.text.toString()
        if (editedLearned != loadedLearned) {
            prefs.learnedWords = editedLearned
            loadedLearned = editedLearned
        }
        prefs.useContext = contextSwitch.isChecked
        prefs.vocabulary = vocabulary.text.toString()
        if (languageChanged) refreshSpeechModel()
    }

    private fun wireButtons() {
        micButton.setOnClickListener {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
        }
        a11yButton.setOnClickListener { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        findViewById<Button>(R.id.appInfoButton).setOnClickListener { openAppInfo() }
        batteryButton.setOnClickListener {
            val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            if (!open(request)) open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        speechButton.setOnClickListener { downloadSpeechModel() }

        findViewById<Button>(R.id.testButton).setOnClickListener {
            saveFields()
            if (!prefs.isCleanupConfigured) {
                testResult.text = getString(R.string.test_need_config)
                return@setOnClickListener
            }
            testResult.text = getString(R.string.test_running)
            CleanupClient.test(prefs) { message -> testResult.text = message }
        }
        findViewById<Button>(R.id.saveButton).setOnClickListener {
            saveFields()
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.forgetHabitsButton).setOnClickListener {
            prefs.forgetHabits()
            showLearning()
            Toast.makeText(this, R.string.habits_forgotten, Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.resetButton).setOnClickListener {
            prefs.offsetX = 0
            prefs.offsetY = 0
            Toast.makeText(this, R.string.position_reset, Toast.LENGTH_SHORT).show()
        }
    }

    // ---- Setup checklist ----

    private fun refreshStatus() {
        val micOk = hasMic()
        micStatus.text = getString(if (micOk) R.string.mic_ok else R.string.mic_missing)
        micButton.visibility = if (micOk) View.GONE else View.VISIBLE

        val serviceOn = isBubbleServiceEnabled()
        a11yStatus.text = getString(if (serviceOn) R.string.a11y_ok else R.string.a11y_missing)
        a11yButton.visibility = if (serviceOn) View.GONE else View.VISIBLE
        restrictedRow.visibility = if (serviceOn) View.GONE else View.VISIBLE

        val batteryOk = getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true
        batteryStatus.text = getString(if (batteryOk) R.string.battery_ok else R.string.battery_missing)
        batteryButton.visibility = if (batteryOk) View.GONE else View.VISIBLE

        refreshSpeechModel()
    }

    private fun hasMic() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun isBubbleServiceEnabled(): Boolean {
        val expected = ComponentName(this, BubbleService::class.java)
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
    }

    /** Is the on-device speech model for the chosen language installed? (Android 14+) */
    private fun refreshSpeechModel() {
        val lang = prefs.language
        speechButton.visibility = View.GONE
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            speechStatus.text = getString(R.string.speech_no_on_device)
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            speechStatus.text = getString(R.string.speech_on_device_available)
            return
        }
        speechStatus.text = getString(R.string.speech_checking)
        val checker = onDeviceRecognizer()
        checker.checkRecognitionSupport(
            recognitionIntent(lang),
            mainExecutor,
            object : RecognitionSupportCallback {
                override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                    if (isDestroyed) return
                    val installed = recognitionSupport.installedOnDeviceLanguages.hasLanguage(lang)
                    val pending = recognitionSupport.pendingOnDeviceLanguages.hasLanguage(lang)
                    val supported = recognitionSupport.supportedOnDeviceLanguages.hasLanguage(lang)
                    speechStatus.text = when {
                        installed -> getString(R.string.speech_installed, lang)
                        pending -> getString(R.string.speech_pending, lang)
                        supported -> getString(R.string.speech_downloadable, lang)
                        else -> getString(R.string.speech_not_offered, lang)
                    }
                    speechButton.visibility = if (supported && !installed && !pending) View.VISIBLE else View.GONE
                }

                override fun onError(error: Int) {
                    if (isDestroyed) return
                    speechStatus.text = getString(R.string.speech_check_failed, error)
                    speechButton.visibility = View.VISIBLE
                }
            },
        )
    }

    private fun downloadSpeechModel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        onDeviceRecognizer().triggerModelDownload(recognitionIntent(prefs.language))
        Toast.makeText(this, R.string.speech_download_started, Toast.LENGTH_LONG).show()
        speechButton.postDelayed({ if (!isDestroyed) refreshSpeechModel() }, 4_000)
    }

    private fun onDeviceRecognizer(): SpeechRecognizer =
        supportChecker ?: SpeechRecognizer.createOnDeviceSpeechRecognizer(this).also { supportChecker = it }

    private fun recognitionIntent(lang: String) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)

    private fun List<String>.hasLanguage(lang: String): Boolean {
        val wanted = lang.replace('_', '-')
        return any { it.replace('_', '-').equals(wanted, ignoreCase = true) }
    }

    // ---- Helpers ----

    private fun openAppInfo() {
        open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    private fun open(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }

    private companion object {
        const val REQUEST_MIC = 1
    }
}
