package io.github.mot1us.spare

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi

@UnstableApi
object CaptionTracks {
    const val AUTO = "auto"
    const val OFF = "off"
    // Fallback readers only. Real PMT/manifest caption descriptors take precedence.
    val compatibility = listOf(
        Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_CEA608).setAccessibilityChannel(1).setLabel("CC1 · compatibility").build(),
        Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_CEA708).setAccessibilityChannel(1).setLabel("Digital 1 · compatibility").build(),
    )
    data class Choice(val group: Tracks.Group, val index: Int) {
        val format get() = group.getTrackFormat(index)
        val key get() = key(format)
        val compatibility get() = format.label?.endsWith(" · compatibility") == true ||
            (format.label.isNullOrBlank() && format.language.isNullOrBlank() && format.accessibilityChannel == 1 && format.sampleMimeType in setOf(MimeTypes.APPLICATION_CEA608, MimeTypes.APPLICATION_CEA708))
        fun label(): String = if (compatibility) { if (format.sampleMimeType == MimeTypes.APPLICATION_CEA708) "Digital 1 · compatibility" else "CC1 · compatibility" } else format.label ?: listOfNotNull(
            format.language?.let { java.util.Locale.forLanguageTag(it).displayLanguage },
            format.sampleMimeType?.substringAfterLast('/'),
            format.accessibilityChannel.takeIf { it > 0 }?.let { "CC $it" },
        ).joinToString(" · ").ifBlank { "Subtitle track" }
    }
    fun choices(tracks: Tracks) = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.flatMap { group ->
        (0 until group.length).filter { group.isTrackSupported(it) }.map { Choice(group, it) }
    }
    fun key(format: Format) = "track:" + listOf(format.sampleMimeType.orEmpty(), format.language.orEmpty(), format.accessibilityChannel.toString(), format.label.orEmpty()).joinToString("|")
}
