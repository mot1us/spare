package io.github.mot1us.spare

import android.app.Instrumentation
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.runner.Description
import org.junit.runner.notification.RunListener

/** Stream the active check so a slow runner identifies its blocked test immediately. */
class SpareProgressListener : RunListener() {
    override fun testStarted(description: Description) = report("START", description)
    override fun testFinished(description: Description) = report("FINISH", description)
    private fun report(state: String, description: Description) {
        val result = Bundle().apply { putString(Instrumentation.REPORT_KEY_STREAMRESULT, "\n$state ${description.className}#${description.methodName}\n") }
        InstrumentationRegistry.getInstrumentation().sendStatus(2, result)
    }
}
