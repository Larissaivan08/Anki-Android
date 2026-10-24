// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.reviewer

import android.app.Activity
import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric

@RunWith(AndroidJUnit4::class)
class ChatGptPanelTest {
    @Test
    fun `opens only official ChatGPT homepage without exporting card text`() {
        val intent = ChatGptPanel.createIntent(1000, 600, "com.android.chrome").intent
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://chatgpt.com/", intent.data.toString())
        assertNull(intent.data?.query)
        assertNull(intent.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun `pins browser and keeps regular signed in session instead of an isolated login`() {
        val tab = ChatGptPanel.createIntent(1000, 600, "com.android.chrome")
        assertEquals("com.android.chrome", tab.intent.`package`)
        assertFalse(tab.isEphemeralBrowsingEnabled)
        assertEquals(0, tab.intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    @Test
    fun `requests adjustable panel with review available in the background`() {
        val intent = ChatGptPanel.createIntent(1000, 600, "com.android.chrome").intent
        assertEquals(650, intent.getIntExtra(CustomTabsIntent.EXTRA_INITIAL_ACTIVITY_HEIGHT_PX, -1))
        assertEquals(300, intent.getIntExtra(CustomTabsIntent.EXTRA_INITIAL_ACTIVITY_WIDTH_PX, -1))
        assertEquals(
            CustomTabsIntent.ACTIVITY_HEIGHT_ADJUSTABLE,
            intent.getIntExtra(CustomTabsIntent.EXTRA_ACTIVITY_HEIGHT_RESIZE_BEHAVIOR, -1),
        )
        assertFalse(intent.getBooleanExtra(CustomTabsIntent.EXTRA_DISABLE_BACKGROUND_INTERACTION, true))
    }

    @Test
    fun `missing browser leaves reviewer open instead of launching another app`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            assertFalse(ChatGptPanel.open(controller.get()))
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
