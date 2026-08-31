package com.ghost.api.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.*
import android.widget.FrameLayout
import android.widget.Toast
import timber.log.Timber
import kotlin.math.*

/**
 * AppReelOverlay - Compact horizontal tape dock hovering above the top apex node.
 * Summoned by touching/holding the Top Orange ✧ node.
 * Features smooth horizontal scrubbing, haptic clicks, swipe-down dismiss,
 * and explicit tap-to-launch (never accidental launch on drag release).
 */
@SuppressLint("ViewConstructor")
class AppReelOverlay(
    context: Context,
    private val windowManager: WindowManager,
    private val onDismiss: () -> Unit
) : FrameLayout(context) {

    data class ReelAppItem(
        val label: String,
        val packageName: String,
        val iconBitmap: Bitmap?
    )

    private val appItems = mutableListOf<ReelAppItem>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var scrollOffset = 0f
    private var lastTouchX = 0f
    private var startTouchX = 0f
    private var startTouchY = 0f
    private var totalDragDistance = 0f
    private var isDragging = false
    private var selectedIndex = 0
    private var lastHapticIndex = -1

    private val colorOrange = Color.parseColor("#F97316")
    private val colorGreen = Color.parseColor("#22C55E")

    private val itemWidth = dpToPx(60).toFloat()
    private val itemSpacing = dpToPx(12).toFloat()
    private val stride = itemWidth + itemSpacing

    val windowParams: WindowManager.LayoutParams

    init {
        setWillNotDraw(false)
        loadInstalledApps()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val metrics = context.resources.displayMetrics
        val width = (metrics.widthPixels * 0.92).toInt().coerceAtMost(dpToPx(400))
        val height = dpToPx(95)

        windowParams = WindowManager.LayoutParams(
            width, height, type,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            y = -dpToPx(190) // Positioned gracefully above the top orange ✧ apex
        }

        textPaint.apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    }

    private fun loadInstalledApps() {
        try {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = pm.queryIntentActivities(intent, 0)
            for (info in resolveInfos) {
                val label = info.loadLabel(pm).toString()
                val pkg = info.activityInfo.packageName
                val iconDrawable = info.loadIcon(pm)
                val bmp = drawableToBitmap(iconDrawable)
                appItems.add(ReelAppItem(label, pkg, bmp))
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to load apps for reel")
        }
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap? {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return drawable.bitmap
        }
        val size = dpToPx(38).coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f - dpToPx(4)

        // 1. Sleek Glassmorphic Floating Dock
        val bgRect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#EE121216")
        canvas.drawRoundRect(bgRect, dpToPx(16).toFloat(), dpToPx(16).toFloat(), paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dpToPx(1.5f).toFloat()
        paint.color = Color.parseColor("#4DF97316")
        canvas.drawRoundRect(bgRect, dpToPx(16).toFloat(), dpToPx(16).toFloat(), paint)

        if (appItems.isEmpty()) {
            textPaint.color = Color.WHITE
            canvas.drawText("Loading apps...", cx, cy, textPaint)
            return
        }

        // 2. Draw Horizontal Tape Reel
        val numItems = appItems.size
        val maxScroll = (numItems - 1) * stride
        scrollOffset = scrollOffset.coerceIn(0f, maxScroll)

        for (i in 0 until numItems) {
            val itemX = cx + (i * stride) - scrollOffset
            if (itemX < -itemWidth || itemX > width + itemWidth) continue // Cull offscreen

            val distFromCenter = abs(itemX - cx)
            val isCenter = distFromCenter < (stride / 2f)
            val scale = (1.0f - (distFromCenter / (width * 0.75f)).coerceIn(0f, 0.3f))
            val alpha = (255 * (1.0f - (distFromCenter / (width * 0.65f)).coerceIn(0f, 0.65f))).toInt()

            val curCardW = itemWidth * scale
            val curCardH = dpToPx(62) * scale
            val cardRect = RectF(
                itemX - curCardW / 2f,
                cy - curCardH / 2f,
                itemX + curCardW / 2f,
                cy + curCardH / 2f
            )

            // Card Background
            paint.style = Paint.Style.FILL
            paint.color = if (isCenter) Color.parseColor("#2C2C34") else Color.parseColor("#1C1C22")
            paint.alpha = alpha
            canvas.drawRoundRect(cardRect, dpToPx(10).toFloat(), dpToPx(10).toFloat(), paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = if (isCenter) dpToPx(1.5f).toFloat() else dpToPx(0.8f).toFloat()
            paint.color = if (isCenter) colorOrange else Color.parseColor("#26FFFFFF")
            paint.alpha = alpha
            canvas.drawRoundRect(cardRect, dpToPx(10).toFloat(), dpToPx(10).toFloat(), paint)

            // App Icon
            val item = appItems[i]
            item.iconBitmap?.let { bmp ->
                val iconSize = (dpToPx(28) * scale).toInt()
                val iconRect = Rect(
                    (itemX - iconSize / 2).toInt(),
                    (cy - curCardH / 2 + dpToPx(5) * scale).toInt(),
                    (itemX + iconSize / 2).toInt(),
                    (cy - curCardH / 2 + dpToPx(5) * scale + iconSize).toInt()
                )
                paint.alpha = alpha
                canvas.drawBitmap(bmp, null, iconRect, paint)
            }

            // App Label
            textPaint.color = if (isCenter) Color.WHITE else Color.parseColor("#88FFFFFF")
            textPaint.alpha = alpha
            textPaint.textSize = dpToPx(8.5f) * scale
            val cleanName = item.label.take(7)
            canvas.drawText(cleanName, itemX, cy + curCardH / 2 - dpToPx(4) * scale, textPaint)
        }

        // Current Selected Index
        val curCenterIdx = (scrollOffset / stride).roundToInt().coerceIn(0, numItems - 1)
        if (curCenterIdx != lastHapticIndex) {
            lastHapticIndex = curCenterIdx
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        selectedIndex = curCenterIdx

        // 3. Bottom Subtitle (Focused App Title)
        val selectedApp = appItems.getOrNull(selectedIndex)
        if (selectedApp != null) {
            textPaint.color = colorOrange
            textPaint.alpha = 255
            textPaint.textSize = dpToPx(9.5f).toFloat()
            canvas.drawText(selectedApp.label.take(24), cx, height - dpToPx(5).toFloat(), textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = true
                lastTouchX = event.x
                startTouchX = event.x
                startTouchY = event.y
                totalDragDistance = 0f
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    val dx = event.x - lastTouchX
                    val dy = event.y - startTouchY

                    // Swipe Down Gesture -> Dismiss Reel
                    if (dy > dpToPx(28) && abs(dy) > abs(event.x - startTouchX) * 1.5f) {
                        isDragging = false
                        onDismiss()
                        return true
                    }

                    // Horizontal Scrolling
                    scrollOffset -= dx * 1.35f
                    totalDragDistance += abs(dx)
                    lastTouchX = event.x
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                isDragging = false
                val threshold = dpToPx(6).toFloat()

                if (totalDragDistance < threshold) {
                    // Tap event -> Launch tapped item!
                    val cx = width / 2f
                    val tappedIndex = ((event.x - cx + scrollOffset) / stride).roundToInt().coerceIn(0, appItems.size - 1)
                    launchAppAtIndex(tappedIndex)
                } else {
                    // Drag ended -> Snap to closest item and STAY OPEN (do not launch!)
                    val nearestOffset = selectedIndex * stride
                    scrollOffset = nearestOffset
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                isDragging = false
            }
            MotionEvent.ACTION_OUTSIDE -> {
                onDismiss()
            }
        }
        return super.onTouchEvent(event)
    }

    private fun launchAppAtIndex(index: Int) {
        val item = appItems.getOrNull(index)
        if (item != null) {
            try {
                val launchIntent = context.packageManager.getLaunchIntentForPackage(item.packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                } else {
                    Toast.makeText(context, "Cannot launch ${item.label}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to launch app from reel")
            }
        }
        onDismiss()
    }

    private fun dpToPx(dp: Float): Int = (dp * context.resources.displayMetrics.density).toInt()
    private fun dpToPx(dp: Int): Int = (dp * context.resources.displayMetrics.density).toInt()
}
