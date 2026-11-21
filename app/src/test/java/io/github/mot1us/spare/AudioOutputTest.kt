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

    @Test fun formatsHaveTheirEverydayNames() {
        assertEquals("Dolby Digital Plus", AudioOutput.name(MimeTypes.AUDIO_E_AC3))
        assertEquals("Dolby Atmos", AudioOutput.name(MimeTypes.AUDIO_E_AC3_JOC))
        assertEquals("AAC", AudioOutput.name(MimeTypes.AUDIO_AAC))
    }
}
