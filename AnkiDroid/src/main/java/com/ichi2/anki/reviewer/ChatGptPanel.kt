// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.reviewer

import android.app.Activity
import android.content.ActivityNotFoundException
import android.net.Uri
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import com.ichi2.anki.R
import timber.log.Timber

/** Browser-owned sheet for the real ChatGPT website and the browser's normal account session. */
object ChatGptPanel {
    private val address = Uri.parse("https://chatgpt.com/")

    internal fun createIntent(
        screenHeight: Int,
        screenWidth: Int,
        browserPackage: String,
    ): CustomTabsIntent {
        require(screenHeight > 0 && screenWidth > 0)
        return CustomTabsIntent
            .Builder()
            .setShowTitle(true)
            .setInitialActivityHeightPx((screenHeight * 0.65).toInt().coerceAtLeast(1), CustomTabsIntent.ACTIVITY_HEIGHT_ADJUSTABLE)
            .setInitialActivityWidthPx((screenWidth * 0.5).toInt().coerceAtLeast(1))
            .setActivitySideSheetBreakpointDp(600)
            .setActivitySideSheetPosition(CustomTabsIntent.ACTIVITY_SIDE_SHEET_POSITION_END)
            .setActivitySideSheetMaximizationEnabled(true)
            .setToolbarCornerRadiusDp(16)
            .setBackgroundInteractionEnabled(true)
            .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
            .build()
            .apply {
                // Keep HTTPS app links from launching an installed ChatGPT app instead.
                intent.setPackage(browserPackage)
                intent.data = address
            }
    }

    fun open(activity: Activity): Boolean {
        val browser =
            CustomTabsClient.getPackageName(
                activity,
                listOf("com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.microsoft.emmx", "com.sec.android.app.sbrowser"),
                true,
            ) ?: CustomTabsClient.getPackageName(activity, emptyList())
        if (browser == null) {
            Toast.makeText(activity, R.string.uerj_chatgpt_browser_unavailable, Toast.LENGTH_LONG).show()
            return false
        }
        val display = activity.resources.displayMetrics
        return try {
            createIntent(display.heightPixels, display.widthPixels, browser).launchUrl(activity, address)
            true
        } catch (error: ActivityNotFoundException) {
            showUnavailable(activity, error)
        } catch (error: SecurityException) {
            showUnavailable(activity, error)
        }
    }

    private fun showUnavailable(
        activity: Activity,
        error: Exception,
    ): Boolean {
        Timber.w(error, "Unable to open ChatGPT browser panel")
        Toast.makeText(activity, R.string.uerj_chatgpt_browser_unavailable, Toast.LENGTH_LONG).show()
        return false
    }
}
