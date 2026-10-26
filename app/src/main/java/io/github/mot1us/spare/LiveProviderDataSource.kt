package io.github.mot1us.spare

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.DefaultHttpDataSource
import java.io.IOException

/** Applies source consent to every segment, key, subtitle, and manifest request. */
@UnstableApi
class LiveProviderDataSource(private val allowHttp: () -> Boolean) : DataSource {
    private var upstream: DataSource? = null
    private val listeners = ArrayList<TransferListener>()
    override fun open(dataSpec: DataSpec): Long {
        val consent = allowHttp()
        if (!Network.allowed(dataSpec.uri.toString(), consent)) throw IOException(SourceFailure(if (dataSpec.uri.scheme == "http" && !consent) SourceProblem.HTTP_REQUIRED else SourceProblem.ADDRESS))
        val client = DefaultHttpDataSource.Factory().setUserAgent("Spare/${BuildConfig.VERSION_NAME}")
            .setConnectTimeoutMs(12000).setReadTimeoutMs(30000).setAllowCrossProtocolRedirects(consent).createDataSource()
        listeners.forEach(client::addTransferListener)
        upstream = client
        try {
            val length = client.open(dataSpec)
            if (!Network.allowed(client.uri.toString(), consent)) throw IOException(SourceFailure(SourceProblem.ADDRESS))
            return length
        } catch (error: Exception) { runCatching { client.close() }; upstream = null; throw error }
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int) = checkNotNull(upstream).read(buffer, offset, length)
    override fun getUri(): Uri? = upstream?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = upstream?.responseHeaders ?: emptyMap()
    override fun close() { upstream?.close(); upstream = null }
    override fun addTransferListener(transferListener: TransferListener) { listeners.add(transferListener); upstream?.addTransferListener(transferListener) }
}
