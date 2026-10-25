package io.github.mot1us.spare

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** Uses the configured recognition service directly: no activity or app chooser. */
class NativeVoiceInput(private val context: Context, private val message: (String) -> Unit, private val result: (String) -> Unit, private val listening: (Boolean) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private val timeout = Runnable { stop(); message("Voice timed out. Choose Voice to retry, or select the field to type.") }
    fun start() {
        stop()
        if (!SpeechRecognizer.isRecognitionAvailable(context)) { message("Voice input is unavailable on this TV. Select the field to type."); return }
        try {
            val engine = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer = engine
            engine.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { if (recognizer === engine) message("Listening… speak a channel name") }
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() { if (recognizer === engine) message("Searching…") }
                override fun onError(error: Int) {
                    if (recognizer !== engine) return
                    stop()
                    message(when (error) {
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is needed for voice. You can still type."
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech heard. Choose Voice to retry, or select the field to type."
                        else -> "Voice input is unavailable right now. Choose Voice to retry, or type."
                    })
                }
                override fun onResults(results: Bundle?) {
                    if (recognizer !== engine) return
                    val words = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull { it.isNotBlank() }
                    stop()
                    if (words == null) message("No match. Choose Voice to retry, or type.")
                    else { message("Search complete. Choose Voice to speak again, or edit the field."); result(words.replace(Regex("[\\p{Cc}]+"), " ").trim().take(128)) }
                }
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            listening(true); message("Starting microphone…")
            engine.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1).putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false))
            handler.postDelayed(timeout, 15000)
        } catch (_: RuntimeException) { stop(); message("Voice input is unavailable on this TV. Select the field to type.") }
    }
    fun stop() {
        handler.removeCallbacks(timeout)
        val old = recognizer; recognizer = null
        if (old != null) { runCatching { old.cancel(); old.destroy() }; listening(false) }
    }
}
