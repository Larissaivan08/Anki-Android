// SPDX-License-Identifier: GPL-3.0-or-later
// Read-aloud wire format documented by https://github.com/rany2/edge-tts (LGPLv3).

package com.ichi2.anki.reviewer

import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

internal object EdgeSpeechProtocol {
    const val FRANCISCA = "pt-BR-FranciscaNeural"
    const val THALITA = "pt-BR-ThalitaMultilingualNeural"

    fun voice(selection: String?) = if (selection == "thalita") THALITA else FRANCISCA

    fun id() = UUID.randomUUID().toString().replace("-", "")

    private fun timestamp() =
        SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())

    fun configuration(): String =
        "X-Timestamp:${timestamp()}\r\n" +
            "Content-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n" +
            """
            {"context":{"synthesis":{"audio":{
            "metadataoptions":{"sentenceBoundaryEnabled":"false","wordBoundaryEnabled":"false"},
            "outputFormat":"audio-24khz-48kbitrate-mono-mp3"}}}}
            """.trimIndent() + "\r\n"

    fun ssml(
        text: String,
        voice: String,
    ): String {
        require(voice == FRANCISCA || voice == THALITA)
        val safe =
            text
                .filter { it >= ' ' || it == '\n' || it == '\r' || it == '\t' }
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
        return "X-RequestId:${id()}\r\nContent-Type:application/ssml+xml\r\n" +
            "X-Timestamp:${timestamp()}Z\r\nPath:ssml\r\n\r\n" +
            "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='pt-BR'>" +
            "<voice name='$voice'><prosody pitch='+0Hz' rate='+0%' volume='+0%'>$safe</prosody></voice></speak>"
    }

    fun audioFrame(frame: ByteArray): ByteArray? {
        if (frame.size < 2) throw IOException("Missing speech frame header")
        val length = ((frame[0].toInt() and 255) shl 8) or (frame[1].toInt() and 255)
        if (length > frame.size - 2) throw IOException("Invalid speech frame header")
        val header = frame.copyOfRange(2, length + 2).toString(Charsets.UTF_8)
        if (!header.lineSequence().any { it.trim() == "Path:audio" }) return null
        return frame.copyOfRange(length + 2, frame.size)
    }
}
