package io.github.mot1us.spare

import android.graphics.Bitmap
import android.graphics.Color

/**
 * A small QR code encoder for the phone setup link: byte mode, error correction level M, versions 1 to 10
 * (up to 213 bytes). Written for Spare so the app stays small; unit tests decode its output with ZXing.
 */
class QrCode private constructor(val size: Int, private val modules: Array<BooleanArray>) {
    operator fun get(x: Int, y: Int) = modules[y][x]

    /** One pixel per module plus a four-module quiet zone; scale it up without filtering. */
    fun bitmap(): Bitmap {
        val full = size + 8
        val pixels = IntArray(full * full) { Color.WHITE }
        for (y in 0 until size) for (x in 0 until size) if (modules[y][x]) pixels[(y + 4) * full + x + 4] = Color.BLACK
        return Bitmap.createBitmap(pixels, full, full, Bitmap.Config.RGB_565)
    }

    companion object {
        private val ECC_PER_BLOCK = intArrayOf(10, 16, 26, 18, 24, 16, 18, 22, 22, 26)
        private val BLOCKS = intArrayOf(1, 1, 1, 2, 2, 4, 4, 4, 5, 5)
        const val MAX_BYTES = 213

        fun encode(text: String): QrCode {
            val data = text.toByteArray(Charsets.UTF_8)
            val version = (1..10).firstOrNull { v -> 4 + countBits(v) + data.size * 8 <= dataCodewords(v) * 8 }
                ?: throw IllegalArgumentException("Text too long for a QR code")
            val capacity = dataCodewords(version) * 8
            val bits = ArrayList<Boolean>(capacity)
            fun append(value: Int, length: Int) { for (i in length - 1 downTo 0) bits.add((value ushr i) and 1 == 1) }
            append(4, 4); append(data.size, countBits(version)); data.forEach { append(it.toInt() and 0xFF, 8) }
            append(0, minOf(4, capacity - bits.size))
            append(0, (8 - bits.size % 8) % 8)
            var pad = 0xEC
            while (bits.size < capacity) { append(pad, 8); pad = pad xor 0xEC xor 0x11 }
            val codewords = ByteArray(bits.size / 8) { i -> (0 until 8).fold(0) { acc, j -> (acc shl 1) or if (bits[i * 8 + j]) 1 else 0 }.toByte() }
            return Builder(version).build(interleave(version, codewords))
        }

        private fun countBits(version: Int) = if (version < 10) 8 else 16
        private fun rawModules(version: Int): Int {
            var result = (16 * version + 128) * version + 64
            if (version >= 2) {
                val align = version / 7 + 2
                result -= (25 * align - 10) * align - 55
                if (version >= 7) result -= 36
            }
            return result
        }
        private fun dataCodewords(version: Int) = rawModules(version) / 8 - ECC_PER_BLOCK[version - 1] * BLOCKS[version - 1]

        private fun interleave(version: Int, data: ByteArray): ByteArray {
            val blocks = BLOCKS[version - 1]; val eccLength = ECC_PER_BLOCK[version - 1]
            val raw = rawModules(version) / 8
            val shortBlocks = blocks - raw % blocks
            val shortLength = raw / blocks
            val divisor = divisor(eccLength)
            val all = ArrayList<ByteArray>()
            var offset = 0
            for (i in 0 until blocks) {
                val length = shortLength - eccLength + if (i < shortBlocks) 0 else 1
                val block = data.copyOfRange(offset, offset + length); offset += length
                val ecc = remainder(block, divisor)
                // Short blocks get a placeholder byte so every block lines up; it is skipped below.
                all.add((if (i < shortBlocks) block + 0 else block) + ecc)
            }
            val result = ArrayList<Byte>(raw)
            for (i in all[0].indices) for (j in all.indices) if (i != shortLength - eccLength || j >= shortBlocks) result.add(all[j][i])
            return result.toByteArray()
        }

        private fun multiply(x: Int, y: Int): Int {
            var z = 0
            for (i in 7 downTo 0) { z = (z shl 1) xor ((z ushr 7) * 0x11D); z = z xor (((y ushr i) and 1) * x) }
            return z and 0xFF
        }
        private fun divisor(degree: Int): IntArray {
            val result = IntArray(degree); result[degree - 1] = 1
            var root = 1
            repeat(degree) {
                for (j in 0 until degree) { result[j] = multiply(result[j], root); if (j + 1 < degree) result[j] = result[j] xor result[j + 1] }
                root = multiply(root, 0x02)
            }
            return result
        }
        private fun remainder(data: ByteArray, divisor: IntArray): ByteArray {
            val result = IntArray(divisor.size)
            for (b in data) {
                val factor = (b.toInt() and 0xFF) xor result[0]
                System.arraycopy(result, 1, result, 0, result.size - 1); result[result.size - 1] = 0
                for (i in result.indices) result[i] = result[i] xor multiply(divisor[i], factor)
            }
            return ByteArray(result.size) { result[it].toByte() }
        }
    }

    private class Builder(private val version: Int) {
        val size = version * 4 + 17
        val modules = Array(size) { BooleanArray(size) }
        val function = Array(size) { BooleanArray(size) }

        fun set(x: Int, y: Int, dark: Boolean) { modules[y][x] = dark; function[y][x] = true }

        fun build(codewords: ByteArray): QrCode {
            for (i in 0 until size) { set(6, i, i % 2 == 0); set(i, 6, i % 2 == 0) }
            finder(3, 3); finder(size - 4, 3); finder(3, size - 4)
            val align = alignment()
            for (i in align.indices) for (j in align.indices) {
                if ((i == 0 && j == 0) || (i == 0 && j == align.lastIndex) || (i == align.lastIndex && j == 0)) continue
                for (dy in -2..2) for (dx in -2..2) set(align[i] + dx, align[j] + dy, maxOf(Math.abs(dx), Math.abs(dy)) != 1)
            }
            format(0)
            if (version >= 7) {
                var rem = version
                repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
                val bits = version shl 12 or rem
                for (i in 0 until 18) {
                    val bit = (bits ushr i) and 1 == 1
                    val a = size - 11 + i % 3; val b = i / 3
                    set(a, b, bit); set(b, a, bit)
                }
            }
            draw(codewords)
            // Pick the mask with the lowest penalty, as scanners read those most reliably.
            var best = 0; var bestScore = Int.MAX_VALUE
            for (mask in 0 until 8) {
                applyMask(mask); format(mask)
                val score = penalty()
                if (score < bestScore) { best = mask; bestScore = score }
                applyMask(mask)
            }
            applyMask(best); format(best)
            return QrCode(size, modules)
        }

        private fun finder(cx: Int, cy: Int) {
            for (dy in -4..4) for (dx in -4..4) {
                val x = cx + dx; val y = cy + dy
                if (x in 0 until size && y in 0 until size) { val d = maxOf(Math.abs(dx), Math.abs(dy)); set(x, y, d != 2 && d != 4) }
            }
        }
        private fun alignment(): IntArray {
            if (version == 1) return IntArray(0)
            val count = version / 7 + 2
            val step = (version * 4 + count * 2 + 1) / (count * 2 - 2) * 2
            val result = IntArray(count); result[0] = 6
            var position = size - 7
            for (i in count - 1 downTo 1) { result[i] = position; position -= step }
            return result
        }
        private fun format(mask: Int) {
            val data = mask // Level M is 00.
            var rem = data
            repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
            val bits = (data shl 10 or rem) xor 0x5412
            fun bit(i: Int) = (bits ushr i) and 1 == 1
            for (i in 0..5) set(8, i, bit(i))
            set(8, 7, bit(6)); set(8, 8, bit(7)); set(7, 8, bit(8))
            for (i in 9 until 15) set(14 - i, 8, bit(i))
            for (i in 0 until 8) set(size - 1 - i, 8, bit(i))
            for (i in 8 until 15) set(8, size - 15 + i, bit(i))
            set(8, size - 8, true)
        }
        private fun draw(data: ByteArray) {
            var i = 0
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                for (vertical in 0 until size) for (j in 0..1) {
                    val x = right - j
                    val upward = (right + 1) and 2 == 0
                    val y = if (upward) size - 1 - vertical else vertical
                    if (!function[y][x] && i < data.size * 8) { modules[y][x] = (data[i ushr 3].toInt() ushr (7 - (i and 7))) and 1 == 1; i++ }
                }
                right -= 2
            }
        }
        private fun applyMask(mask: Int) {
            for (y in 0 until size) for (x in 0 until size) {
                val invert = when (mask) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> (x / 3 + y / 2) % 2 == 0
                    5 -> x * y % 2 + x * y % 3 == 0
                    6 -> (x * y % 2 + x * y % 3) % 2 == 0
                    else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                }
                if (invert && !function[y][x]) modules[y][x] = !modules[y][x]
            }
        }
        /** Runs, 2×2 blocks and dark balance from the standard's penalty rules (finder-like patterns are left out). */
        private fun penalty(): Int {
            var score = 0
            for (line in 0 until size) {
                var rowRun = 1; var columnRun = 1
                for (k in 1 until size) {
                    if (modules[line][k] == modules[line][k - 1]) { rowRun++; if (rowRun == 5) score += 3 else if (rowRun > 5) score++ } else rowRun = 1
                    if (modules[k][line] == modules[k - 1][line]) { columnRun++; if (columnRun == 5) score += 3 else if (columnRun > 5) score++ } else columnRun = 1
                }
            }
            for (y in 0 until size - 1) for (x in 0 until size - 1) {
                val c = modules[y][x]
                if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) score += 3
            }
            val dark = modules.sumOf { row -> row.count { it } }
            val total = size * size
            score += ((Math.abs(dark * 20 - total * 10) + total - 1) / total - 1).coerceAtLeast(0) * 10
            return score
        }
    }
}
