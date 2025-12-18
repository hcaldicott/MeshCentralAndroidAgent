package com.meshcentral.agent

import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager

class InputPointerView(context: Context) : View(context) {

    private val sizeDp = 24
    private val sizePx: Int
    private val radiusPx: Float
    private val strokeWidthPx: Float

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(180, 0, 150, 255) // Semi-transparent blue
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 2f * context.resources.displayMetrics.density
    }

    private var windowManager: WindowManager? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isAttached = false

    // Screen dimensions for coordinate mapping
    private var remoteScreenWidth = 0
    private var remoteScreenHeight = 0
    private var actualScreenWidth = 0
    private var actualScreenHeight = 0
    private var statusBarHeight = 0

    init {
        val density = context.resources.displayMetrics.density
        sizePx = (sizeDp * density).toInt()
        radiusPx = (sizeDp / 2f - 2f) * density // Leave room for stroke
        strokeWidthPx = 2f * density
        strokePaint.strokeWidth = strokeWidthPx

        // Get actual screen dimensions (what the overlay uses)
        updateActualScreenSize(context)

        // Get remote screen dimensions (what the capture reports)
        remoteScreenWidth = Resources.getSystem().displayMetrics.widthPixels
        remoteScreenHeight = Resources.getSystem().displayMetrics.heightPixels

        // Get status bar height
        statusBarHeight = getStatusBarHeight(context)
    }

    private fun getStatusBarHeight(context: Context): Int {
        val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) {
            context.resources.getDimensionPixelSize(resourceId)
        } else {
            // Fallback: estimate based on density (typically 24-25dp)
            (24 * context.resources.displayMetrics.density).toInt()
        }
    }

    private fun updateActualScreenSize(context: Context) {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
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
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(sizePx, sizePx)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = sizePx / 2f
        val cy = sizePx / 2f
        canvas.drawCircle(cx, cy, radiusPx, fillPaint)
        canvas.drawCircle(cx, cy, radiusPx, strokePaint)
    }

    fun addToWindow(wm: WindowManager) {
        if (isAttached) return
        windowManager = wm
        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        try {
            wm.addView(this, layoutParams)
            isAttached = true
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                android.util.Log.e("InputPointerView", "Failed to add view: ${e.message}")
            }
        }
    }

    fun removeFromWindow() {
        if (!isAttached) return
        try {
            windowManager?.removeView(this)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                android.util.Log.e("InputPointerView", "Failed to remove view: ${e.message}")
            }
        }
        isAttached = false
        windowManager = null
        layoutParams = null
    }

    fun positionView(x: Int, y: Int) {
        if (!isAttached) return
        val params = layoutParams ?: return

        // Two scalings are involved:
        // 1. Desktop scaling (g_desktop_scalingLevel): remote sends coords in scaled image space
        // 2. System bar scaling: displayMetrics vs actual full screen

        // First, reverse the desktop scaling (g_desktop_scalingLevel: 1024 = 100%, 512 = 50%)
        val desktopScaledX = if (g_desktop_scalingLevel != 1024) {
            (x * 1024) / g_desktop_scalingLevel
        } else x

        val desktopScaledY = if (g_desktop_scalingLevel != 1024) {
            (y * 1024) / g_desktop_scalingLevel
        } else y

        // Then, apply system bar scaling (displayMetrics -> actual screen)
        val mappedX = if (remoteScreenWidth > 0 && actualScreenWidth > 0 && remoteScreenWidth != actualScreenWidth) {
            (desktopScaledX * actualScreenWidth) / remoteScreenWidth
        } else desktopScaledX

        val mappedY = if (remoteScreenHeight > 0 && actualScreenHeight > 0 && remoteScreenHeight != actualScreenHeight) {
            (desktopScaledY * actualScreenHeight) / remoteScreenHeight
        } else desktopScaledY

        // Center the dot on the cursor position
        params.x = mappedX - sizePx / 2
        params.y = mappedY - sizePx / 2

        if (BuildConfig.DEBUG) {
            android.util.Log.d("InputPointerView", "positionView: remote($x,$y) -> desktop($desktopScaledX,$desktopScaledY) -> actual($mappedX,$mappedY), scale=$g_desktop_scalingLevel")
        }

        try {
            windowManager?.updateViewLayout(this, params)
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                android.util.Log.e("InputPointerView", "Failed to update position: ${e.message}")
            }
        }
    }

    fun isVisible(): Boolean = isAttached && visibility == VISIBLE

    fun show() {
        visibility = VISIBLE
    }

    fun hide() {
        visibility = GONE
    }
}
