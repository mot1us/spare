package io.github.mot1us.spare;

import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

/** Test-only service runs in its own APK process without the target app's Kotlin runtime. */
public class FakeRecognitionService extends RecognitionService {
    @Override protected void onStartListening(Intent intent, Callback callback) {
        try {
            callback.readyForSpeech(new Bundle());
            // The recognizer unbinds between requests; its service process may
            // restart. Keep the scripted response independent of that lifetime.
            if (new File(getFilesDir(), "voice-first-result").createNewFile()) {
                Bundle results = new Bundle();
                ArrayList<String> words = new ArrayList<>(); words.add("Channel 2");
                results.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, words);
                callback.results(results);
            } else callback.error(SpeechRecognizer.ERROR_NO_MATCH);
        } catch (android.os.RemoteException | IOException ignored) { }
    }
    @Override protected void onStopListening(Callback callback) { }
    @Override protected void onCancel(Callback callback) { }
}
