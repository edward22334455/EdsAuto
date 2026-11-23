package com.eds.auto

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

/**
 * Only job: put Waze in split screen, then open the music app in the other half.
 * It does not read the screen.
 */
class SplitService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: SplitService? = null
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    fun launchSplit(first: Intent, second: Intent) {
        first.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(first)

        handler.postDelayed({
            performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
        }, 2000)

        handler.postDelayed({
            second.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
            startActivity(second)
        }, 3800)
    }
}
