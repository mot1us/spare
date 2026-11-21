package io.github.mot1us.spare

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioOutputTest {
    @Test fun aStreamWhoseSoundCannotPlayIsNamed() {
        assertEquals("DTS", AudioOutput.unplayable(listOf(false to listOf(MimeTypes.AUDIO_DTS))))
        assertEquals("Dolby TrueHD / DTS-HD", AudioOutput.unplayable(listOf(false to listOf(MimeTypes.AUDIO_TRUEHD), false to listOf(MimeTypes.AUDIO_DTS_HD))))
    }

    @Test fun oneTrackThatPlaysOrNoSoundAtAllIsNotReported() {
        assertNull(AudioOutput.unplayable(listOf(false to listOf(MimeTypes.AUDIO_DTS), true to listOf(MimeTypes.AUDIO_AAC))))
        assertNull(AudioOutput.unplayable(emptyList()))
    }

    @Test fun aTrackIsDescribedInWords() {
        assertEquals("English · Dolby Digital Plus · 6 ch", AudioOutput.describe(null, "en", MimeTypes.AUDIO_E_AC3, 6))
        assertEquals("Commentary · English · AAC · 2 ch", AudioOutput.describe("Commentary", "en", MimeTypes.AUDIO_AAC, 2))
        assertEquals("DTS", AudioOutput.describe(null, "und", MimeTypes.AUDIO_DTS, -1))
    }

    @Test fun formatsHaveTheirEverydayNames() {
        assertEquals("Dolby Digital Plus", AudioOutput.name(MimeTypes.AUDIO_E_AC3))
        assertEquals("Dolby Atmos", AudioOutput.name(MimeTypes.AUDIO_E_AC3_JOC))
        assertEquals("AAC", AudioOutput.name(MimeTypes.AUDIO_AAC))
    }
}
