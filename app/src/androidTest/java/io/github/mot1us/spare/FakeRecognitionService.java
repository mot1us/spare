package io.github.mot1us.spare;

import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognitionService;
import android.speech.SpeechRecognizer;
import java.util.ArrayList;

/** Test-only service runs in its own APK process without the target app's Kotlin runtime. */
public class FakeRecognitionService extends RecognitionService {
    private static int calls;
    @Override protected void onStartListening(Intent intent, Callback callback) {
        try {
            callback.readyForSpeech(new Bundle());
            if (calls++ == 0) {
                Bundle results = new Bundle();
                ArrayList<String> words = new ArrayList<>(); words.add("Channel 2");
                results.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, words);
                callback.results(results);
            } else callback.error(SpeechRecognizer.ERROR_NO_MATCH);
        } catch (android.os.RemoteException ignored) { }
    }
    @Override protected void onStopListening(Callback callback) { }
    @Override protected void onCancel(Callback callback) { }
}
