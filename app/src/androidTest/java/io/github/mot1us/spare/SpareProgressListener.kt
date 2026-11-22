package io.github.mot1us.spare

import android.app.Instrumentation
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.runner.Description
import org.junit.runner.notification.RunListener

/** Stream the active check so a slow runner identifies its blocked test immediately. */
class SpareProgressListener : RunListener() {
    override fun testStarted(description: Description) = report("START", description)
    override fun testFinished(description: Description) = report("FINISH", description, heap())
    /** Memory still in use after a test, so memory one test leaves behind shows up before the suite runs out of it. */
    private fun heap(): String {
        val runtime = Runtime.getRuntime()
        repeat(2) { runtime.gc(); System.runFinalization() }
        val used = (runtime.totalMemory() - runtime.freeMemory()) / 1048576
        return " · heap $used MB" + if (used >= 60) " · busy: " + busyThreads() else ""
    }
    /** Threads still running Spare code after a test, with the Spare frame they are in: what keeps a test's data alive. */
    private fun busyThreads(): String = Thread.getAllStackTraces().entries.filter { (thread, _) -> thread !== Thread.currentThread() && thread.name != "main" }
        .mapNotNull { (thread, stack) -> stack.firstOrNull { it.className.startsWith("io.github.mot1us.spare") && !it.className.contains("Test") }?.let { "${thread.name}@${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" } }
        .joinToString("; ").ifEmpty { "none" }
    private fun report(state: String, description: Description, note: String = "") {
        val result = Bundle().apply { putString(Instrumentation.REPORT_KEY_STREAMRESULT, "\n$state ${description.className}#${description.methodName}$note\n") }
        InstrumentationRegistry.getInstrumentation().sendStatus(2, result)
    }
}
