package io.github.mot1us.spare

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.decoder.ffmpeg.FfmpegLibrary

/**
 * How a stream's sound reaches the TV. Surround sound (Dolby, DTS) is normally sent on undecoded for the TV or a receiver
 * to decode ("passthrough"), when Android says they can. A TV that says so but passes the sound to a soundbar that
 * cannot stays silent without any error, so Settings › Playback › Surround sound can have this box decode it instead.
 */
@UnstableApi
internal object AudioOutput {
    /** True: this box decodes Dolby and DTS sound itself into ordinary sound every TV plays. */
    @Volatile var decodeOnBox = false
    private val surround = setOf(MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC, MimeTypes.AUDIO_AC4, MimeTypes.AUDIO_TRUEHD,
        MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_EXPRESS, MimeTypes.AUDIO_DTS_X)

    /** The player's audio output, refusing undecoded surround sound while [decodeOnBox] is on, so a decoder on this box is used. */
    fun sink(base: AudioSink): AudioSink = object : ForwardingAudioSink(base) {
        override fun supportsFormat(format: Format) = if (decodeOnBox && format.sampleMimeType in surround) false else super.supportsFormat(format)
        override fun getFormatSupport(format: Format) = if (decodeOnBox && format.sampleMimeType in surround) AudioSink.SINK_FORMAT_UNSUPPORTED else super.getFormatSupport(format)
    }

    private val decodedBySpare = setOf(MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_EXPRESS, MimeTypes.AUDIO_TRUEHD)
    /**
     * The decoders Media3 may use, without the box's own DTS and Dolby TrueHD decoders: whether a box is licensed for
     * them varies, and some report one that plays silence (an onn 4K Pro's DTS decoder, and other players there with it).
     * Sending the sound on undecoded to a TV or receiver that takes it is unaffected; anything else is decoded in Spare
     * by FFmpeg (the ffmpeg module), the same on every box. Without that library the box's decoders are used as before.
     */
    val codecs = MediaCodecSelector { mime, secure, tunneling ->
        if (mime in decodedBySpare && FfmpegLibrary.isAvailable() && FfmpegLibrary.supportsFormat(mime)) emptyList()
        else MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
    }

    fun name(mime: String?): String = when (mime) {
        MimeTypes.AUDIO_AC3 -> "Dolby Digital"; MimeTypes.AUDIO_E_AC3 -> "Dolby Digital Plus"; MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Atmos"
        MimeTypes.AUDIO_AC4 -> "Dolby AC-4"; MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"; MimeTypes.AUDIO_DTS -> "DTS"; MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
        MimeTypes.AUDIO_DTS_EXPRESS -> "DTS Express"; MimeTypes.AUDIO_DTS_X -> "DTS:X"; MimeTypes.AUDIO_AAC -> "AAC"; MimeTypes.AUDIO_MPEG -> "MP3"
        MimeTypes.AUDIO_OPUS -> "Opus"; MimeTypes.AUDIO_VORBIS -> "Vorbis"; MimeTypes.AUDIO_FLAC -> "FLAC"; MimeTypes.AUDIO_RAW -> "PCM"
        else -> mime?.removePrefix("audio/")?.uppercase(java.util.Locale.ROOT) ?: "unknown"
    }

    /** The sound a stream carries when none of it can play on this box (no decoder and no passthrough); null when some can, or it has none. */
    fun unplayable(tracks: Tracks): String? = unplayable(tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        .map { group -> group.isSupported to (0 until group.length).map { group.getTrackFormat(it).sampleMimeType } })
    /** [audio]: each audio track group, whether any of it can play, and its formats. */
    internal fun unplayable(audio: List<Pair<Boolean, List<String?>>>): String? {
        if (audio.isEmpty() || audio.any { it.first }) return null
        return audio.flatMap { it.second }.map(::name).distinct().joinToString(" / ")
    }

    /** One audio track in words: "English · Dolby Digital Plus · 6 ch", for the Audio track page and the report. */
    fun describe(format: Format): String = describe(format.label, format.language, format.sampleMimeType, format.channelCount)
    internal fun describe(label: String?, language: String?, mime: String?, channels: Int): String {
        val spoken = language?.takeIf { it.isNotBlank() && it != C.LANGUAGE_UNDETERMINED }?.let { java.util.Locale.forLanguageTag(it).displayLanguage.ifBlank { it } }
        return listOfNotNull(label?.takeIf { it.isNotBlank() && it != spoken }, spoken, name(mime), channels.takeIf { it > 0 }?.let { "$it ch" }).joinToString(" · ")
    }
    /** Every audio track a stream carries, which one plays, and which this box cannot play. */
    fun tracks(tracks: Tracks): String = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.flatMap { group ->
        (0 until group.length).map { index ->
            describe(group.getTrackFormat(index)) + when { group.isTrackSelected(index) -> " (playing)"; !group.isTrackSupported(index) -> " (this box can’t play it)"; else -> "" }
        }
    }.ifEmpty { listOf("no audio tracks") }.joinToString(", ")
    /** How the playing sound left this box, for the performance report. */
    fun route(config: AudioSink.AudioTrackConfig, decoder: String): String = buildString {
        append(when {
            !Util.isEncodingLinearPcm(config.encoding) -> "sent on undecoded (passthrough)"
            decoder.startsWith("ffmpeg", ignoreCase = true) -> "decoded by Spare ($decoder)"
            else -> "decoded on this box" + decoder.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        })
        if (config.tunneling) append(" · tunneled")
        if (config.offload) append(" · offloaded")
    }
}
