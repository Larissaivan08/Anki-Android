// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.reviewer

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.edit
import com.ichi2.anki.R
import org.json.JSONTokener
import java.util.Locale

/** Owns speech for one reviewer, without changing the collection or card templates. Main-thread only. */
class AutomaticCardReadAloud(
    context: Context,
    private val preferences: SharedPreferences,
    private val onError: (Int) -> Unit,
    private val onCompleted: () -> Unit = {},
    private val ttsFactory: ((TextToSpeech.OnInitListener) -> TextToSpeech)? = null,
    speechFactory: (Context) -> CardSpeech = { NeuralCardSpeech(it) },
) : AutoCloseable {
    private val neural = if (ttsFactory == null) speechFactory(context.applicationContext) else null
    private val handler = Handler(Looper.getMainLooper())
    private val script by lazy {
        context.assets
            .open("scripts/meduerj-visible-card-text.js")
            .bufferedReader()
            .use { it.readText() }
    }
    private var engine: TextToSpeech? = null
    private var ready = false
    private var active = false
    private var closed = false
    private var failed = false
    private var generation = 0L
    private var pendingText: String? = null
    private var finalUtterance: String? = null
    private var evaluator: ((String, (String) -> Unit) -> Unit)? = null
    private val preferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == PREFERENCE_KEY) {
                stop()
                if (enabled && failed) {
                    engine?.shutdown()
                    engine = null
                    neural?.reset()
                    failed = false
                }
                if (enabled) {
                    readCurrentCard()
                } else if (active) {
                    onCompleted()
                }
            }
        }

    val enabled: Boolean get() = isEnabled(preferences)

    init {
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
    }

    fun setEnabled(value: Boolean) {
        preferences.edit { putBoolean(PREFERENCE_KEY, value) }
    }

    fun resume() {
        if (closed) return
        active = true
        readCurrentCard()
    }

    fun pause() {
        active = false
        stop()
    }

    fun onCardChanging() {
        evaluator = null
        stop()
    }

    fun onCardRendered(evaluate: (String, (String) -> Unit) -> Unit) {
        evaluator = evaluate
        readCurrentCard()
    }

    private fun readCurrentCard() {
        if (closed || !active || !enabled) return
        val evaluate = evaluator ?: return
        stop()
        val request = generation
        evaluate(script) { result ->
            if (!canSpeak(request)) return@evaluate
            val text = runCatching { JSONTokener(result).nextValue() as? String }.getOrNull()?.trim().orEmpty()
            if (text.isEmpty()) {
                onCompleted()
                return@evaluate
            }
            pendingText = text
            if (ready) speakPending() else initializeEngine()
        }
    }

    private fun canSpeak(request: Long) = !closed && active && enabled && request == generation

    private fun initializeEngine() {
        if (failed) {
            onCompleted()
            return
        }
        if (neural != null) {
            ready = true
            speakPending()
            return
        }
        if (engine != null) return
        // Post the result so even a synchronous test/failing engine callback runs after assignment.
        runCatching {
            engine = ttsFactory!!(TextToSpeech.OnInitListener { status -> handler.post { initialized(status) } })
        }.onFailure { fail(R.string.meduerj_read_aloud_unavailable) }
    }

    private fun initialized(status: Int) {
        if (closed) return
        val tts = engine ?: return
        if (status != TextToSpeech.SUCCESS || tts.setLanguage(Locale.forLanguageTag("pt-BR")) < TextToSpeech.LANG_AVAILABLE) {
            fail(R.string.meduerj_read_aloud_unavailable)
            return
        }
        tts.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    handler.post {
                        if (!closed && active && enabled && finalUtterance != null && utteranceId == finalUtterance) {
                            finalUtterance = null
                            onCompleted()
                        }
                    }
                }

                @Deprecated("Required by Android TextToSpeech")
                override fun onError(utteranceId: String?) {
                    handler.post {
                        if (utteranceId?.startsWith("meduerj:$generation:") == true && !closed && active && enabled) {
                            fail(R.string.meduerj_read_aloud_failed)
                        }
                    }
                }
            },
        )
        ready = true
        speakPending()
    }

    private fun speakPending() {
        val text = pendingText ?: return
        pendingText = null
        if (!canSpeak(generation)) return
        if (neural != null) {
            val request = generation
            neural.speak(text, onDone = {
                handler.post { if (canSpeak(request)) onCompleted() }
            }, onError = {
                handler.post { if (canSpeak(request)) fail(R.string.meduerj_read_aloud_failed) }
            })
            return
        }
        val tts = engine ?: return
        val chunks = speechChunks(text, TextToSpeech.getMaxSpeechInputLength())
        finalUtterance = "meduerj:$generation:${chunks.lastIndex}"
        for ((index, chunk) in chunks.withIndex()) {
            val mode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            if (tts.speak(chunk, mode, null, "meduerj:$generation:$index") == TextToSpeech.ERROR) {
                fail(R.string.meduerj_read_aloud_failed)
                break
            }
        }
    }

    private fun fail(message: Int) {
        stop()
        failed = true
        ready = false
        if (active && enabled) {
            onError(message)
            onCompleted()
        }
    }

    fun stop() {
        generation++
        pendingText = null
        finalUtterance = null
        engine?.stop()
        neural?.stop()
    }

    override fun close() {
        if (closed) return
        closed = true
        active = false
        evaluator = null
        preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        stop()
        engine?.shutdown()
        engine = null
        neural?.close()
    }

    companion object {
        const val PREFERENCE_KEY = "meduerjAutomaticReadAloud"

        fun isEnabled(preferences: SharedPreferences): Boolean = preferences.getBoolean(PREFERENCE_KEY, false)

        internal fun speechChunks(
            text: String,
            maximum: Int,
        ): List<String> {
            require(maximum >= 2)
            val chunks = mutableListOf<String>()
            var start = 0
            while (start < text.length) {
                var end = minOf(start + maximum, text.length)
                if (end < text.length) {
                    val space = text.lastIndexOf(' ', end - 1)
                    if (space > start) end = space + 1
                    if (text[end - 1].isHighSurrogate()) end--
                }
                chunks += text.substring(start, end)
                start = end
            }
            return chunks
        }
    }
}
