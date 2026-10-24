package io.github.mot1us.spare

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/** Applies source consent to every segment, key, subtitle, and manifest request. */
@UnstableApi
class ProviderDataSource(private val upstream: DataSource, private val allowHttp: () -> Boolean) : DataSource {
    override fun open(dataSpec: DataSpec): Long {
        if (!Network.allowed(dataSpec.uri.toString(), allowHttp())) throw java.io.IOException("Unsupported or insecure stream address")
        return upstream.open(dataSpec)
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int) = upstream.read(buffer, offset, length)
    override fun getUri(): Uri? = upstream.uri
    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders
    override fun close() = upstream.close()
    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)
}
