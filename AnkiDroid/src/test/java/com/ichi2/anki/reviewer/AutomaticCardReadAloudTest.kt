// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.reviewer

import android.content.Context
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class AutomaticCardReadAloudTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences = context.getSharedPreferences("automatic-card-read-aloud-test", Context.MODE_PRIVATE)
    private val engine = mockk<TextToSpeech>(relaxed = true)
    private val progress = slot<UtteranceProgressListener>()
    private lateinit var initialization: TextToSpeech.OnInitListener
    private lateinit var reader: AutomaticCardReadAloud
    private val errors = mutableListOf<Int>()
    private var completions = 0

    @Before
    fun setup() {
        preferences.edit().clear().commit()
        every { engine.setLanguage(any()) } returns TextToSpeech.LANG_COUNTRY_AVAILABLE
        every { engine.setOnUtteranceProgressListener(capture(progress)) } returns TextToSpeech.SUCCESS
        every { engine.speak(any<String>(), any(), any(), any()) } returns TextToSpeech.SUCCESS
        reader =
            AutomaticCardReadAloud(
                context,
                preferences,
                onError = { errors += it },
                onCompleted = { completions++ },
                ttsFactory = {
                    initialization = it
                    engine
                },
            )
    }

    @After
    fun teardown() {
        reader.close()
    }

    private fun enableAndRender(text: String = "\"Pergunta visível\"") {
        reader.resume()
        reader.setEnabled(true)
        reader.onCardRendered { _, callback -> callback(text) }
    }

    private fun initializeEngine() {
        initialization.onInit(TextToSpeech.SUCCESS)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `disabled by default and does not initialize speech`() {
        reader.resume()
        var evaluated = false
        reader.onCardRendered { _, _ -> evaluated = true }
        assertFalse(reader.enabled)
        assertFalse(evaluated)
        verify(exactly = 0) { engine.setLanguage(any()) }
    }

    @Test
    fun `enabling reads current rendered card in Brazilian Portuguese`() {
        enableAndRender()
        initializeEngine()
        verify { engine.setLanguage(Locale.forLanguageTag("pt-BR")) }
        verify(exactly = 1) { engine.speak("Pergunta visível", TextToSpeech.QUEUE_FLUSH, null, any()) }
    }

    @Test
    fun `disabling immediately stops playback and ignores delayed DOM callback`() {
        enableAndRender()
        initializeEngine()
        lateinit var delayed: (String) -> Unit
        reader.onCardRendered { _, callback -> delayed = callback }
        clearMocks(engine, answers = false)
        reader.setEnabled(false)
        delayed("\"Não ler\"")
        verify(exactly = 1) { engine.stop() }
        verify(exactly = 0) { engine.speak(any<String>(), any(), any(), any()) }
    }

    @Test
    fun `disabling during engine initialization cannot restart speech`() {
        enableAndRender()
        reader.setEnabled(false)
        initializeEngine()
        verify(exactly = 0) { engine.speak(any<String>(), any(), any(), any()) }
    }

    @Test
    fun `card change invalidates text extracted for previous side`() {
        enableAndRender()
        initializeEngine()
        lateinit var delayed: (String) -> Unit
        reader.onCardRendered { _, callback -> delayed = callback }
        reader.onCardChanging()
        delayed("\"Pergunta antiga\"")
        reader.onCardRendered { _, callback -> callback("\"Resposta revelada\"") }
        verify(exactly = 0) { engine.speak("Pergunta antiga", any(), any(), any()) }
        verify(exactly = 1) { engine.speak("Resposta revelada", TextToSpeech.QUEUE_FLUSH, null, any()) }
    }

    @Test
    fun `pause stops speech and stale callback cannot speak in background`() {
        enableAndRender()
        initializeEngine()
        lateinit var delayed: (String) -> Unit
        reader.onCardRendered { _, callback -> delayed = callback }
        reader.pause()
        delayed("\"Segundo plano\"")
        verify(exactly = 1) { engine.speak(any<String>(), any(), any(), any()) }
        verify { engine.stop() }
    }

    @Test
    fun `close before initialization shuts down engine and prevents playback`() {
        enableAndRender()
        reader.close()
        initializeEngine()
        verify(exactly = 1) { engine.shutdown() }
        verify(exactly = 0) { engine.speak(any<String>(), any(), any(), any()) }
    }

    @Test
    fun `missing Portuguese voice reports problem without prompting login`() {
        every { engine.setLanguage(any()) } returns TextToSpeech.LANG_MISSING_DATA
        enableAndRender()
        initializeEngine()
        assertEquals(1, errors.size)
        assertEquals(1, completions)
        verify(exactly = 0) { engine.speak(any<String>(), any(), any(), any()) }
    }

    @Test
    fun `only current final utterance completes reading`() {
        enableAndRender()
        initializeEngine()
        val ids = mutableListOf<String>()
        every { engine.speak(any<String>(), any(), any(), capture(ids)) } returns TextToSpeech.SUCCESS
        reader.onCardRendered { _, callback -> callback("\"Resposta\"") }
        progress.captured.onDone("old-utterance")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, completions)
        progress.captured.onDone(ids.last())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, completions)
    }

    @Test
    fun `long cards preserve all text without splitting unicode pairs`() {
        val text = "Ação e revisão 🩺 ".repeat(800)
        val chunks = AutomaticCardReadAloud.speechChunks(text, 37)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= 37 && !it.last().isHighSurrogate() })
    }

    @Test
    fun `stopped speech cannot complete or advance the card`() {
        enableAndRender()
        initializeEngine()
        reader.stop()
        progress.captured.onDone(null)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, completions)
    }
}
