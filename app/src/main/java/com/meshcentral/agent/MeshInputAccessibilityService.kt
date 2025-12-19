package com.meshcentral.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.UiAutomation
import android.content.res.Resources
import android.graphics.Path
import android.graphics.Point
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputConnection
import android.view.WindowManager
import androidx.annotation.RequiresApi
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Accessibility service that bridges remote desktop input coming from MeshCentral with Android.
 *
 * The service keeps a floating cursor overlay so the remote mouse state is visible, translates
 * coordinates between the remote desktop and the Android display, and feeds input events into
 * Android via gestures (when the API supports it) or with raw touch/motion events. The service
 * also tracks the focused editable node so keyboard input can be injected or selection moved
 * without relying on accessibility event callbacks.
 *
 * Gesture usage is guarded behind API checks: API 24+ paths use `GestureDescription` and
 * `dispatchGesture`, while older devices fall back to synthesizing `MotionEvent`s through
 * `dispatchMotionEvent`. Node traversal helpers respect focus and selection semantics (the service
 * never explores other windows) and always stays within privacy guidelines since it only operates
 * on the active window with implicit user consent from enabling the accessibility service.
 */
class MeshInputAccessibilityService : AccessibilityService() {
    private val logTag = "MeshInputAccessibilityService"
    companion object {
        @Volatile
        var instance: MeshInputAccessibilityService? = null
        private val uiAutomationGetter: Method? = runCatching {
            AccessibilityService::class.java.getDeclaredMethod("getUiAutomation")
        }.getOrNull()
    }

    private val gesturesSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
    private val gestureContinuationSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
    private val gestureController: GestureController? = if (gesturesSupported) GestureController() else null

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
    private val passwordTexts = ConcurrentHashMap<Int, PasswordText>()

    private var cursorView: InputPointerView? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastCursorX = 0
    private var lastCursorY = 0
    private var lastCursorUpdateTime = 0L
    private val cursorUpdateInterval = 16L // ~60fps max update rate
    private val cursorIdleRunnable = Runnable { snapCursorToLastPosition() }
    // Screen dimensions for coordinate scaling
    private var remoteScreenWidth = 0
    private var remoteScreenHeight = 0
    private var actualScreenWidth = 0
    private var actualScreenHeight = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        initScreenDimensions()
        initCursorOverlay()
    }

    /**
     * Capture remote and actual screen dimensions to support coordinate scaling.
     * Remote dimensions come from display metrics, actual dimensions include system bars
     * through WindowManager APIs depending on Android release.
     */
    private fun initScreenDimensions() {
        // Remote screen dimensions (what the capture reports to remote client)
        remoteScreenWidth = Resources.getSystem().displayMetrics.widthPixels
        remoteScreenHeight = Resources.getSystem().displayMetrics.heightPixels

        // Actual screen dimensions (full screen including system bars)
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            actualScreenWidth = bounds.width()
            actualScreenHeight = bounds.height()
        } else {
            val size = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(size)
            actualScreenWidth = size.x
            actualScreenHeight = size.y
        }

        if (BuildConfig.DEBUG) {
            Log.d(logTag, "Screen dimensions: remote=${remoteScreenWidth}x${remoteScreenHeight}, actual=${actualScreenWidth}x${actualScreenHeight}")
        }
    }

    /**
     * Translate coordinates from the remote desktop into the device's native screen space.
     * First undo the remote desktop scaling factor, then adjust for the difference between
     * reported display metrics and the full screen size (status/navigation bars).
     */
    private fun scaleCoordinates(x: Int, y: Int): Pair<Int, Int> {
        // First, reverse the desktop scaling (g_desktop_scalingLevel: 1024 = 100%, 512 = 50%)
        val desktopScaledX = if (g_desktop_scalingLevel != 1024) {
            (x * 1024) / g_desktop_scalingLevel
        } else x

        val desktopScaledY = if (g_desktop_scalingLevel != 1024) {
            (y * 1024) / g_desktop_scalingLevel
        } else y

        // Then, apply system bar scaling (displayMetrics -> actual screen)
        val scaledX = if (remoteScreenWidth > 0 && actualScreenWidth > 0 && remoteScreenWidth != actualScreenWidth) {
            (desktopScaledX * actualScreenWidth) / remoteScreenWidth
        } else desktopScaledX

        val scaledY = if (remoteScreenHeight > 0 && actualScreenHeight > 0 && remoteScreenHeight != actualScreenHeight) {
            (desktopScaledY * actualScreenHeight) / remoteScreenHeight
        } else desktopScaledY

        return Pair(scaledX, scaledY)
    }

    /**
     * Sets up the floating cursor overlay off the main thread so remote pointer updates are visible.
     */
    private fun initCursorOverlay() {
        mainHandler.post {
            try {
                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
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

    /**
     * This service does not rely on accessibility event streams, so ignore them.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }

    /**
     * AccessibilityService requires implementing onInterrupt even if it is a no-op.
     */
    override fun onInterrupt() {
    }

    /**
     * Clears shared state when the service is torn down.
     */
    override fun onDestroy() {
        instance = null
        passwordTexts.clear()
        mainHandler.post {
            cursorView?.removeFromWindow()
            cursorView = null
        }
        super.onDestroy()
    }

    /**
     * Updates the overlay cursor position while throttling for smoother movement.
     */
    private fun updateCursorPosition(x: Int, y: Int, forceUpdate: Boolean = false) {
        // Store the last position for snapping
        lastCursorX = x
        lastCursorY = y

        // Cancel any pending snap and schedule a new one
        mainHandler.removeCallbacks(cursorIdleRunnable)
        mainHandler.postDelayed(cursorIdleRunnable, 100) // Snap after 100ms of no movement

        // Throttle updates during movement to prevent visual lag
        val now = SystemClock.uptimeMillis()
        if (!forceUpdate && (now - lastCursorUpdateTime) < cursorUpdateInterval) {
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

    /**
     * Forces the overlay cursor to the last known coordinates (used when mouse movement stops).
     */
    private fun snapCursorToLastPosition() {
        // Called when mouse movement stops - force update to final position
        mainHandler.post {
            cursorView?.positionView(lastCursorX, lastCursorY)
        }
    }

    /**
     * Hides the overlay cursor asynchronously.
     */
    fun hideCursor() {
        mainHandler.post {
            cursorView?.hide()
        }
    }

    @Suppress("unused")
    /**
     * Shows the overlay cursor asynchronously.
     */
    fun showCursor() {
        mainHandler.post {
            cursorView?.show()
        }
    }

    /**
     * Enables/disables input injection when remote control should ignore input.
     */
    fun setRemoteInputLocked(locked: Boolean) {
        remoteInputLocked = locked
    }

    /**
     * Tries to dispatch a key via the current `InputConnection`, which is only supported on API 34+.
     */
    @Suppress("NewApi")
    private fun sendKeyViaInputConnection(keyEvent: KeyEvent): Boolean {
        if (Build.VERSION.SDK_INT < 34) {
            return false
        }
        val connection = getInputMethod()?.currentInputConnection as? InputConnection
        if (connection == null) {
            if (BuildConfig.DEBUG) {
                Log.d(logTag, "sendKeyViaInputConnection failed (no connection)")
            }
            return false
        }
        val success: Boolean = try {
            connection.sendKeyEvent(keyEvent)
        } catch (ex: Exception) {
            if (BuildConfig.DEBUG) {
                Log.d(logTag, "sendKeyViaInputConnection exception=${ex.message}")
            }
            false
        }
        return success
    }

    /**
     * Dispatch a key through InputConnection/UiAutomation when available, otherwise fall back
     * to AccessibilityNodeInfo traversal (API <34) so remote keyboards still work.
     */
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
        if (handleAccessibilityKeyEvent(keyEvent)) return
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

    /**
     * Moves the remote cursor and injects mouse move/hover events (gestures when available).
     */
    fun injectMouseMove(x: Int, y: Int) {
        updateCursorPosition(x, y)
        if (remoteInputLocked) {
            if (BuildConfig.DEBUG) {
                Log.d(logTag, "injectMouseMove suppressed (remote input locked) x=$x y=$y")
            }
            return
        }
        // Scale coordinates for gesture injection
        val (scaledX, scaledY) = scaleCoordinates(x, y)
        if (gesturesSupported) {
            if (pointerDown) {
                if (BuildConfig.DEBUG) {
                    Log.d(logTag, "injectMouseMove (gesture) x=$x y=$y -> scaled($scaledX,$scaledY)")
                }
                gestureController?.continueStroke(scaledX, scaledY)
            } else if (BuildConfig.DEBUG) {
                Log.d(logTag, "injectMouseMove ignored (no button down) x=$x y=$y")
            }
            return
        }
        dispatchMotionEvent(if (pointerDown) MotionEvent.ACTION_MOVE else MotionEvent.ACTION_HOVER_MOVE, scaledX, scaledY)
    }

    /**
     * Injects a mouse-down event, starting a gesture or pointer down sequence.
     */
    fun injectMouseDown(x: Int, y: Int) {
        updateCursorPosition(x, y, forceUpdate = true)
        if (remoteInputLocked) {
            if (BuildConfig.DEBUG) {
                Log.d(logTag, "injectMouseDown suppressed (remote input locked) x=$x y=$y")
            }
            return
        }
        // Scale coordinates for gesture injection
        val (scaledX, scaledY) = scaleCoordinates(x, y)
        pointerDown = true
        if (gesturesSupported) {
            if (BuildConfig.DEBUG) {
                Log.d(logTag, "injectMouseDown (gesture) x=$x y=$y -> scaled($scaledX,$scaledY)")
            }
            gestureController?.startStroke(scaledX, scaledY)
            gestureController?.continueStroke(scaledX, scaledY)
            return
        }
        if (BuildConfig.DEBUG) {
            Log.d(logTag, "injectMouseDown x=$x y=$y -> scaled($scaledX,$scaledY)")
        }
        dispatchMotionEvent(MotionEvent.ACTION_DOWN, scaledX, scaledY)
        pointerDownTime = SystemClock.uptimeMillis()
    }

    /**
     * Injects the corresponding mouse-up event for the current pointer.
     */
    fun injectMouseUp(x: Int, y: Int) {
        updateCursorPosition(x, y, forceUpdate = true)
        if (remoteInputLocked) {
            if (BuildConfig.DEBUG) {
                Log.d(logTag, "injectMouseUp suppressed (remote input locked) x=$x y=$y")
            }
            return
        }
        // Scale coordinates for gesture injection
        val (scaledX, scaledY) = scaleCoordinates(x, y)
        if (gesturesSupported) {
            if (BuildConfig.DEBUG) {
                Log.d(logTag, "injectMouseUp (gesture) x=$x y=$y -> scaled($scaledX,$scaledY)")
            }
            gestureController?.endStroke(scaledX, scaledY)
            pointerDown = false
            return
        }
        if (BuildConfig.DEBUG) {
            Log.d(logTag, "injectMouseUp x=$x y=$y -> scaled($scaledX,$scaledY)")
        }
        dispatchMotionEvent(MotionEvent.ACTION_UP, scaledX, scaledY)
        pointerDown = false
        pointerDownTime = 0
    }

    /**
     * Synthetically performs a double-click via sequential down/up events.
     */
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

    /**
     * Sends a scroll gesture or motion event based on the configured gesture availability.
     */
    fun injectMouseScroll(x: Int, y: Int, scrollDelta: Int) {
        updateCursorPosition(x, y, forceUpdate = true)
        if (remoteInputLocked) {
            if (BuildConfig.DEBUG) {
                Log.d(logTag, "injectMouseScroll suppressed (remote input locked) x=$x y=$y delta=$scrollDelta")
            }
            return
        }
        // Scale coordinates for gesture injection
        val (scaledX, scaledY) = scaleCoordinates(x, y)
        if (gesturesSupported) {
            if (BuildConfig.DEBUG) {
                Log.d(logTag, "injectMouseScroll (gesture) x=$x y=$y -> scaled($scaledX,$scaledY) delta=$scrollDelta")
            }
            gestureController?.performScrollGesture(scaledX, scaledY, scrollDelta)
            return
        }
        dispatchMotionEvent(MotionEvent.ACTION_SCROLL, scaledX, scaledY, scrollY = scrollDelta.toFloat())
    }

    /**
     * Injects synthesized motion events when gestures are not available or for hover events.
     */
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

    /**
     * Uses reflection to grab the internal `UiAutomation` instance from `AccessibilityService`.
     */
    private fun getUiAutomation(): UiAutomation? {
        val getter = uiAutomationGetter ?: return null
        return try {
            getter.invoke(this) as? UiAutomation
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Intercepts navigation keys from accessibility before they enter the focused node.
     */
    private fun handleAccessibilityKeyEvent(keyEvent: KeyEvent): Boolean {
        if (keyEvent.action != KeyEvent.ACTION_DOWN) return false
        return when (keyEvent.keyCode) {
            KeyEvent.KEYCODE_DEL -> removeCharacterAtCursor(false)
            KeyEvent.KEYCODE_FORWARD_DEL -> removeCharacterAtCursor(true)
            KeyEvent.KEYCODE_DPAD_LEFT -> moveCursor("ArrowLeft")
            KeyEvent.KEYCODE_DPAD_RIGHT -> moveCursor("ArrowRight")
            KeyEvent.KEYCODE_DPAD_UP -> moveCursor("ArrowUp")
            KeyEvent.KEYCODE_DPAD_DOWN -> moveCursor("ArrowDown")
            KeyEvent.KEYCODE_MOVE_HOME -> moveCursor("Home")
            KeyEvent.KEYCODE_MOVE_END -> moveCursor("End")
            KeyEvent.KEYCODE_ENTER -> handleEnterKey()
            KeyEvent.KEYCODE_TAB -> moveFocusForward()
            else -> {
                val unicodeChar = keyEvent.unicodeChar
                if (unicodeChar in 32..255) {
                    enterText(unicodeChar.toChar().toString())
                } else {
                    false
                }
            }
        }
    }

    /**
     * Inserts text at the current focus, replacing any selection and keeping cursor state updated.
     */
    private fun enterText(text: String): Boolean {
        return withFocusedEditableNode { node ->
            val existingText = getExistingText(node)?.toString() ?: ""
            val selectionStart = node.textSelectionStart.takeIf { it >= 0 } ?: existingText.length
            val selectionEnd = node.textSelectionEnd.takeIf { it >= 0 } ?: selectionStart
            val typeInMiddle = selectionStart < existingText.length
            val newText = if (typeInMiddle) {
                existingText.take(selectionStart) + text + existingText.drop(selectionEnd)
            } else {
                existingText + text
            }
            val cursorPos = if (typeInMiddle) selectionStart + text.length else newText.length
            val result = replaceTextInNode(node, newText, cursorPos)
            if (node.isPassword) {
                savePasswordText(node, newText)
            }
            result
        }
    }

    /**
     * Removes a character before or after the cursor depending on `removeForward`.
     */
    private fun removeCharacterAtCursor(removeForward: Boolean): Boolean {
        return withFocusedEditableNode { node ->
            val existingText = getExistingText(node)?.toString() ?: return@withFocusedEditableNode false
            val selectionStart = node.textSelectionStart.takeIf { it >= 0 } ?: existingText.length
            val selectionEnd = node.textSelectionEnd.takeIf { it >= 0 } ?: selectionStart
            if (existingText.isEmpty()) return@withFocusedEditableNode false
            val typeInMiddle = selectionStart < existingText.length
            val (newText, cursorPos) = if (typeInMiddle) {
                if (selectionEnd > selectionStart) {
                    val updated = existingText.removeRange(selectionStart, selectionEnd)
                    updated to selectionStart
                } else if (!removeForward && selectionStart > 0) {
                    val updated = existingText.removeRange(selectionStart - 1, selectionStart)
                    updated to selectionStart - 1
                } else if (removeForward) {
                    val updated = existingText.removeRange(selectionEnd, selectionEnd + 1)
                    updated to selectionEnd
                } else {
                    existingText to selectionStart
                }
            } else if (removeForward) {
                return@withFocusedEditableNode false
            } else {
                val updated = existingText.removeRange(existingText.length - 1, existingText.length)
                updated to existingText.length - 1
            }
            val result = replaceTextInNode(node, newText, cursorPos.coerceAtLeast(0))
            if (node.isPassword) {
                savePasswordText(node, newText)
            }
            result
        }
    }

    /**
     * Moves the cursor or selection based on arrow/home/end commands.
     */
    private fun moveCursor(command: String): Boolean {
        return withFocusedEditableNode { node ->
            val existingText = getExistingText(node)?.toString() ?: return@withFocusedEditableNode false
            if (existingText.isEmpty()) return@withFocusedEditableNode false
            val selectionStart = node.textSelectionStart.takeIf { it >= 0 } ?: existingText.length
            val selectionEnd = node.textSelectionEnd.takeIf { it >= 0 } ?: selectionStart
            val targetPosition = when (command) {
                "ArrowLeft" -> if (selectionEnd > selectionStart) selectionStart else (selectionStart - 1).coerceAtLeast(0)
                "ArrowRight" -> if (selectionEnd > selectionStart) selectionEnd else (selectionEnd + 1).coerceAtMost(existingText.length)
                "ArrowUp" -> 0
                "ArrowDown" -> existingText.length
                "Home" -> 0
                "End" -> existingText.length
                else -> return@withFocusedEditableNode false
            }
            setSelection(node, targetPosition)
        }
    }

    /**
     * Advances accessibility focus to the next focusable node in the tree.
     */
    private fun moveFocusForward(): Boolean {
        val root = rootInActiveWindow ?: return false
        val focusable = findFocusableNodeRecursive(root)
        root.recycle()
        return focusable?.let {
            val changed = it.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            it.recycle()
            changed
        } ?: false
    }

    /**
     * Performs the enter action on the focused node, preferring IME enter when available.
     */
    private fun handleEnterKey(): Boolean {
        return withFocusedEditableNode { node ->
            val actions = node.actionList
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                        actions.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER) ->
                    node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                actions.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK) ->
                    node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK.id)
                else -> false
            }
        }
    }

    /**
     * Replaces the node text via `ACTION_SET_TEXT` and updates cursor position afterwards.
     */
    private fun replaceTextInNode(node: AccessibilityNodeInfo, newText: String, cursorPos: Int): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        }
        val result = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (result) {
            setSelection(node, cursorPos, newText.length)
        }
        return result
    }

    /**
     * Sets the selection/cursor position on an editable node.
     */
    private fun setSelection(node: AccessibilityNodeInfo, position: Int, maxLength: Int? = null): Boolean {
        val safeMax = maxLength ?: (node.text?.length ?: 0)
        val clamped = position.coerceIn(0, safeMax)
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, clamped)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, clamped)
        }
        val result = node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_SELECTION.id, args)
        if (BuildConfig.DEBUG) {
            Log.d(logTag, "setSelection position=$clamped safeMax=$safeMax result=$result")
        }
        return result
    }

    /**
     * Runs `block` on the currently focused editable node, if any.
     */
    private inline fun withFocusedEditableNode(block: (AccessibilityNodeInfo) -> Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val focusNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        root.recycle()
        focusNode ?: return false
        return try {
            block(focusNode)
        } finally {
            focusNode.recycle()
        }
    }

    /**
     * Reads the node text while hiding hints and respecting password caching.
     */
    private fun getExistingText(node: AccessibilityNodeInfo): CharSequence? {
        if (node.isPassword) {
            val text = node.text
            if (text == null || text.isEmpty()) {
                passwordTexts.remove(node.windowId)
                return null
            }
            return passwordTexts[node.windowId]?.takeIf { !it.isExpired() }?.text
        }
        var existing = node.text
        if (existing != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val hint = node.hintText
            if (hint != null && existing == hint) {
                existing = null
            }
        }
        return existing
    }

    /**
     * Tracks password text off-screen so it can be restored without exposing it.
     */
    private fun savePasswordText(node: AccessibilityNodeInfo, text: String) {
        passwordTexts[node.windowId] = PasswordText(text)
    }

    /**
     * Encapsulates gesture-based mouse injection for API 24+.
     */
    @RequiresApi(Build.VERSION_CODES.N)
    private inner class GestureController {
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

        /**
         * Begins a gesture stroke at (x, y).
         */
        fun startStroke(x: Int, y: Int) {
            gesturePath.reset()
            gesturePath.moveTo(x.toFloat(), y.toFloat())
            lastGestureStartTime = SystemClock.elapsedRealtime()
            gestureStroke = null
        }

        /**
         * Extends the gesture stroke, optionally continuing for devices with API 26+.
         */
        fun continueStroke(x: Int, y: Int) {
            gesturePath.lineTo(x.toFloat(), y.toFloat())
            if (!gestureContinuationSupported) {
                return
            }
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

        /**
         * Ends the current gesture stroke and dispatches it.
         */
        fun endStroke(x: Int, y: Int) {
            gesturePath.lineTo(x.toFloat(), y.toFloat())
            var duration = SystemClock.elapsedRealtime() - lastGestureStartTime
            if (duration <= 0) duration = 1
            if (gestureContinuationSupported) {
                gestureStroke = if (gestureStroke == null) {
                    GestureDescription.StrokeDescription(gesturePath, 0, duration, false)
                } else {
                    gestureStroke?.continueStroke(gesturePath, 0, duration, false)
                }
                gestureStroke?.let { dispatchGestureStroke(it) }
                gestureStroke = null
            } else {
                val stroke = GestureDescription.StrokeDescription(gesturePath, 0, duration)
                dispatchGestureStroke(stroke)
            }
            gesturePath.reset()
        }

        /**
         * Dispatches a vertical scroll path as a gesture stroke.
         */
        fun performScrollGesture(x: Int, y: Int, scrollDelta: Int) {
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

        /**
         * Builds and sends the gesture description to the accessibility service.
         */
        private fun dispatchGestureStroke(stroke: GestureDescription.StrokeDescription) {
            val builder = GestureDescription.Builder()
            builder.addStroke(stroke)
            if (BuildConfig.DEBUG) {
                Log.d(logTag, "dispatchGesture stroke duration=${stroke.duration}")
            }
            dispatchGesture(builder.build(), gestureCallback, null)
        }
    }

    /**
     * Recursively finds the first focusable node in the subtree.
     */
    @Suppress("DEPRECATION")
    private fun findFocusableNodeRecursive(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isFocusable) return AccessibilityNodeInfo.obtain(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            val focusable = findFocusableNodeRecursive(child)
            child?.recycle()
            if (focusable != null) {
                return focusable
            }
        }
        return null
    }

    /**
     * Tracks obfuscated password text along with the last update time.
     */
    private data class PasswordText(val text: String, val timestamp: Long = System.currentTimeMillis()) {
        fun isExpired(): Boolean = timestamp < System.currentTimeMillis() - 300_000
    }
}
