package io.github.mot1us.spare

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

class HttpFailure(val status: Int) : IOException("HTTP $status")
class SourceFailure(val problem: SourceProblem) : IllegalArgumentException(problem.detail)

enum class SourceProblem(val detail: String) {
    HTTP_REQUIRED("This provider uses an HTTP address or redirect. Edit the source and allow unencrypted HTTP only if you trust the provider."),
    ADDRESS("The server address is invalid. Enter the full HTTP or HTTPS server address, including its port if supplied."),
    PLAYLIST("The server did not return an M3U playlist. Check that this is the playlist download URL."),
    RESPONSE("The server did not return a supported Xtream channel list. Check the server address and that the provider supports Xtream Codes."),
    AUTH("The provider rejected the account credentials. Check the username and password."),
    INACTIVE("The provider reports that this account is inactive or expired. Check the account with your provider."),
    NO_CHANNELS("The provider returned no supported live channels. Check the account's live-TV access and source settings."),
    LIMIT("The provider response exceeds the app's import limits."),
}

object SourceErrors {
    // Never display exception text: network/parser messages can contain credential URLs.
    fun message(error: Throwable): String {
        val causes = generateSequence(error) { it.cause }.take(12).toList()
        causes.filterIsInstance<SourceFailure>().firstOrNull()?.let { return it.problem.detail }
        causes.filterIsInstance<HttpFailure>().firstOrNull()?.let { return when (it.status) {
            401 -> "The provider returned HTTP 401 (unauthorized). Check the username and password."
            403 -> "The provider returned HTTP 403 (access denied). Check account access, provider restrictions, or VPN settings."
            404 -> "The provider returned HTTP 404 (not found). Check the server address, port, and path."
            429 -> "The provider returned HTTP 429 (too many requests). Wait a moment before trying again."
            in 500..599 -> "The provider returned HTTP ${it.status} (server error). Try again later."
            else -> "The provider returned HTTP ${it.status}. Check the source settings or contact the provider."
        } }
        if (causes.any { it is SSLException }) return "The HTTPS connection could not be verified. Check the server address and device date/time, or contact the provider about its certificate."
        if (causes.any { it is UnknownHostException }) return "The server name could not be found. Check its spelling and the device's network or DNS connection."
        if (causes.any { it is SocketTimeoutException }) return "The provider connection timed out. Check the server address, port, and network, then retry."
        if (causes.any { it is ConnectException }) return "Could not connect to the provider. Check the server address, port, and network."
        return "Could not read the provider's live channels. Check the source settings and network, then retry."
    }
}
