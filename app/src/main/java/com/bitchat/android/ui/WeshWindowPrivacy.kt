package com.bitchat.android.ui

import android.app.Activity
import android.os.Build
import android.view.WindowManager

/** Keeps conversations out of screenshots, screen recordings, casting and the recents preview. */
internal object WeshWindowPrivacy {
    fun apply(activity: Activity) {
        activity.window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.setRecentsScreenshotEnabled(false)
        }
    }
}
