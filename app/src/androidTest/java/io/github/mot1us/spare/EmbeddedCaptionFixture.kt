package io.github.mot1us.spare

import java.io.ByteArrayOutputStream

/** Adds synthetic ATSC user-data captions to the test-only MPEG-2 fixture, without PMT caption descriptors. */
object EmbeddedCaptionFixture {
    fun add(input: ByteArray, digital: Boolean): ByteArray {
        fun packets() = sequence { for (offset in input.indices step 188) yield(input.copyOfRange(offset, offset + 188)) }
        fun pid(p: ByteArray) = ((p[1].toInt() and 31) shl 8) or (p[2].toInt() and 255)
        fun payload(p: ByteArray): Int {
            val mode = (p[3].toInt() ushr 4) and 3
            return if (mode and 1 == 0) 188 else if (mode and 2 == 0) 4 else 5 + (p[4].toInt() and 255)
        }
        val videoPid = packets().first { p -> val at = payload(p); at + 4 < 188 && p[1].toInt() and 64 != 0 && p[at] == 0.toByte() && p[at+1] == 0.toByte() && p[at+2] == 1.toByte() && p[at+3].toInt() and 240 == 224 }.let(::pid)
        val output = ByteArrayOutputStream(); var pes = ByteArrayOutputStream(); var continuity = 0; var sequence = 0; var started = false
        fun flush() {
            val raw = pes.toByteArray(); if (raw.size < 9) return
            val headerEnd = 9 + (raw[8].toInt() and 255)
            val data = if (sequence++ < 2) byteArrayOf() else captions(digital, sequence and 3)
            val modified = raw.copyOfRange(0, headerEnd) + data + raw.copyOfRange(headerEnd, raw.size)
            val length = ((raw[4].toInt() and 255) shl 8) or (raw[5].toInt() and 255)
            if (length > 0) { val next = length + data.size; modified[4] = (next ushr 8).toByte(); modified[5] = next.toByte() }
            var at = 0
            while (at < modified.size) {
                val take = minOf(184, modified.size - at); val packet = ByteArray(188) { 0xFF.toByte() }
                packet[0] = 0x47; packet[1] = ((videoPid ushr 8) or if (at == 0) 64 else 0).toByte(); packet[2] = videoPid.toByte()
                packet[3] = ((if (take == 184) 0x10 else 0x30) or (continuity++ and 15)).toByte()
                val start = if (take == 184) 4 else { val lengthOfAdaptation = 183 - take; packet[4] = lengthOfAdaptation.toByte(); if (lengthOfAdaptation > 0) packet[5] = 0; 5 + lengthOfAdaptation }
                modified.copyInto(packet, start, at, at + take); output.write(packet); at += take
            }
            pes = ByteArrayOutputStream()
        }
        for (packet in packets()) {
            if (pid(packet) != videoPid) { output.write(packet); continue }
            val at = payload(packet); if (at >= 188) continue
            if (packet[1].toInt() and 64 != 0) { if (started) flush(); started = true }
            if (!started) continue
            pes.write(packet, at, 188 - at)
        }
        flush(); return output.toByteArray()
    }
    private fun captions(digital: Boolean, sequence: Int): ByteArray {
        val cc = ByteArrayOutputStream()
        if (!digital) {
            fun parity(value: Int) = if (Integer.bitCount(value) % 2 == 0) value or 128 else value
            val pairs = listOf(0x14 to 0x20, 0x14 to 0x2E, 0x11 to 0x40) + "Spare captions".chunked(2).map { it[0].code to it.getOrElse(1) { ' ' }.code } + listOf(0x14 to 0x2F)
            pairs.forEach { (a, b) -> cc.write(0xFC); cc.write(parity(a)); cc.write(parity(b)) }
        } else {
            // Define visible window 0, write a service-1 caption, then display the window.
            val commands = byteArrayOf(0x98.toByte(), 0x38, 0, 0, 0, 31, 0) + "Spare captions".toByteArray() + byteArrayOf(0x89.toByte(), 1)
            var packetData = byteArrayOf((0x20 or commands.size).toByte()) + commands
            if (packetData.size % 2 == 0) packetData += 0.toByte()
            cc.write(0xFF); cc.write((sequence shl 6) or ((packetData.size + 1) / 2)); cc.write(packetData[0].toInt())
            for (i in 1 until packetData.size step 2) { cc.write(0xFE); cc.write(packetData[i].toInt()); cc.write(packetData[i+1].toInt()) }
        }
        val bytes = cc.toByteArray()
        return byteArrayOf(0, 0, 1, 0xB2.toByte()) + "GA94".toByteArray() + byteArrayOf(3, (0x40 or (bytes.size / 3)).toByte(), 0xFF.toByte()) + bytes + byteArrayOf(0xFF.toByte())
    }
}
