package io.github.mot1us.spare

import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource

@UnstableApi
object PlaybackErrors {
    fun httpFailure(error: Throwable) = generateSequence(error) { it.cause }.take(12).filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
    fun message(error: PlaybackException): String {
        val causes = generateSequence<Throwable>(error) { it.cause }.take(12).toList()
        causes.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.let {
            return SourceErrors.message(HttpFailure(it.responseCode))
        }
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FAILED -> "The device could not decode this channel's video. Try another channel or stream format."
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "The provider did not send a supported video stream. Try another stream format or channel."
            else -> SourceErrors.message(error)
        } + " (${error.errorCodeName})"
    }
}
