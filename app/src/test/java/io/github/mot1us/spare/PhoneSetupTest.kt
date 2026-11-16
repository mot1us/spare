package io.github.mot1us.spare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.URLEncoder

class PhoneSetupTest {
    private fun request(url: String, body: String? = null): Pair<Int, String> {
        val connection = URL(url).openConnection() as HttpURLConnection
        if (body != null) {
            connection.requestMethod = "POST"; connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = connection.responseCode
        val text = (if (code < 400) connection.inputStream else connection.errorStream)?.bufferedReader()?.readText().orEmpty()
        return code to text
    }
    private fun form(vararg fields: Pair<String, String>) = fields.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }

    @Test fun phoneFormFillsTheTvOnceThenStops() {
        val results = java.util.Collections.synchronizedList(mutableListOf<PhoneSetupResult>())
        val opens = java.util.concurrent.atomic.AtomicInteger()
        val server = PhoneSetupServer({ it.run() }, { results += it }, bind = InetAddress.getLoopbackAddress(), opened = { opens.incrementAndGet() })
        val url = server.start()!!
        assertEquals("Nothing announced before a phone opens the form", 0, opens.get())
        assertTrue(url.matches(Regex("http://127\\.0\\.0\\.1:\\d+/p/[A-Za-z0-9_-]{22}")))
        val (code, page) = request(url)
        assertEquals(200, code); assertTrue(page.contains("Send to TV")); assertFalse("No scripts on the phone page", page.contains("<script"))
        assertEquals(404, request(url.substringBeforeLast('/') + "/wrongtoken").first)
        request(url)
        assertEquals("The TV hears about the phone once, not on every reload, and never for a wrong link", 1, opens.get())
        val incomplete = request(url, form("type" to "xtream", "server" to "http://example.test:8080", "username" to "<b>me</b>"))
        assertTrue(incomplete.second.contains("Enter the username and password."))
        assertTrue("Values are escaped", incomplete.second.contains("&lt;b&gt;me&lt;/b&gt;"))
        assertTrue(results.isEmpty())
        val sent = request(url, form("type" to "xtream", "name" to "Home", "server" to " http://example.test:8080 ", "username" to "user", "password" to " pass word ", "m3u" to "ignored"))
        assertTrue(sent.second.contains("Sent to your TV"))
        repeat(50) { if (results.isEmpty()) Thread.sleep(20) }
        assertEquals(listOf(PhoneSetupResult(true, "Home", "http://example.test:8080", "user", " pass word ", "")), results)
        Thread.sleep(200)
        assertFalse("One form only", server.running)
        assertTrue(runCatching { request(url) }.isFailure)
    }

    @Test fun formRulesAndLimits() {
        assertEquals("Enter the playlist link.", PhoneSetupForm.result(PhoneSetupForm.parse("type=m3u")).second)
        assertEquals("The address must start with http:// or https://.", PhoneSetupForm.result(PhoneSetupForm.parse("type=m3u&m3u=ftp%3A%2F%2Fx")).second)
        val m3u = PhoneSetupForm.result(PhoneSetupForm.parse("type=m3u&m3u=https%3A%2F%2Fexample.test%2Fa.m3u&username=x&password=y")).first!!
        assertEquals(PhoneSetupResult(false, "", "https://example.test/a.m3u", "", "", ""), m3u)
        val fields = PhoneSetupForm.parse("name=" + "n".repeat(500) + "&unknown=1&m3u=http%3A%2F%2Fa%0D%0Ab")
        assertEquals(64, fields["name"]!!.length); assertNull(fields["unknown"]); assertEquals("http://ab", fields["m3u"])
    }

    @Test fun oversizedFormsAreRefused() {
        val results = java.util.Collections.synchronizedList(mutableListOf<PhoneSetupResult>())
        val server = PhoneSetupServer({ it.run() }, { results += it }, bind = InetAddress.getLoopbackAddress())
        val url = server.start()!!
        assertEquals(413, request(url, "name=" + "x".repeat(PhoneSetupForm.MAX_BODY + 1)).first)
        assertTrue(results.isEmpty()); assertTrue(server.running)
        server.stop(); assertFalse(server.running)
    }
}
