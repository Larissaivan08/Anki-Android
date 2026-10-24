// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.reviewer

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue

/** MP3 bytes become readable immediately, before synthesis of the whole card has completed. */
@OptIn(UnstableApi::class)
internal class SpeechAudioPipe : BaseDataSource(true) {
    private val queue = LinkedBlockingQueue<ByteArray>()
    private var current = ByteArray(0)
    private var offset = 0
    private var opened = false
    private var ended = false

    @Volatile private var failure: IOException? = null

    @Volatile private var cancelled = false

    fun append(bytes: ByteArray) {
        if (bytes.isNotEmpty() && !cancelled) queue.offer(bytes)
    }

    fun finish() {
        queue.offer(ByteArray(0))
    }

    fun fail(error: IOException) {
        failure = error
        queue.offer(ByteArray(0))
    }

    fun cancel() {
        cancelled = true
        queue.offer(ByteArray(0))
    }

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        opened = true
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(
        buffer: ByteArray,
        bufferOffset: Int,
        length: Int,
    ): Int {
        if (length == 0) return 0
        if (cancelled) throw IOException("Speech cancelled")
        if (ended) return C.RESULT_END_OF_INPUT
        while (offset == current.size) {
            current =
                try {
                    queue.take()
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("Speech interrupted", e)
                }
            offset = 0
            if (cancelled) throw IOException("Speech cancelled")
            if (current.isEmpty()) {
                ended = true
                failure?.let { throw it }
                return C.RESULT_END_OF_INPUT
            }
        }
        val count = minOf(length, current.size - offset)
        current.copyInto(buffer, bufferOffset, offset, offset + count)
        offset += count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri = Uri.parse("meduerj-speech:///audio.mp3")

    override fun close() {
        cancel()
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}
