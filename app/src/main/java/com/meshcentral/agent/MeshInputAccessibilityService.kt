package com.meshcentral.agent

import android.accessibilityservice.AccessibilityService
import android.app.UiAutomation
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent
import java.lang.reflect.Method

class MeshInputAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile
        var instance: MeshInputAccessibilityService? = null
        private val uiAutomationGetter: Method? = runCatching {
            AccessibilityService::class.java.getDeclaredMethod("getUiAutomation")
        }.getOrNull()
    }

    private val pointerProperties = MotionEvent.PointerProperties().apply {
        id = 0
        toolType = MotionEvent.TOOL_TYPE_MOUSE
    }
    private val pointerPropertiesArray = arrayOf(pointerProperties)
    private val pointerCoords = MotionEvent.PointerCoords()
    private val pointerCoordsArray = arrayOf(pointerCoords)

    private var pointerDownTime: Long = 0
    private var pointerDown: Boolean = false
    @Volatile
    private var remoteInputLocked = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No-op
    }

    override fun onInterrupt() {
        // No-op
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun setRemoteInputLocked(locked: Boolean) {
        remoteInputLocked = locked
    }

    fun injectKey(keyCode: Int, action: Int, metaState: Int = 0) {
        if (remoteInputLocked) return
        val eventTime = SystemClock.uptimeMillis()
        val automation = getUiAutomation() ?: return
        val keyEvent = KeyEvent(
            eventTime,
            eventTime,
            action,
            keyCode,
            0,
            metaState,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            0,
            KeyEvent.FLAG_FROM_SYSTEM,
            InputDevice.SOURCE_KEYBOARD
        )
        automation.injectInputEvent(keyEvent, false)
    }

    fun injectMouseMove(x: Int, y: Int) {
        dispatchMotionEvent(if (pointerDown) MotionEvent.ACTION_MOVE else MotionEvent.ACTION_HOVER_MOVE, x, y)
    }

    fun injectMouseDown(x: Int, y: Int) {
        pointerDown = true
        dispatchMotionEvent(MotionEvent.ACTION_DOWN, x, y)
    }

    fun injectMouseUp(x: Int, y: Int) {
        dispatchMotionEvent(MotionEvent.ACTION_UP, x, y)
        pointerDown = false
        pointerDownTime = 0
    }

    fun injectMouseDoubleClick(x: Int, y: Int) {
        injectMouseDown(x, y)
        injectMouseUp(x, y)
        injectMouseDown(x, y)
        injectMouseUp(x, y)
    }

    fun injectMouseScroll(x: Int, y: Int, scrollDelta: Int) {
        dispatchMotionEvent(MotionEvent.ACTION_SCROLL, x, y, scrollY = scrollDelta.toFloat())
    }

    private fun dispatchMotionEvent(
        action: Int,
        x: Int,
        y: Int,
        scrollX: Float = 0f,
        scrollY: Float = 0f
    ) {
        if (remoteInputLocked) return
        val eventTime = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN || pointerDownTime == 0L) {
            pointerDownTime = eventTime
        }
        pointerCoords.x = x.toFloat()
        pointerCoords.y = y.toFloat()
        pointerCoords.pressure = 1f
        pointerCoords.size = 1f
        pointerCoords.setAxisValue(MotionEvent.AXIS_VSCROLL, scrollY)
        pointerCoords.setAxisValue(MotionEvent.AXIS_HSCROLL, scrollX)
        val event = MotionEvent.obtain(
            pointerDownTime,
            eventTime,
            action,
            1,
            pointerPropertiesArray,
            pointerCoordsArray,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_MOUSE,
            0
        )
        val automation = getUiAutomation() ?: return
        automation.injectInputEvent(event, false)
        event.recycle()
        if (action == MotionEvent.ACTION_UP) {
            pointerDown = false
            pointerDownTime = 0
        }
    }

    private fun getUiAutomation(): UiAutomation? {
        val getter = uiAutomationGetter ?: return null
        return try {
            getter.invoke(this) as? UiAutomation
        } catch (_: Exception) {
            null
        }
    }
}
