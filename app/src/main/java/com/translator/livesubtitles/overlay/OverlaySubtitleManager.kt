package com.translator.livesubtitles.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import com.translator.livesubtitles.R

/**
 * Manages the floating subtitle window overlay on Android screen.
 * Uses ContextThemeWrapper to prevent theme attribute inflation crashes in Background Service.
 */
class OverlaySubtitleManager(
    private val context: Context,
    private val onCloseRequested: () -> Unit
) {

    companion object {
        private const val TAG = "OverlaySubtitleManager"
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var tvSubtitle: TextView? = null
    private var tvStatus: TextView? = null
    private var tvBadge: TextView? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var clearSubtitleRunnable: Runnable? = null

    fun show() {
        if (overlayView != null) return

        try {
            // ContextThemeWrapper prevents InflateException in Service context
            val themedContext = ContextThemeWrapper(context, R.style.Theme_LiveSubtitles)
            val inflater = LayoutInflater.from(themedContext)
            overlayView = inflater.inflate(R.layout.layout_floating_subtitle, null)

            tvSubtitle = overlayView?.findViewById(R.id.tvSubtitle)
            tvStatus = overlayView?.findViewById(R.id.tvStatus)
            tvBadge = overlayView?.findViewById(R.id.tvEngineBadge)
            val btnClose = overlayView?.findViewById<ImageView>(R.id.btnCloseOverlay)
            val dragHandle = overlayView?.findViewById<View>(R.id.dragHandle)

            btnClose?.setOnClickListener {
                onCloseRequested()
            }

            layoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                x = 0
                y = 220 // Initial vertical position below status bar
            }

            setupDragListener(dragHandle ?: overlayView!!)

            windowManager.addView(overlayView, layoutParams)
            Log.i(TAG, "Floating overlay added successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create overlay view", e)
        }
    }

    private fun setupDragListener(view: View) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        view.setOnTouchListener { _, event ->
            val params = layoutParams ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    try {
                        windowManager.updateViewLayout(overlayView, params)
                    } catch (e: Exception) {
                        // ignore layout update errors during transition
                    }
                    true
                }
                else -> false
            }
        }
    }

    fun updateSubtitle(text: String) {
        mainHandler.post {
            tvSubtitle?.text = text
            tvStatus?.text = " • 🟢 Active"

            clearSubtitleRunnable?.let { mainHandler.removeCallbacks(it) }

            clearSubtitleRunnable = Runnable {
                tvSubtitle?.text = "Listening for dialogue..."
                tvStatus?.text = " • 🟢 Listening"
            }
            mainHandler.postDelayed(clearSubtitleRunnable!!, 6000)
        }
    }

    fun updateStatus(statusText: String) {
        mainHandler.post {
            tvStatus?.text = " • $statusText"
        }
    }

    fun hide() {
        mainHandler.post {
            clearSubtitleRunnable?.let { mainHandler.removeCallbacks(it) }
            overlayView?.let {
                try {
                    windowManager.removeView(it)
                } catch (e: Exception) {
                    Log.e(TAG, "Error removing overlayView", e)
                }
            }
            overlayView = null
        }
    }
}
