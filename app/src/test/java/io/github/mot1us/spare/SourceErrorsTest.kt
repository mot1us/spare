package io.github.mot1us.spare

import java.io.IOException
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import java.net.ServerSocket
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.*
import org.junit.Test

class SourceErrorsTest {
    @Test fun preservesActualHttpStatusWithoutDisplayingResponseOrCredentialUrl() {
        ServerSocket(0).use { server ->
            val worker = Thread {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    socket.getOutputStream().write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                }
            }.apply { start() }
            val failure = assertThrows(HttpFailure::class.java) { Network.get("http://127.0.0.1:${server.localPort}/?password=private-secret", true) }
            worker.join(3000)
            assertEquals(403, failure.status)
            assertTrue(SourceErrors.message(failure).contains("HTTP 403"))
            assertFalse(SourceErrors.message(failure).contains("private-secret"))
        }
    }
    @Test fun identifiesDnsTimeoutAndCertificateFailures() {
        assertTrue(SourceErrors.message(UnknownHostException("private-url")).contains("server name"))
        assertTrue(SourceErrors.message(SocketTimeoutException("private-url")).contains("timed out"))
        assertTrue(SourceErrors.message(SSLHandshakeException("private-url")).contains("HTTPS"))
    }
    @Test fun unknownExceptionsAndNestedCausesNeverExposeRawMessages() {
        assertFalse(SourceErrors.message(IOException("https://private.example/?password=private-secret")).contains("private-secret"))
        assertTrue(SourceErrors.message(IOException("private-secret", HttpFailure(401))).contains("HTTP 401"))
    }
    @Test fun recognizesXtreamAuthenticationAndInactiveResponses() {
        val auth = assertThrows(SourceFailure::class.java) { Importers.xtreamArray("""{"user_info":{"auth":0}}""") }
        assertEquals(SourceProblem.AUTH, auth.problem)
        val expired = assertThrows(SourceFailure::class.java) { Importers.xtreamArray("""{"user_info":{"auth":1,"status":"Expired"}}""") }
        assertEquals(SourceProblem.INACTIVE, expired.problem)
        assertEquals(0, Importers.xtreamArray("[]").length())
    }
    @Test fun rejectsHtmlInsteadOfAnXtreamArray() {
        assertEquals(SourceProblem.RESPONSE, assertThrows(SourceFailure::class.java) { Importers.xtreamArray("<html>provider login</html>") }.problem)
    }
    @Test fun normalizesKnownApiSuffixWhilePreservingHttpsPortAndCustomPath() {
        assertEquals("https://example.com:8443/panel", Importers.serverBase("https://example.com:8443/panel/player_api.php/"))
        assertEquals("https://example.com:8443/panel", Importers.serverBase(" https://example.com:8443/panel/ "))
        assertEquals("https://example.com:8443/panel/player_api.php?username=u&password=p", Importers.endpoint(Source(name="Test",kind="xtream",url="https://example.com:8443/panel/player_api.php",username="u",password="p"), "player_api.php"))
    }
}
