// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.reviewer

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@OptIn(UnstableApi::class)
class EdgeSpeechProtocolTest {
    @Test
    fun `connection token matches independently captured Edge reference`() {
        assertEquals(
            "492D7694A183A45E-56E821F95D4579C5B7C7E33DC7303FF379EC36D510213555BBBB9EB17EF15876",
            EdgeSpeechToken.generate(1791888000.0),
        )
        assertEquals(
            "6D26B5F5B70269AD-280E82E1BBE24E4E8866E3502EB6277B9436F61ABDC1C224518A038BBD7BBDAD",
            EdgeSpeechToken.generate(1791888300.0),
        )
        assertEquals(
            "2CD23A2CA0B2595A-F3C846C370E05F9CC83ACC5F995C5F5D87A4D0F14CE8936A0F902E64099E38A2",
            EdgeSpeechToken.generate(1700000000.0),
        )
    }

    @Test
    fun `token reuses five minute window and changes at boundary`() {
        val token = EdgeSpeechToken.generate(1791888000.0)
        assertEquals(token, EdgeSpeechToken.generate(1791888299.0))
        assertNotEquals(token, EdgeSpeechToken.generate(1791888300.0))
    }

    @Test
    fun `old voice selections migrate to female Brazilian default`() {
        for (old in listOf(null, "dora", "alex", "santa", "en-US-AriaNeural")) {
            assertEquals(EdgeSpeechProtocol.FRANCISCA, EdgeSpeechProtocol.voice(old))
        }
        assertEquals(EdgeSpeechProtocol.THALITA, EdgeSpeechProtocol.voice("thalita"))
    }

    @Test
    fun `card text is XML escaped and keeps Brazilian Unicode`() {
        val request = EdgeSpeechProtocol.ssml("Ação < 5 & pressão > 10\u0000", EdgeSpeechProtocol.FRANCISCA)
        assertTrue(request.contains("Ação &lt; 5 &amp; pressão &gt; 10"))
        assertFalse(request.contains('\u0000'))
        assertTrue(request.contains("xml:lang='pt-BR'"))
        assertTrue(request.contains("Path:ssml\r\n\r\n"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `only whitelisted Brazilian female voices are accepted`() {
        EdgeSpeechProtocol.ssml("Texto", "en-US-AriaNeural")
    }

    @Test
    fun `binary frames keep only MP3 payload`() {
        val header = "Path:audio\r\nContent-Type:audio/mpeg\r\n".toByteArray()
        val audio = byteArrayOf(0x49, 0x44, 0x33, 7, 8)
        val frame =
            ByteBuffer
                .allocate(2 + header.size + audio.size)
                .putShort(header.size.toShort())
                .put(header)
                .put(audio)
                .array()
        assertArrayEquals(audio, EdgeSpeechProtocol.audioFrame(frame))
    }

    @Test(expected = IOException::class)
    fun `malformed audio frame is rejected`() {
        EdgeSpeechProtocol.audioFrame(byteArrayOf(0, 40, 0))
    }

    @Test
    fun `stream yields first audio before complete synthesis and EOF stays terminal`() {
        val pipe = SpeechAudioPipe()
        pipe.open(DataSpec.Builder().setUri(Uri.parse("meduerj-speech://test")).build())
        val out = ByteArray(8)
        pipe.append(byteArrayOf(1, 2, 3))
        assertEquals(3, pipe.read(out, 0, out.size))
        assertArrayEquals(byteArrayOf(1, 2, 3), out.copyOfRange(0, 3))
        pipe.append(byteArrayOf(4, 5))
        assertEquals(2, pipe.read(out, 0, out.size))
        pipe.finish()
        assertEquals(C.RESULT_END_OF_INPUT, pipe.read(out, 0, out.size))
        assertEquals(C.RESULT_END_OF_INPUT, pipe.read(out, 0, out.size))
        pipe.close()
    }

    @Test
    fun `stop unblocks player waiting for network audio`() {
        val pipe = SpeechAudioPipe()
        pipe.open(DataSpec.Builder().setUri(Uri.parse("meduerj-speech://test")).build())
        val began = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result =
                executor.submit<Boolean> {
                    began.countDown()
                    try {
                        pipe.read(ByteArray(8), 0, 8)
                        false
                    } catch (_: IOException) {
                        true
                    }
                }
            assertTrue(began.await(1, TimeUnit.SECONDS))
            pipe.cancel()
            assertTrue(result.get(1, TimeUnit.SECONDS))
        } finally {
            pipe.close()
            executor.shutdownNow()
        }
    }
}
