package com.meshcentral.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.UiAutomation
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import java.lang.reflect.Method

class MeshInputAccessibilityService : AccessibilityService() {
    private val logTag = "MeshInputAccessibilityService"
    companion object {
        @Volatile
        var instance: MeshInputAccessibilityService? = null
        private val uiAutomationGetter: Method? = runCatching {
            AccessibilityService::class.java.getDeclaredMethod("getUiAutomation")
        }.getOrNull()
    }

    private val gesturePath = Path()
    private var gestureStroke: GestureDescription.StrokeDescription? = null
    private var lastGestureStartTime: Long = 0
    private val gestureCallback = object : GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) {
            if (BuildConfig.DEBUG) {
                Log.d(logTag, "dispatchGesture completed")
            }
        }

        override fun onCancelled(gestureDescription: GestureDescription?) {
            if (BuildConfig.DEBUG) {
                Log.d(logTag, "dispatchGesture cancelled")
            }
        }
    }

    private val gesturesSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

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

    private var cursorView: InputPointerView? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastCursorX = 0
    private var lastCursorY = 0
    private var lastCursorUpdateTime = 0L
    private val CURSOR_UPDATE_INTERVAL = 16L // ~60fps max update rate
    private val cursorIdleRunnable = Runnable { snapCursorToLastPosition() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        initCursorOverlay()
    }

    private fun initCursorOverlay() {
        mainHandler.post {
            try {
                val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                cursorView = InputPointerView(this).apply {
                    addToWindow(wm)
                    hide() // Start hidden until first mouse event
                }
                if (BuildConfig.DEBUG) {
                    Log.d(logTag, "Cursor overlay initialized")
                }
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) {
                    Log.e(logTag, "Failed to initialize cursor overlay: ${e.message}")
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No-op
    }

    override fun onInterrupt() {
        // No-op
    }

    override fun onDestroy() {
        instance = null
        mainHandler.post {
            cursorView?.removeFromWindow()
            cursorView = null
        }
        super.onDestroy()
    }

    private fun updateCursorPosition(x: Int, y: Int, forceUpdate: Boolean = false) {
        // Store the last position for snapping
        lastCursorX = x
        lastCursorY = y

        // Cancel any pending snap and schedule a new one
        mainHandler.removeCallbacks(cursorIdleRunnable)
        mainHandler.postDelayed(cursorIdleRunnable, 100) // Snap after 100ms of no movement

        // Throttle updates during movement to prevent visual lag
        val now = SystemClock.uptimeMillis()
        if (!forceUpdate && (now - lastCursorUpdateTime) < CURSOR_UPDATE_INTERVAL) {
            return
        }
        lastCursorUpdateTime = now

        mainHandler.post {
            cursorView?.let {
                if (!it.isVisible()) {
                    it.show()
                }
                it.positionView(x, y)
            }
        }
    }

    private fun snapCursorToLastPosition() {
        // Called when mouse movement stops - force update to final position
        mainHandler.post {
            cursorView?.positionView(lastCursorX, lastCursorY)
        }
    }

    fun hideCursor() {
        mainHandler.post {
            cursorView?.hide()
        }
    }

    fun showCursor() {
        mainHandler.post {
            cursorView?.show()
        }
    }

    fun setRemoteInputLocked(locked: Boolean) {
        remoteInputLocked = locked
    }

    @Suppress("NewApi")
    private fun sendKeyViaInputConnection(keyEvent: KeyEvent): Boolean {
        if (Build.VERSION.SDK_INT < 34) {
            return false
        }
        return try {
            val inputMethod = getInputMethod()
            val connection = inputMethod?.currentInputConnection
            if (connection == null) {
                if (BuildConfig.DEBUG) {
                    Log.d(logTag, "sendKeyViaInputConnection failed (no connection)")
                }
                return false
            }
            connection.sendKeyEvent(keyEvent)
            true
        } catch (ex: Exception) {
            if (BuildConfig.DEBUG) {
                Log.d(logTag, "sendKeyViaInputConnection exception=${ex.message}")
            }
            false
        }
    }

    fun injectKey(keyCode: Int, action: Int, metaState: Int = 0) {
        if (remoteInputLocked) {
            if (BuildConfig.DEBUG) {
                Log.d(logTag, "injectKey suppressed (remote input locked) keyCode=${KeyEvent.keyCodeToString(keyCode)} action=$action")
            }
            return
        }
        val eventTime = SystemClock.uptimeMillis()
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
        if (BuildConfig.DEBUG) {
            val unicodeChar = keyEvent.unicodeChar
            val charPart = if (unicodeChar != 0) " ('${unicodeChar.toChar()}')" else ""
            Log.d(logTag, "injectKey keyCode=${KeyEvent.keyCodeToString(keyCode)}($keyCode)$charPart action=$action meta=$metaState")
        }
        val connectionSuccess = sendKeyViaInputConnection(keyEvent)
        if (BuildConfig.DEBUG) {
            Log.d(logTag, "injectKey InputConnection result=$connectionSuccess keyCode=${KeyEvent.keyCodeToString(keyCode)}($keyCode) action=$action")
        }
        if (connectionSuccess) return
        val automation = getUiAutomation()
        if (automation == null) {
            if (BuildConfig.DEBUG) {
                Log.d(logTag, "injectKey fallback failed to get UiAutomation keyCode=${KeyEvent.keyCodeToString(keyCode)} action=$action")
            }
            return
        }
        val success = automation.injectInputEvent(keyEvent, false)
        if (BuildConfig.DEBUG) {
            Log.d(logTag, "injectKey UiAutomation result=$success keyCode=${KeyEvent.keyCodeToString(keyCode)}($keyCode) action=$action")
        }
    }

    fun injectMouseMove(x: Int, y: Int) {
        updateCursorPosition(x, y)
        if (remoteInputLocked) {
            if (BuildConfig.DEBUG) {
                Log.d(logTag, "injectMouseMove suppressed (remote input locked) x=$x y=$y")
            }
            return
        }
        if (gesturesSupported) {
            if (pointerDown) {
                if (BuildConfig.DEBUG) {
                    Log.d(logTag, "injectMouseMove (gesture) x=$x y=$y")
                }
                continueStroke(x, y)
            } else if (BuildConfig.DEBUG) {
                Log.d(logTag, "injectMouseMove ignored (no button down) x=$x y=$y")
            }
            return
        }
        dispatchMotionEvent(if (pointerDown) MotionEvent.ACTION_MOVE else MotionEvent.ACTION_HOVER_MOVE, x, y)
    }

    fun injectMouseDown(x: Int, y: Int) {
        updateCursorPosition(x, y, forceUpdate = true)
        if (remoteInputLocked) {
            if (BuildConfig.DEBUG) {
                Log.d(logTag, "injectMouseDown suppressed (remote input locked) x=$x y=$y")
            }
            return
        }
        pointerDown = true
        if (gesturesSupported) {
            if (BuildConfig.DEBUG) {
                Log.d(logTag, "injectMouseDown (gesture) x=$x y=$y")
            }
            startStroke(x, y)
            continueStroke(x, y)
            return
        }
        if (BuildConfig.DEBUG) {
            Log.d(logTag, "injectMouseDown x=$x y=$y")
        }
        dispatchMotionEvent(MotionEvent.ACTION_DOWN, x, y)
        pointerDownTime = SystemClock.uptimeMillis()
    }

    fun injectMouseUp(x: Int, y: Int) {
        updateCursorPosition(x, y, forceUpdate = true)
        if (remoteInputLocked) {
            if (BuildConfig.DEBUG) {
                Log.d(logTag, "injectMouseUp suppressed (remote input locked) x=$x y=$y")
            }
            return
        }
        if (gesturesSupported) {
            if (BuildConfig.DEBUG) {
                Log.d(logTag, "injectMouseUp (gesture) x=$x y=$y")
            }
            endStroke(x, y)
            pointerDown = false
            return
        }
        if (BuildConfig.DEBUG) {
            Log.d(logTag, "injectMouseUp x=$x y=$y")
        }
        dispatchMotionEvent(MotionEvent.ACTION_UP, x, y)
        pointerDown = false
        pointerDownTime = 0
    }

    fun injectMouseDoubleClick(x: Int, y: Int) {
        if (remoteInputLocked) {
            if (BuildConfig.DEBUG) {
                Log.d(logTag, "injectMouseDoubleClick suppressed (remote input locked) x=$x y=$y")
            }
            return
        }
        if (BuildConfig.DEBUG) {
            Log.d(logTag, "injectMouseDoubleClick x=$x y=$y")
        }
        injectMouseDown(x, y)
        injectMouseUp(x, y)
        injectMouseDown(x, y)
        injectMouseUp(x, y)
    }

    fun injectMouseScroll(x: Int, y: Int, scrollDelta: Int) {
        updateCursorPosition(x, y, forceUpdate = true)
        if (remoteInputLocked) {
            if (BuildConfig.DEBUG) {
                Log.d(logTag, "injectMouseScroll suppressed (remote input locked) x=$x y=$y delta=$scrollDelta")
            }
            return
        }
        if (gesturesSupported) {
            if (BuildConfig.DEBUG) {
                Log.d(logTag, "injectMouseScroll (gesture) x=$x y=$y delta=$scrollDelta")
            }
            performScrollGesture(x, y, scrollDelta)
            return
        }
        dispatchMotionEvent(MotionEvent.ACTION_SCROLL, x, y, scrollY = scrollDelta.toFloat())
    }

    private fun startStroke(x: Int, y: Int) {
        gesturePath.reset()
        gesturePath.moveTo(x.toFloat(), y.toFloat())
        lastGestureStartTime = SystemClock.elapsedRealtime()
        gestureStroke = null
    }

    private fun continueStroke(x: Int, y: Int) {
        gesturePath.lineTo(x.toFloat(), y.toFloat())
        var duration = SystemClock.elapsedRealtime() - lastGestureStartTime
        if (duration <= 0) duration = 1
        gestureStroke = if (gestureStroke == null) {
            GestureDescription.StrokeDescription(gesturePath, 0, duration, true)
        } else {
            gestureStroke?.continueStroke(gesturePath, 0, duration, true)
        }
        gestureStroke?.let { dispatchGestureStroke(it) }
        lastGestureStartTime = SystemClock.elapsedRealtime()
        gesturePath.reset()
        gesturePath.moveTo(x.toFloat(), y.toFloat())
    }

    private fun endStroke(x: Int, y: Int) {
        gesturePath.lineTo(x.toFloat(), y.toFloat())
        var duration = SystemClock.elapsedRealtime() - lastGestureStartTime
        if (duration <= 0) duration = 1
        gestureStroke = if (gestureStroke == null) {
            GestureDescription.StrokeDescription(gesturePath, 0, duration, false)
        } else {
            gestureStroke?.continueStroke(gesturePath, 0, duration, false)
        }
        gestureStroke?.let { dispatchGestureStroke(it) }
        gestureStroke = null
        gesturePath.reset()
    }

    private fun dispatchGestureStroke(stroke: GestureDescription.StrokeDescription) {
        val builder = GestureDescription.Builder()
        builder.addStroke(stroke)
        if (BuildConfig.DEBUG) {
            Log.d(logTag, "dispatchGesture stroke duration=${stroke.duration}")
        }
        dispatchGesture(builder.build(), gestureCallback, null)
    }

    private fun performScrollGesture(x: Int, y: Int, scrollDelta: Int) {
        if (scrollDelta == 0) return
        val displayMetrics = resources.displayMetrics
        val height = displayMetrics.heightPixels.toFloat()
        val startY = y.toFloat().coerceIn(0f, height)
        val endY = (startY - scrollDelta).coerceIn(0f, height)
        val path = Path().apply {
            moveTo(x.toFloat(), startY)
            lineTo(x.toFloat(), endY)
        }
        val duration = 200L
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        dispatchGestureStroke(stroke)
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
        if (BuildConfig.DEBUG) {
            Log.d(logTag, "dispatchMotionEvent action=$action x=$x y=$y scrollX=$scrollX scrollY=$scrollY")
        }
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
