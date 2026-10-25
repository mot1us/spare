package io.github.mot1us.spare

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.Format
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.hls.BundledHlsMediaChunkExtractor
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaChunkExtractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import java.io.EOFException

/** Recover embedded captions when an HLS master omits them (or incorrectly says NONE). */
@UnstableApi
@androidx.annotation.OptIn(ExperimentalApi::class)
class CaptionHlsExtractors(private val flags: Int, private val delegate: DefaultHlsExtractorFactory = DefaultHlsExtractorFactory(flags, true)) : HlsExtractorFactory by delegate {
    private var parser: SubtitleParser.Factory = DefaultSubtitleParserFactory()
    private var parseDuringExtraction = false
    override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): HlsExtractorFactory {
        parser = subtitleParserFactory; delegate.setSubtitleParserFactory(subtitleParserFactory); return this
    }
    override fun experimentalParseSubtitlesDuringExtraction(parseSubtitlesDuringExtraction: Boolean): HlsExtractorFactory {
        parseDuringExtraction = parseSubtitlesDuringExtraction
        delegate.experimentalParseSubtitlesDuringExtraction(parseSubtitlesDuringExtraction); return this
    }
    override fun getOutputTextFormat(sourceFormat: Format): Format = delegate.getOutputTextFormat(sourceFormat)
    override fun experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies: Int): HlsExtractorFactory {
        delegate.experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies); return this
    }
    override fun createExtractor(uri: Uri, format: Format, muxedCaptionFormats: List<Format>?, timestampAdjuster: TimestampAdjuster, responseHeaders: Map<String, List<String>>, sniffingExtractorInput: ExtractorInput, playerId: PlayerId): HlsMediaChunkExtractor {
        if (muxedCaptionFormats.isNullOrEmpty()) {
            var readerFlags = flags or DefaultTsPayloadReaderFactory.FLAG_IGNORE_SPLICE_INFO_STREAM
            if (!format.codecs.isNullOrEmpty()) {
                if (!MimeTypes.containsCodecsCorrespondingToMimeType(format.codecs, MimeTypes.AUDIO_AAC)) readerFlags = readerFlags or DefaultTsPayloadReaderFactory.FLAG_IGNORE_AAC_STREAM
                if (!MimeTypes.containsCodecsCorrespondingToMimeType(format.codecs, MimeTypes.VIDEO_H264)) readerFlags = readerFlags or DefaultTsPayloadReaderFactory.FLAG_IGNORE_H264_STREAM
            }
            val extractor = TsExtractor(TsExtractor.MODE_HLS,
                if (parseDuringExtraction) 0 else TsExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA,
                if (parseDuringExtraction) parser else SubtitleParser.Factory.UNSUPPORTED,
                timestampAdjuster, DefaultTsPayloadReaderFactory(readerFlags, CaptionTracks.compatibility), TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES)
            val ts = try { extractor.sniff(sniffingExtractorInput) } catch (_: EOFException) { false }
            finally { sniffingExtractorInput.resetPeekPosition() }
            if (ts) return BundledHlsMediaChunkExtractor(extractor, format, timestampAdjuster)
        }
        return delegate.createExtractor(uri, format, muxedCaptionFormats, timestampAdjuster, responseHeaders, sniffingExtractorInput, playerId)
    }
}
