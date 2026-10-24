// SPDX-License-Identifier: LGPL-3.0-or-later
// Protocol compatibility adapted from rany2/edge-tts src/edge_tts/drm.py.
// https://github.com/rany2/edge-tts/pull/492

package com.ichi2.anki.reviewer

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

internal object EdgeSpeechToken {
    const val CLIENT = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"

    private fun words(value: String) = value.split(' ').map { it.toLong(16).toInt() }.toIntArray()

    private val initial = words("6A09E667 BB67AE85 3C6EF372 A54FF53A 510E527F 9B05688C 1F83D9AB 5BE0CD19")
    private val seed = words("FD9FBEA2 91243CE7 BE499094 CD2E1E0A 6C006F49 04BD35AB FEB0A7E4 03EFFBD2")
    private val schedule =
        words(
            "EB24BF9E 1558691F A0C09EE2 0CFA32BA A622EE8C D1DD9491 C0DD25A0 " +
                "F9E70AB6 F0C31573 7058CE4E 7188E606 00E5AB33 D258B060 72766564",
        )
    private val rounds =
        words(
            "428A2F98 71374491 B5C0FBCF E9B5DBA5 3956C25B 59F111F1 923F82A4 AB1C5ED5 " +
                "D807AA98 12835B01 243185BE 550C7DC3 72BE5D74 80DEB1FE 9BDC06A7 C19BF174 " +
                "E49B69C1 EFBE4786 0FC19DC6 240CA1CC 2DE92C6F 4A7484AA 5CB0A9DC 76F988DA " +
                "983E5152 A831C66D B00327C8 BF597FC7 C6E00BF3 D5A79147 06CA6351 14292967 " +
                "27B70A85 2E1B2138 4D2C6DFC 53380D13 650A7354 766A0ABB 81C2C92E 92722C85 " +
                "A2BFE8A1 A81A664B C24B8B70 C76C51A3 D192E819 D6990624 F40E3585 106AA070 " +
                "19A4C116 1E376C08 2748774C 34B0BCB5 391C0CB3 4ED8AA4A 5B9CCA4F 682E6FF3 " +
                "748F82EE 78A5636F 84C87814 8CC70208 90BEFFFA A4506CEB BEF9A3F7 C67178F2",
        )
    private val coefficients =
        words(
            "E82E91B1 44DE24C6 3BEB65BC C89C5933 8D1EC211 314EF5D5 E92491B1 " +
                "F0A5B9D5 06064CF3 DA79FA60 BED20560 89F9C9F8 35FA38C6 CB49E3C6",
        )
    private val offsets =
        words(
            "B32700D0 88DFF51D 29514038 B328CBDF D405B4D5 26E55F3C 607801C6 " +
                "82B2C1FB FA51A3DA 207CC464 3C3C74F3 D2D14B61 BB04A2C1 C5626167",
        )

    private fun number(hex: String) = hex.toLong(16).toInt()

    private fun sigma0(v: Int) = (v ushr 7) xor (v shl 25) xor (v ushr 18) xor (v shl 14) xor (v ushr 3)

    private fun sigma1(v: Int) = (v ushr 17) xor (v shl 15) xor (v ushr 19) xor (v shl 13) xor (v ushr 10)

    private fun expand(
        w: IntArray,
        from: Int,
    ) {
        for (i in from until 64) w[i] = sigma1(w[i - 2]) + w[i - 7] + sigma0(w[i - 15]) + w[i - 16]
    }

    private fun compress(
        state: IntArray,
        w: IntArray,
        from: Int,
    ): IntArray {
        var a = state[0]
        var b = state[1]
        var c = state[2]
        var d = state[3]
        var e = state[4]
        var f = state[5]
        var g = state[6]
        var h = state[7]
        for (i in from until 64) {
            val se = Integer.rotateRight(e, 6) xor Integer.rotateRight(e, 11) xor Integer.rotateRight(e, 25)
            val sa = Integer.rotateRight(a, 2) xor Integer.rotateRight(a, 13) xor Integer.rotateRight(a, 22)
            val k = if (from == 14 && i == 14) number("C9DC06A7") else rounds[i]
            val t1 = h + se + (g xor (e and (f xor g))) + k + w[i]
            val t2 = sa + ((a and b) or (c and (a or b)))
            h = g
            g = f
            f = e
            e = d + t1
            d = c
            c = b
            b = a
            a = t1 + t2
        }
        return intArrayOf(a, b, c, d, e, f, g, h)
    }

    private fun digest(data: ByteArray): ByteArray {
        val w = IntArray(64)
        w[14] = ((data[0].toInt() and 255) shl 16) or ((data[1].toInt() and 255) shl 8) or (data[2].toInt() and 255)
        w[15] = ByteBuffer.wrap(data, 3, 4).int
        for (i in 16 until 30) {
            var value = schedule[i - 16]
            if (i - 2 >= 14) value += sigma1(w[i - 2]) xor (if (i == 16) number("000B92C0") else 0)
            if (i - 7 >= 14) value += w[i - 7]
            if (i - 15 >= 14) value += sigma0(w[i - 15]) xor (if (i == 29) number("059C0B80") else 0)
            w[i] = value
        }
        w[30] = sigma1(w[28]) + w[23] + sigma0(w[15]) + w[14] + number("C9DC06A7") - rounds[14]
        expand(w, 31)
        val first = compress(seed, w, 14)
        val state = IntArray(8) { initial[it] + first[it] }
        val length = 57 + data.size
        val zeros = ((55 - length) % 64 + 64) % 64
        val padded =
            ByteBuffer
                .allocate(data.size - 7 + 1 + zeros + 8)
                .put(data, 7, data.size - 7)
                .put(0x80.toByte())
                .put(ByteArray(zeros))
                .putLong(length.toLong() * 8)
                .array()
        for (offset in padded.indices step 64) {
            val buffer = ByteBuffer.wrap(padded, offset, 64)
            for (i in 0 until 16) w[i] = buffer.int
            expand(w, 16)
            val result = compress(state, w, 0)
            for (i in 0 until 8) state[i] += result[i]
        }
        return ByteBuffer.allocate(32).also { out -> state.forEach { out.putInt(it) } }.array()
    }

    private fun checksum(
        data: ByteArray,
        hash: ByteArray,
    ): ByteArray {
        val keys = ByteBuffer.wrap(hash).order(ByteOrder.LITTLE_ENDIAN)
        val ka = keys.int or 1
        val kb = keys.int or 1
        val factors = intArrayOf(ka * number("82893933"), kb * number("82893933"))
        val terms = coefficients.mapIndexed { i, c -> c * factors[i % 2] }.toMutableList()
        val shifts = offsets.toMutableList()
        val count = ((data.size + 1 + 3) / 4).let { if (it % 2 == 0) it else it + 1 }
        val buffer =
            ByteBuffer
                .allocate(count * 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put(0)
                .put(data)
        buffer.rewind()
        for (i in 0 until count) {
            val value = buffer.int
            var term = value * (if (i % 2 == 0) ka else kb)
            var shift = Integer.rotateLeft(value, 16) * number(if (i % 2 == 0) "EF0569FB" else "C31713DB")
            if (i == 0) {
                term += number("F2F90B1A") * factors[0]
                shift += number("0B1A0000")
            }
            terms += term
            shifts += shift
        }
        var swapAcc = 0
        var swapLast = 0
        var state = 0
        var acc = 0
        for (i in terms.indices step 2) {
            val x = terms[i]
            val y = terms[i + 1]
            var q = Integer.rotateLeft(shifts[i] + x, 16) * number("76F50F34")
            swapAcc += Integer.rotateLeft(q, 16) * number("C3EFEA97") + q
            q = Integer.rotateLeft(shifts[i + 1] + y, 16) * number("1881CE3C")
            swapLast = Integer.rotateLeft(q, 16) * number("35BD1EC9") + q
            swapAcc += swapLast
            state = state * ka + x
            for (factor in listOf("CF98B111", "87085B9F", "12CEB96D", "257E1D83")) state = Integer.rotateLeft(state, 16) * number(factor)
            acc += state
            var t = state * kb + y
            t = Integer.rotateLeft(t, 16) * number("A27416F5")
            t = Integer.rotateLeft(t, 16) * number("D38396FF")
            state = Integer.rotateLeft(Integer.rotateLeft(t, 16) * number("7C932B89"), 16) * number("BFA49F69")
            acc += state
        }
        return ByteBuffer
            .allocate(8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(swapLast xor state)
            .putInt(swapAcc xor acc)
            .array()
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02X".format(it.toInt() and 255) }

    fun generate(unixSeconds: Double = System.currentTimeMillis() / 1000.0): String {
        val seconds = unixSeconds + 11644473600.0
        val ticks = (seconds - seconds % 300) * 10000000.0
        val data = (String.format(Locale.US, "%.0f", ticks) + CLIENT).toByteArray(Charsets.US_ASCII)
        val hash = digest(data)
        return hex(checksum(data, hash)) + "-" + hex(hash)
    }
}
