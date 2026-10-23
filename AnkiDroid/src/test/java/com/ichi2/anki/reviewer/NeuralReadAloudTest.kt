// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.reviewer

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class NeuralReadAloudTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences = context.getSharedPreferences("neural-read-aloud-test", Context.MODE_PRIVATE)
    private val voice = mockk<CardSpeech>(relaxed = true)
    private lateinit var reader: AutomaticCardReadAloud
    private lateinit var completed: () -> Unit
    private lateinit var failed: () -> Unit
    private var completions = 0
    private var errors = 0

    @Before
    fun setup() {
        preferences.edit().clear().commit()
        every { voice.speak(any(), any(), any()) } answers {
            completed = secondArg()
            failed = thirdArg()
        }
        reader =
            AutomaticCardReadAloud(context, preferences, onError = { errors++ }, onCompleted = { completions++ }, speechFactory = { voice })
        reader.resume()
    }

    @After
    fun teardown() = reader.close()

    private fun read() {
        reader.setEnabled(true)
        reader.onCardRendered { _, callback -> callback("\"Hipertensão arterial\"") }
    }

    @Test
    fun `default backend reads visible text with neural engine`() {
        read()
        verify(exactly = 1) { voice.speak("Hipertensão arterial", any(), any()) }
        completed()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, completions)
    }

    @Test
    fun `turning off ignores delayed neural completion and failure`() {
        read()
        reader.setEnabled(false)
        val before = completions
        completed()
        failed()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(before, completions)
        assertEquals(0, errors)
        verify { voice.stop() }
    }

    @Test
    fun `changing cards rejects old generated audio callbacks`() {
        read()
        reader.onCardChanging()
        completed()
        failed()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, completions)
        assertEquals(0, errors)
    }

    @Test
    fun `neural failure can be retried by toggling option`() {
        read()
        failed()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, errors)
        reader.setEnabled(false)
        reader.setEnabled(true)
        verify { voice.reset() }
        verify(exactly = 2) { voice.speak("Hipertensão arterial", any(), any()) }
    }

    @Test
    fun `closing reviewer stops and releases neural speech`() {
        read()
        reader.close()
        completed()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, completions)
        verify(exactly = 1) { voice.close() }
    }
}
