// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.reviewer

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import com.ichi2.anki.AnkiDroidApp
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Online Brazilian female voices. All state and player access are confined to the main thread. */
@OptIn(UnstableApi::class)
internal class EdgeCardSpeech(
    private val context: Context,
) : CardSpeech {
    private class SpeechRequest(
        val text: String,
        val voice: String,
    ) {
        val key = "$voice\u0000$text"
        val pipe = SpeechAudioPipe()
        val bytes = ByteArrayOutputStream()
        var started = false
        var complete = false
    }

    private val main = Handler(Looper.getMainLooper())
    private var socket: WebSocket? = null
    private var ready = false
    private var closed = false
    private var generation = 0L
    private var requests = emptyList<SpeechRequest>()
    private var receiving: SpeechRequest? = null
    private var player: ExoPlayer? = null
    private var onCompleted: (() -> Unit)? = null
    private var onFailed: (() -> Unit)? = null

    override fun warmUp() {
        if (closed || ready || socket != null) return
        val url =
            "https://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1" +
                "?TrustedClientToken=${EdgeSpeechToken.CLIENT}&ConnectionId=${EdgeSpeechProtocol.id()}" +
                "&Sec-MS-GEC=${EdgeSpeechToken.generate()}&Sec-MS-GEC-Version=2-143.0.3650.75"
        val request =
            Request
                .Builder()
                .url(url)
                .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0",
                ).header("Cookie", "muid=${EdgeSpeechProtocol.id().uppercase()};")
                .build()
        socket =
            client.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response,
                    ) {
                        main.post {
                            if (socket !== webSocket || closed) {
                                webSocket.cancel()
                                return@post
                            }
                            ready = true
                            webSocket.send(EdgeSpeechProtocol.configuration())
                            pump()
                        }
                    }

                    override fun onMessage(
                        webSocket: WebSocket,
                        bytes: ByteString,
                    ) {
                        val audio =
                            try {
                                EdgeSpeechProtocol.audioFrame(bytes.toByteArray())
                            } catch (error: IOException) {
                                main.post { socketFailed(webSocket, error) }
                                return
                            } ?: return
                        if (audio.isEmpty()) return
                        main.post {
                            if (socket !== webSocket) return@post
                            receiving?.let { current ->
                                current.bytes.write(audio)
                                current.pipe.append(audio)
                            }
                        }
                    }

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        if (!text.lineSequence().any { it.trim() == "Path:turn.end" }) return
                        main.post {
                            if (socket !== webSocket) return@post
                            receiving?.let { current ->
                                if (current.bytes.size() == 0) {
                                    socketFailed(webSocket, IOException("Speech service returned no audio"))
                                    return@post
                                }
                                current.complete = true
                                val audio = current.bytes.toByteArray()
                                cache.putAudio(current.key, audio)
                                current.bytes.reset()
                                current.pipe.finish()
                            }
                            receiving = null
                            pump()
                        }
                    }

                    override fun onClosing(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String,
                    ) {
                        webSocket.close(code, null)
                    }

                    override fun onClosed(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String,
                    ) {
                        main.post { socketFailed(webSocket, IOException("Speech connection closed")) }
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?,
                    ) {
                        main.post { socketFailed(webSocket, IOException("Speech service connection failed", t)) }
                    }
                },
            )
    }

    override fun speak(
        text: String,
        onDone: () -> Unit,
        onError: () -> Unit,
    ) {
        if (closed) return
        stop()
        val epoch = generation
        val voice = EdgeSpeechProtocol.voice(AnkiDroidApp.sharedPrefs().getString("meduerjBrazilianVoice", "francisca"))
        val began = SystemClock.elapsedRealtime()
        onCompleted = onDone
        onFailed = onError
        requests = AutomaticCardReadAloud.speechChunks(text, 500).map { SpeechRequest(it, voice) }
        var allCached = true
        for (entry in requests) {
            val audio = cache.audio(entry.key)
            if (audio == null) {
                allCached = false
                continue
            }
            entry.started = true
            entry.complete = true
            entry.pipe.append(audio)
            entry.pipe.finish()
        }
        try {
            val audioPlayer =
                ExoPlayer
                    .Builder(context)
                    .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(250, 1000, 100, 100).build())
                    .build()
            player = audioPlayer
            audioPlayer.setAudioAttributes(
                AudioAttributes
                    .Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                true,
            )
            audioPlayer.addListener(
                object : Player.Listener {
                    private var logged = false

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying && !logged && generation == epoch) {
                            logged = true
                            Timber.i(
                                "Card speech started in %d ms, voice=%s, cached=%s",
                                SystemClock.elapsedRealtime() - began,
                                voice,
                                allCached,
                            )
                        }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (generation == epoch && playbackState == Player.STATE_ENDED) {
                            val done = onCompleted
                            onCompleted = null
                            onFailed = null
                            done?.invoke()
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        if (generation == epoch) fail(error)
                    }
                },
            )
            val sources =
                requests.map { entry ->
                    ProgressiveMediaSource
                        .Factory(DataSource.Factory { entry.pipe })
                        .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(0))
                        .createMediaSource(
                            MediaItem
                                .Builder()
                                .setUri("meduerj-speech:///audio.mp3")
                                .setMimeType(MimeTypes.AUDIO_MPEG)
                                .build(),
                        )
                }
            audioPlayer.setMediaSources(sources)
            audioPlayer.prepare()
            audioPlayer.play()
            if (!allCached) {
                warmUp()
                pump()
            }
        } catch (error: Exception) {
            fail(error)
        }
    }

    private fun pump() {
        if (!ready || receiving != null || closed) return
        val next = requests.firstOrNull { !it.started } ?: return
        next.started = true
        receiving = next
        if (socket?.send(EdgeSpeechProtocol.ssml(next.text, next.voice)) != true) {
            fail(IOException("Could not send speech request"))
            return
        }
        val epoch = generation
        main.postDelayed({
            if (generation == epoch && receiving === next && next.bytes.size() == 0) {
                fail(IOException("Speech service did not return audio in time"))
            }
        }, 12000)
    }

    private fun socketFailed(
        webSocket: WebSocket,
        error: IOException,
    ) {
        if (socket !== webSocket) return
        socket = null
        ready = false
        if (receiving != null || requests.any { !it.started }) fail(error)
    }

    private fun fail(error: Throwable) {
        Timber.w(error, "Online Brazilian card speech failed")
        val callback = onFailed
        stop()
        callback?.invoke()
    }

    override fun stop() {
        generation++
        onCompleted = null
        onFailed = null
        requests.forEach { it.pipe.cancel() }
        requests = emptyList()
        if (receiving != null) {
            receiving = null
            socket?.cancel()
            socket = null
            ready = false
        }
        player?.release()
        player = null
    }

    override fun reset() {
        stop()
        socket?.cancel()
        socket = null
        ready = false
    }

    override fun close() {
        closed = true
        reset()
    }

    private class AudioCache : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
        private var bytes = 0

        fun audio(key: String): ByteArray? = synchronized(this) { get(key) }

        fun putAudio(
            key: String,
            audio: ByteArray,
        ) = synchronized(this) {
            if (audio.size > 8 * 1024 * 1024) return@synchronized
            bytes -= get(key)?.size ?: 0
            put(key, audio)
            bytes += audio.size
            val oldest = entries.iterator()
            while (bytes > 8 * 1024 * 1024 && oldest.hasNext()) {
                bytes -= oldest.next().value.size
                oldest.remove()
            }
        }
    }

    companion object {
        private val cache = AudioCache()
        private val client =
            OkHttpClient
                .Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .pingInterval(15, TimeUnit.SECONDS)
                .build()
    }
}
