package com.unhappychoice.droidflyer.presentation.view.core

import android.os.Build
import android.view.MotionEvent
import android.view.Window
import androidx.appcompat.app.AlertDialog

internal fun Window.protectTradingTouches() {
    callback = ObscuredTouchCallback(callback)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        setHideOverlayWindows(true)
    }
}

internal fun AlertDialog.Builder.showTradingConfirmation(): AlertDialog {
    val dialog = create()
    requireNotNull(dialog.window).protectTradingTouches()
    dialog.show()
    return dialog
}

private class ObscuredTouchCallback(private val original: Window.Callback) : Window.Callback by original {
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val obscured = MotionEvent.FLAG_WINDOW_IS_OBSCURED or MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED
        if (event.flags and obscured == 0) return original.dispatchTouchEvent(event)

        // Clear any pressed control before later events in this gesture arrive.
        val cancel = MotionEvent.obtain(event)
        try {
            cancel.action = MotionEvent.ACTION_CANCEL
            original.dispatchTouchEvent(cancel)
        } finally {
            cancel.recycle()
        }
        return true
    }
}
