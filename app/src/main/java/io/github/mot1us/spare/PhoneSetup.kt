package io.github.mot1us.spare

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** What the phone form sent. The TV shows it in Add playlist for the viewer to review and save. */
data class PhoneSetupResult(val xtream: Boolean, val name: String, val url: String, val username: String, val password: String, val epg: String)

/** The phone side of "Set up from my phone": one plain HTML form, no scripts, no outside requests. */
object PhoneSetupForm {
    const val MAX_BODY = 16 * 1024
    private val limits = mapOf("type" to 10, "name" to 64, "m3u" to 2000, "server" to 2000, "username" to 200, "password" to 200, "epg" to 2000)

    /** Decodes a urlencoded body, keeping only the form's own fields and cutting each to its length limit. */
    fun parse(body: String): Map<String, String> = body.split('&').mapNotNull { pair ->
        val key = runCatching { URLDecoder.decode(pair.substringBefore('='), "UTF-8") }.getOrNull() ?: return@mapNotNull null
        val limit = limits[key] ?: return@mapNotNull null
        val value = runCatching { URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8") }.getOrNull() ?: return@mapNotNull null
        key to (if (key == "password") value else value.trim()).filterNot { it == '\r' || it == '\n' }.take(limit)
    }.toMap()

    /** Null with a message for the phone when the form is incomplete. */
    fun result(fields: Map<String, String>): Pair<PhoneSetupResult?, String> {
        val xtream = fields["type"] == "xtream"
        val url = fields[if (xtream) "server" else "m3u"].orEmpty()
        fun web(value: String) = value.startsWith("http://", true) || value.startsWith("https://", true)
        val problem = when {
            url.isBlank() -> if (xtream) "Enter the server address." else "Enter the playlist link."
            !web(url) -> "The address must start with http:// or https://."
            xtream && (fields["username"].isNullOrBlank() || fields["password"].isNullOrEmpty()) -> "Enter the username and password."
            fields["epg"].orEmpty().let { it.isNotBlank() && !web(it) } -> "The guide link must start with http:// or https://."
            else -> ""
        }
        if (problem.isNotEmpty()) return null to problem
        return PhoneSetupResult(xtream, fields["name"].orEmpty(), url, if (xtream) fields["username"].orEmpty() else "",
            if (xtream) fields["password"].orEmpty() else "", fields["epg"].orEmpty()) to ""
    }

    private fun escape(value: String) = buildString { value.forEach { c ->
        append(when (c) { '&' -> "&amp;"; '<' -> "&lt;"; '>' -> "&gt;"; '"' -> "&quot;"; '\'' -> "&#39;"; else -> c.toString() })
    } }

    private const val STYLE = """
        :root{color-scheme:dark}body{margin:0;font:16px/1.4 system-ui,sans-serif;background:#0d1117;color:#f2f2f2}
        main{max-width:480px;margin:0 auto;padding:24px 16px 40px}h1{font-size:24px;margin:0 0 4px}p{color:#b8bfc8;margin:0 0 20px}
        label{display:block;margin:0 0 14px;font-size:14px;color:#b8bfc8}
        input[type=text],input[type=password]{display:block;box-sizing:border-box;width:100%;margin-top:6px;padding:12px;border:1px solid #39424e;border-radius:10px;background:#161b22;color:#fff;font-size:16px}
        .kind{display:flex;gap:8px;margin:0 0 18px}.kind label{flex:1;margin:0;padding:12px;border:1px solid #39424e;border-radius:10px;text-align:center;color:#fff}
        .kind input{margin-right:6px}.kind label:has(input:checked){border-color:#fff;background:#222a35}
        form:has(input[value=xtream]:checked) .m3u{display:none}form:not(:has(input[value=xtream]:checked)) .xt{display:none}
        button{width:100%;padding:14px;border:0;border-radius:10px;background:#fff;color:#000;font-size:17px;font-weight:600}
        .error{color:#ffb4a9;margin:0 0 16px}
    """

    fun page(fields: Map<String, String> = emptyMap(), error: String = ""): String {
        fun value(key: String) = escape(fields[key].orEmpty())
        fun field(label: String, key: String, extra: String = "") =
            """<label>$label<input type="${if (key == "password") "password" else "text"}" name="$key" value="${if (key == "password") "" else value(key)}" maxlength="${limits.getValue(key)}" $extra></label>"""
        val xtream = fields["type"] == "xtream"
        val url = """autocapitalize="off" autocorrect="off" spellcheck="false" inputmode="url""""
        return """<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Spare setup</title><style>$STYLE</style></head><body><main>
<h1>Add a playlist to Spare</h1><p>Fill this in and press Send. Your TV shows it so you can check it before saving.</p>
${if (error.isNotEmpty()) "<p class=\"error\">${escape(error)}</p>" else ""}
<form method="post" autocomplete="off">
<div class="kind"><label><input type="radio" name="type" value="m3u"${if (xtream) "" else " checked"}>M3U link</label><label><input type="radio" name="type" value="xtream"${if (xtream) " checked" else ""}>Xtream Codes</label></div>
${field("Name (optional)", "name")}
<div class="m3u">${field("Playlist link", "m3u", url)}</div>
<div class="xt">${field("Server address (include the port)", "server", url)}${field("Username", "username", """autocapitalize="off" autocorrect="off" spellcheck="false"""")}${field("Password", "password")}</div>
${field("Guide (XMLTV) link, optional", "epg", url)}
<button type="submit">Send to TV</button></form></main></body></html>"""
    }

    fun sent() = """<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Sent to Spare</title><style>$STYLE</style></head><body><main><h1>Sent to your TV</h1><p>Check the details on your TV and choose Save. You can close this page.</p></main></body></html>"""

    fun gone() = """<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Spare setup</title><style>$STYLE</style></head><body><main><h1>This link has expired</h1><p>Open "Set up from my phone" on your TV again for a new code.</p></main></body></html>"""
}

/**
 * A one-time web server on this TV's home network address for the phone setup form.
 * It accepts only private-network clients, the random link in the QR code, and one completed form, then stops.
 * Nothing leaves the home network and no outside service is involved.
 */
class PhoneSetupServer(private val post: (Runnable) -> Unit, private val deliver: (PhoneSetupResult) -> Unit,
    private val lifetimeMs: Long = 10 * 60_000L, private val bind: InetAddress? = null) {
    @Volatile private var socket: ServerSocket? = null
    @Volatile var url: String? = null; private set
    private val token = ByteArray(16).also(SecureRandom()::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    private var misses = 0

    /** Returns the link for the QR code, or null when the TV has no home network address. */
    fun start(): String? {
        if (socket != null) return url
        val address = bind ?: lanAddress() ?: return null
        val server = runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(address, 0), 4); soTimeout = 1000 } }.getOrNull() ?: return null
        socket = server
        val host = address.hostAddress
        url = "http://$host:${server.localPort}/p/$token"
        val ends = System.currentTimeMillis() + lifetimeMs
        Thread({
            try {
                while (socket === server && System.currentTimeMillis() < ends) {
                    val client = try { server.accept() } catch (_: SocketTimeoutException) { continue }
                    client.use { runCatching { handle(it) } }
                }
            } catch (_: Exception) {
            } finally { stop(server) }
        }, "spare-phone-setup").apply { isDaemon = true }.start()
        return url
    }

    fun stop() { socket?.let(::stop) }
    private fun stop(server: ServerSocket) { if (socket === server) { socket = null }; runCatching { server.close() } }
    val running get() = socket != null

    private fun handle(client: Socket) {
        val remote = client.inetAddress
        if (!(remote.isSiteLocalAddress || remote.isLinkLocalAddress || remote.isLoopbackAddress)) return
        client.soTimeout = 10_000
        val input = client.getInputStream()
        val head = readHead(input) ?: return
        val lines = head.split("\r\n")
        val parts = lines.first().split(' ')
        if (parts.size < 3) return reply(client, 400, "")
        val method = parts[0]; val path = parts[1].substringBefore('?')
        val expected = "/p/$token"
        if (!MessageDigest.isEqual(path.toByteArray(), expected.toByteArray())) {
            if (++misses > 30) stop()
            return reply(client, 404, PhoneSetupForm.gone())
        }
        when (method) {
            "GET" -> reply(client, 200, PhoneSetupForm.page())
            "POST" -> {
                val length = lines.firstNotNullOfOrNull { line -> line.takeIf { it.startsWith("content-length:", true) }?.substringAfter(':')?.trim()?.toIntOrNull() } ?: -1
                if (length !in 0..PhoneSetupForm.MAX_BODY) return reply(client, 413, PhoneSetupForm.page(error = "That was too long to send."))
                val body = ByteArray(length); var read = 0
                while (read < length) { val n = input.read(body, read, length - read); if (n < 0) return; read += n }
                val fields = PhoneSetupForm.parse(String(body, Charsets.UTF_8))
                val (result, problem) = PhoneSetupForm.result(fields)
                if (result == null) return reply(client, 200, PhoneSetupForm.page(fields, problem))
                if (socket == null) return reply(client, 410, PhoneSetupForm.gone())
                reply(client, 200, PhoneSetupForm.sent())
                stop()
                post(Runnable { deliver(result) })
            }
            else -> reply(client, 405, "")
        }
    }

    private fun readHead(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        var match = 0
        while (out.size() < 8192) {
            val b = input.read(); if (b < 0) return null
            out.write(b)
            match = when { b == '\r'.code && match % 2 == 0 -> match + 1; b == '\n'.code && match % 2 == 1 -> match + 1; else -> 0 }
            if (match == 4) return out.toString("ISO-8859-1").trimEnd()
        }
        return null
    }

    private fun reply(client: Socket, status: Int, html: String) {
        val body = html.toByteArray(Charsets.UTF_8)
        val reason = mapOf(200 to "OK", 400 to "Bad Request", 404 to "Not Found", 405 to "Method Not Allowed", 410 to "Gone", 413 to "Payload Too Large")[status] ?: "OK"
        val head = "HTTP/1.1 $status $reason\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\n" +
            "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'\r\nConnection: close\r\n\r\n"
        client.getOutputStream().apply { write(head.toByteArray(Charsets.ISO_8859_1)); write(body); flush() }
    }

    companion object {
        /** The TV's private IPv4 address on Wi-Fi or Ethernet. */
        fun lanAddress(): InetAddress? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("eth")) 0 else 1 }
                .flatMap { it.inetAddresses.toList() }.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
        }.getOrNull()
    }
}
