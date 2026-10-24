// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.reviewer

interface CardSpeech : AutoCloseable {
    fun warmUp() = Unit

    fun speak(
        text: String,
        onDone: () -> Unit,
        onError: () -> Unit,
    )

    fun stop()

    fun reset()
}
