package com.unhappychoice.droidflyer.presentation.view

import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.Window
import org.junit.Assert.assertTrue
import org.robolectric.Shadows.shadowOf

internal fun Window.tap(button: View, downFlags: Int = 0, upFlags: Int = downFlags, moveFlags: Int? = null) {
    val decor = decorView
    val width = View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY)
    val height = View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY)
    decor.measure(width, height)
    decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)
    assertTrue(button.isShown && button.isEnabled && button.width > 0 && button.height > 0)
    val location = IntArray(2).also(button::getLocationInWindow)
    val x = location[0] + button.width / 2f
    val y = location[1] + button.height / 2f
    val downTime = SystemClock.uptimeMillis()
    val pointer = MotionEvent.PointerProperties().apply {
        id = 0
        toolType = MotionEvent.TOOL_TYPE_FINGER
    }
    val coordinates = MotionEvent.PointerCoords().apply {
        this.x = x
        this.y = y
        pressure = 1f
        size = 1f
    }
    fun dispatch(action: Int, flags: Int) {
        val event = MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(), action, 1, arrayOf(pointer), arrayOf(coordinates),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, flags
        )
        try {
            callback.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }
    dispatch(MotionEvent.ACTION_DOWN, downFlags)
    moveFlags?.let { dispatch(MotionEvent.ACTION_MOVE, it) }
    dispatch(MotionEvent.ACTION_UP, upFlags)
    shadowOf(Looper.getMainLooper()).idle()
}

internal fun Window.tapWithObscuration(button: View) {
    val full = MotionEvent.FLAG_WINDOW_IS_OBSCURED
    val partial = MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED
    tap(button, downFlags = full)
    tap(button, downFlags = partial)
    tap(button, downFlags = 0, upFlags = partial)
    tap(button, downFlags = 0, upFlags = 0, moveFlags = full)
}
