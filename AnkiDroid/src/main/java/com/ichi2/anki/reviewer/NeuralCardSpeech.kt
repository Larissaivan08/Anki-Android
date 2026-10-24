// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.reviewer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.ichi2.anki.AnkiDroidApp
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import timber.log.Timber
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

interface CardSpeech : AutoCloseable {
    fun speak(
        text: String,
        onDone: () -> Unit,
        onError: () -> Unit,
    )

    fun stop()

    fun reset()
}

/** Kokoro inference stays on one worker. Cancellation never frees a model still in native code. */
internal class NeuralCardSpeech(
    private val context: Context,
) : CardSpeech {
    private val worker = inferenceWorker
    private val version = AtomicLong()
    private val playbackLock = Any()
    private var track: AudioTrack? = null
    private var model: OfflineTts? = null // worker only

    override fun speak(
        text: String,
        onDone: () -> Unit,
        onError: () -> Unit,
    ) {
        stop()
        val request = version.get()
        worker.execute {
            try {
                if (version.get() != request) return@execute
                val tts = model ?: createModel().also { model = it }
                for (chunk in AutomaticCardReadAloud.speechChunks(text, 400)) {
                    if (version.get() != request) return@execute
                    val voice = AnkiDroidApp.sharedPrefs().getString("meduerjBrazilianVoice", "dora")
                    val speaker =
                        when (voice) {
                            "alex" -> 1
                            "santa" -> 2
                            else -> 0
                        }
                    val audio = tts.generate(chunk, sid = speaker, speed = 1.0f)
                    if (version.get() != request) return@execute
                    check(audio.samples.isNotEmpty() && audio.samples.all { it.isFinite() })
                    val samples = ShortArray(audio.samples.size) { (audio.samples[it].coerceIn(-1f, 1f) * 32767).toInt().toShort() }
                    val player =
                        AudioTrack
                            .Builder()
                            .setAudioAttributes(
                                AudioAttributes
                                    .Builder()
                                    .setUsage(
                                        AudioAttributes.USAGE_MEDIA,
                                    ).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .build(),
                            ).setAudioFormat(
                                AudioFormat
                                    .Builder()
                                    .setSampleRate(
                                        audio.sampleRate,
                                    ).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .build(),
                            ).setBufferSizeInBytes(
                                maxOf(
                                    AudioTrack.getMinBufferSize(
                                        audio.sampleRate,
                                        AudioFormat.CHANNEL_OUT_MONO,
                                        AudioFormat.ENCODING_PCM_16BIT,
                                    ),
                                    8192,
                                ),
                            ).setTransferMode(AudioTrack.MODE_STREAM)
                            .build()
                    try {
                        synchronized(playbackLock) {
                            if (version.get() == request) {
                                track = player
                                player.play()
                            }
                        }
                        var written = 0
                        while (written < samples.size && version.get() == request) {
                            val count = player.write(samples, written, minOf(4096, samples.size - written), AudioTrack.WRITE_NON_BLOCKING)
                            check(count >= 0)
                            written += count
                            if (count == 0) Thread.sleep(10)
                        }
                        while (version.get() == request && player.playbackHeadPosition < written) Thread.sleep(10)
                    } finally {
                        synchronized(playbackLock) {
                            if (track === player) track = null
                            player.release()
                        }
                    }
                }
                if (version.get() == request) onDone()
            } catch (error: Exception) {
                Timber.e(error, "Brazilian card narration failed")
                if (version.get() == request) onError()
            } catch (error: LinkageError) {
                Timber.e(error, "Brazilian speech native library unavailable")
                if (version.get() == request) onError()
            }
        }
    }

    private fun createModel(): OfflineTts {
        // eSpeak is the phonemizer only; the spoken audio is generated by the neural model.
        val data = File(context.filesDir, "kokoro-ptbr-v2/espeak-ng-data")
        val marker = File(data.parentFile, "ready")
        if (!marker.exists()) {
            copyAssetDirectory("neural/espeak-ng-data", data)
            marker.writeText("1")
            File(context.filesDir, "kokoro-1.0").deleteRecursively()
        }
        return OfflineTts(
            assetManager = context.assets,
            config =
                OfflineTtsConfig(
                    model =
                        OfflineTtsModelConfig(
                            kokoro =
                                OfflineTtsKokoroModelConfig(
                                    model = "neural/model.onnx",
                                    voices = "neural/voices.bin",
                                    tokens = "neural/tokens.txt",
                                    dataDir = data.absolutePath,
                                    lang = "pt-br",
                                ),
                            numThreads = 2,
                        ),
                ),
        )
    }

    private fun copyAssetDirectory(
        source: String,
        destination: File,
    ) {
        val children = context.assets.list(source).orEmpty()
        if (children.isEmpty()) {
            destination.parentFile?.mkdirs()
            context.assets.open(source).use { input -> destination.outputStream().use { input.copyTo(it) } }
        } else {
            destination.mkdirs()
            children.forEach { copyAssetDirectory("$source/$it", File(destination, it)) }
        }
    }

    override fun stop() {
        version.incrementAndGet()
        synchronized(playbackLock) {
            track?.pause()
            track?.flush()
        }
    }

    override fun reset() {
        stop()
        worker.execute {
            model?.free()
            model = null
        }
    }

    override fun close() {
        reset()
    }

    companion object {
        // eSpeak has process-wide state: previews and reviewers must not enter native code concurrently.
        private val inferenceWorker = Executors.newSingleThreadExecutor()
    }
}
