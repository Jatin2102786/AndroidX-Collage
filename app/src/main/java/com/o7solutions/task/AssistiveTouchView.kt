package com.o7solutions.task

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.*
import androidx.core.content.ContextCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlin.math.*

class AssistiveTouchView(private val context: Context) {

    private val windowManager: WindowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var mainFab: FloatingActionButton? = null
    private var isExpanded = false
    private var fabButtons = mutableListOf<FloatingActionButton>()

    // Touch handling variables
    private var initialX: Int = 0
    private var initialY: Int = 0
    private var initialTouchX: Float = 0f
    private var initialTouchY: Float = 0f
    private var isDragging = false

    // Expanded menu options
    private val menuOptions = listOf(
        FabOption("Home", R.drawable.ic_home) { /* Handle home action */ },
        FabOption("Settings", R.drawable.ic_settings) { /* Handle settings action */ },
        FabOption("Camera", R.drawable.ic_camera) { /* Handle camera action */ },
        FabOption("Gallery", R.drawable.ic_gallery) { /* Handle gallery action */ }
    )

    data class FabOption(val title: String, val icon: Int, val action: () -> Unit)

    fun show() {
        if (mainFab != null) return

        // Create main FAB
        mainFab = FloatingActionButton(context).apply {
            setImageResource(R.drawable.capture_button) // You'll need to add this icon
            size = FloatingActionButton.SIZE_NORMAL
            setBackgroundTintList(ContextCompat.getColorStateList(context, R.color.black))
            setOnTouchListener(fabTouchListener)
            setOnClickListener { toggleMenu() }
        }

        // Set up window parameters for overlay
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 200
        }

        windowManager.addView(mainFab, params)
    }

    fun hide() {
        mainFab?.let {
            if (isExpanded) {
                hideExpandedMenu()
            }
            windowManager.removeView(it)
            mainFab = null
        }
    }

    private val fabTouchListener = View.OnTouchListener { view, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = (view.layoutParams as WindowManager.LayoutParams).x
                initialY = (view.layoutParams as WindowManager.LayoutParams).y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                isDragging = false
                true
            }

            MotionEvent.ACTION_MOVE -> {
                val deltaX = event.rawX - initialTouchX
                val deltaY = event.rawY - initialTouchY

                if (abs(deltaX) > 10 || abs(deltaY) > 10) {
                    isDragging = true

                    val params = view.layoutParams as WindowManager.LayoutParams
                    params.x = initialX + deltaX.toInt()
                    params.y = initialY + deltaY.toInt()

                    // Keep within screen bounds
                    val displayMetrics = context.resources.displayMetrics
                    params.x = params.x.coerceIn(0, displayMetrics.widthPixels - view.width)
                    params.y = params.y.coerceIn(0, displayMetrics.heightPixels - view.height)

                    windowManager.updateViewLayout(view, params)
                }
                true
            }

            MotionEvent.ACTION_UP -> {
                if (!isDragging) {
                    view.performClick()
                } else {
                    // Snap to edge
                    snapToEdge(view)
                }
                isDragging = false
                true
            }

            else -> false
        }
    }

    private fun snapToEdge(view: View) {
        val params = view.layoutParams as WindowManager.LayoutParams
        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val viewCenterX = params.x + view.width / 2

        // Snap to left or right edge based on position
        params.x = if (viewCenterX < screenWidth / 2) {
            20 // Left margin
        } else {
            screenWidth - view.width - 20 // Right margin
        }

        windowManager.updateViewLayout(view, params)
    }

    private fun toggleMenu() {
        if (isExpanded) {
            hideExpandedMenu()
        } else {
            showExpandedMenu()
        }
    }

    private fun showExpandedMenu() {
        if (isExpanded) return

        isExpanded = true
        val mainFabParams = mainFab?.layoutParams as WindowManager.LayoutParams
        val centerX = mainFabParams.x + (mainFab?.width ?: 0) / 2
        val centerY = mainFabParams.y + (mainFab?.height ?: 0) / 2

        menuOptions.forEachIndexed { index, option ->
            val angle = (360.0 / menuOptions.size) * index
            val radius = 200

            val x = centerX + (radius * cos(Math.toRadians(angle))).toInt()
            val y = centerY + (radius * sin(Math.toRadians(angle))).toInt()

            val fab = FloatingActionButton(context).apply {
                setImageResource(option.icon)
                size = FloatingActionButton.SIZE_MINI
                setBackgroundTintList(ContextCompat.getColorStateList(context, R.color.black))
                alpha = 0f
                scaleX = 0f
                scaleY = 0f
                setOnClickListener {
                    option.action()
                    hideExpandedMenu()
                }
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                this.x = x - fab.layoutParams?.width?.toInt()!! ?: 0 / 2
                this.y = y - fab.layoutParams?.height?.toInt()!! ?: 0 / 2
            }

            windowManager.addView(fab, params)
            fabButtons.add(fab)

            // Animate in
            fab.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(200)
                .setStartDelay((index * 50).toLong())
                .start()
        }

        // Rotate main FAB
        mainFab?.animate()?.rotation(45f)?.setDuration(200)?.start()
    }

    private fun hideExpandedMenu() {
        if (!isExpanded) return

        isExpanded = false

        fabButtons.forEachIndexed { index, fab ->
            fab.animate()
                .alpha(0f)
                .scaleX(0f)
                .scaleY(0f)
                .setDuration(150)
                .setStartDelay((index * 30).toLong())
                .withEndAction {
                    windowManager.removeView(fab)
                }
                .start()
        }

        fabButtons.clear()

        // Rotate main FAB back
        mainFab?.animate()?.rotation(0f)?.setDuration(200)?.start()
    }
}

// Usage in Activity or Service
class AssistiveTouchService : Service() {
    private var assistiveTouch: AssistiveTouchView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        assistiveTouch = AssistiveTouchView(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        assistiveTouch?.show()
        return START_STICKY
    }

    override fun onDestroy() {
        assistiveTouch?.hide()
        super.onDestroy()
    }

}

// Helper class for managing the service from Activity
class AssistiveTouchManager(private val context: Context) {

    fun startAssistiveTouch() {
        val intent = Intent(context, AssistiveTouchService::class.java)
        context.startService(intent)
    }

    fun stopAssistiveTouch() {
        val intent = Intent(context, AssistiveTouchService::class.java)
        context.stopService(intent)
    }
}