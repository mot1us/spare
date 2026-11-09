package io.github.mot1us.spare;

import androidx.test.runner.AndroidJUnitRunner;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/** A self-reinstall intentionally kills this instrumentation; the shell verifies return to Spare. */
public class SpareTestRunner extends AndroidJUnitRunner {
    private boolean installSmoke;
    private boolean releaseSmoke;
    @Override public void onCreate(Bundle args) {
        installSmoke = "true".equals(args.getString("installSmoke"));
        releaseSmoke = "true".equals(args.getString("releaseSmoke"));
        super.onCreate(args);
    }
    @Override public void onStart() {
        if (releaseSmoke) {
            Bundle result = new Bundle();
            try {
                new SpareDeviceTest().releaseArtifactSmoke();
                result.putString("releaseSmoke", "passed");
                finish(android.app.Activity.RESULT_OK, result);
            } catch (Exception | AssertionError failure) {
                result.putString("failure", android.util.Log.getStackTraceString(failure));
                finish(android.app.Activity.RESULT_CANCELED, result);
            }
            return;
        }
        if (!installSmoke) { super.onStart(); return; }
        try {
            Context app = getTargetContext();
            File directory = new File(app.getCacheDir(), "updates"); directory.mkdirs();
            try (FileInputStream input = new FileInputStream(app.getApplicationInfo().sourceDir);
                 FileOutputStream output = new FileOutputStream(new File(directory, "spare.apk"))) {
                byte[] bytes = new byte[16384]; int count;
                while ((count = input.read(bytes)) > 0) output.write(bytes, 0, count);
            }
            app.startActivity(new Intent().setClassName(app.getPackageName(), "io.github.mot1us.spare.UpdateInstallActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception failure) { Bundle result = new Bundle(); result.putString("failure", failure.toString()); finish(1, result); }
    }
}
