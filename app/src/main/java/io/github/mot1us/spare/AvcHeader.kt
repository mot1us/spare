package io.github.mot1us.spare

import androidx.media3.common.util.UnstableApi
import androidx.media3.container.NalUnitUtil
import androidx.media3.container.ParsableNalUnitBitArray

/** Reads the stream's SPS, including timing that Media3's TS Format does not expose. */
@UnstableApi
object AvcHeader {
    data class Info(val fps: Float = 0f, val interlaced: Boolean = false, val known: Boolean = false)
    fun read(buffers: List<ByteArray>): Info = buffers.asSequence().filter { it.size in 4..65536 }
        .flatMap { units(it).asSequence() }.mapNotNull { bytes ->
            if (bytes[0].toInt() and 31 != 7) null else runCatching { sps(bytes) }.getOrNull()
        }.firstOrNull() ?: Info()

    private fun units(bytes: ByteArray): List<ByteArray> {
        val starts = mutableListOf<Pair<Int, Int>>()
        var at = 0
        while (at + 3 < bytes.size) {
            val prefix = when {
                bytes[at] == 0.toByte() && bytes[at + 1] == 0.toByte() && bytes[at + 2] == 1.toByte() -> 3
                at + 4 < bytes.size && bytes[at] == 0.toByte() && bytes[at + 1] == 0.toByte() && bytes[at + 2] == 0.toByte() && bytes[at + 3] == 1.toByte() -> 4
                else -> 0
            }
            if (prefix > 0) { starts.add(at to prefix); at += prefix } else at++
        }
        if (starts.isNotEmpty()) return starts.mapIndexed { index, (offset, prefix) -> bytes.copyOfRange(offset + prefix, starts.getOrNull(index + 1)?.first ?: bytes.size) }.filter { it.isNotEmpty() }
        if (bytes[0] == 1.toByte() && bytes.size >= 7) { // AVCDecoderConfigurationRecord
            val result = mutableListOf<ByteArray>(); var offset = 6
            repeat(bytes[5].toInt() and 31) {
                if (offset + 2 > bytes.size) return emptyList()
                val length = ((bytes[offset].toInt() and 255) shl 8) or (bytes[offset + 1].toInt() and 255); offset += 2
                if (length == 0 || offset + length > bytes.size) return emptyList()
                result.add(bytes.copyOfRange(offset, offset + length)); offset += length
            }
            return result
        }
        return listOf(bytes)
    }

    private fun sps(bytes: ByteArray): Info {
        val parsed = NalUnitUtil.parseSpsNalUnit(bytes, 0, bytes.size)
        val basic = Info(interlaced = !parsed.frameMbsOnlyFlag, known = true)
        return runCatching {
            val b = ParsableNalUnitBitArray(bytes, 1, bytes.size)
            val profile = b.readBits(8); b.skipBits(16); b.readUnsignedExpGolombCodedInt()
            if (profile in setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)) {
                val chroma = b.readUnsignedExpGolombCodedInt(); require(chroma in 0..3)
                if (chroma == 3) b.skipBit()
                b.readUnsignedExpGolombCodedInt(); b.readUnsignedExpGolombCodedInt(); b.skipBit()
                if (b.readBit()) repeat(if (chroma == 3) 12 else 8) { index ->
                    if (b.readBit()) {
                        var last = 8; var next = 8
                        repeat(if (index < 6) 16 else 64) {
                            if (next != 0) next = (last + b.readSignedExpGolombCodedInt() + 256) % 256
                            if (next != 0) last = next
                        }
                    }
                }
            }
            b.readUnsignedExpGolombCodedInt()
            when (b.readUnsignedExpGolombCodedInt()) {
                0 -> b.readUnsignedExpGolombCodedInt()
                1 -> { b.skipBit(); b.readSignedExpGolombCodedInt(); b.readSignedExpGolombCodedInt(); val count = b.readUnsignedExpGolombCodedInt(); require(count <= 256); repeat(count) { b.readSignedExpGolombCodedInt() } }
            }
            b.readUnsignedExpGolombCodedInt(); b.skipBit(); b.readUnsignedExpGolombCodedInt(); b.readUnsignedExpGolombCodedInt()
            if (!b.readBit()) b.skipBit()
            b.skipBit()
            if (b.readBit()) repeat(4) { b.readUnsignedExpGolombCodedInt() }
            if (!b.readBit()) return basic
            if (b.readBit() && b.readBits(8) == 255) b.skipBits(32)
            if (b.readBit()) b.skipBit()
            if (b.readBit()) { b.skipBits(4); if (b.readBit()) b.skipBits(24) }
            if (b.readBit()) { b.readUnsignedExpGolombCodedInt(); b.readUnsignedExpGolombCodedInt() }
            if (!b.readBit()) return basic
            fun unsigned32() = (b.readBits(16).toLong() shl 16) or b.readBits(16).toLong()
            val ticks = unsigned32(); val scale = unsigned32(); val fixed = b.readBit()
            basic.copy(fps = if (fixed && ticks > 0) VideoRate.normalize((scale.toDouble() / (2 * ticks)).toFloat()) else 0f)
        }.getOrDefault(basic)
    }
}
