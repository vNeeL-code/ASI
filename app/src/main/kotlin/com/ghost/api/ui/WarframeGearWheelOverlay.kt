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
 * WarframeGearWheelOverlay - Infinite radial/spiral gearwheel launcher.
 * Fans out when touching the Top Orange Apex node.
 * Features inertial angular momentum, haptic tick feedback, and instant app launching.
 */
@SuppressLint("ViewConstructor")
class WarframeGearWheelOverlay(
    context: Context,
    private val windowManager: WindowManager,
    private val onDismiss: () -> Unit
) : FrameLayout(context) {

    data class AppGearItem(
        val label: String,
        val packageName: String,
        val iconBitmap: Bitmap?
    )

    private val appItems = mutableListOf<AppGearItem>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gearPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var rotationAngle = 0f
    private var targetRotationAngle = 0f
    private var lastTouchAngle = 0f
    private var isDragging = false
    private var selectedIndex = 0
    private var lastHapticIndex = -1

    private val colorOrange = Color.parseColor("#F97316")
    private val colorAccent = Color.parseColor("#A78BFA")
    private val colorGreen = Color.parseColor("#22C55E")

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

        windowParams = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        )

        // Text paint for center HUD
        textPaint.apply {
            color = Color.WHITE
            textSize = dpToPx(14).toFloat()
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        gearPaint.apply {
            style = Paint.Style.STROKE
            strokeWidth = dpToPx(3).toFloat()
            color = colorOrange
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
                appItems.add(AppGearItem(label, pkg, bmp))
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to load apps for gearwheel")
        }
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap? {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return drawable.bitmap
        }
        val size = dpToPx(40).coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height * 0.38f // Anchored near the top apex horizon

        // Dim background
        canvas.drawColor(Color.parseColor("#D9050508"))

        if (appItems.isEmpty()) {
            canvas.drawText("Loading gear items...", cx, cy, textPaint)
            return
        }

        val baseRadius = dpToPx(130).toFloat()
        val numItems = appItems.size.coerceAtMost(36)
        val angleStep = (2.0 * Math.PI / numItems).toFloat()

        // 1. Draw Outer Glowing Gear Teeth
        val gearRadius = baseRadius + dpToPx(28)
        val numTeeth = 32
        for (i in 0 until numTeeth) {
            val toothAngle = (i * 2.0 * Math.PI / numTeeth).toFloat() + (rotationAngle * 0.015f)
            val tx1 = cx + (gearRadius - dpToPx(6)) * cos(toothAngle)
            val ty1 = cy + (gearRadius - dpToPx(6)) * sin(toothAngle)
            val tx2 = cx + (gearRadius + dpToPx(6)) * cos(toothAngle)
            val ty2 = cy + (gearRadius + dpToPx(6)) * sin(toothAngle)
            canvas.drawLine(tx1, ty1, tx2, ty2, gearPaint)
        }
        canvas.drawCircle(cx, cy, gearRadius, gearPaint)

        // 2. Draw Center Core Hub
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#1C1917")
        canvas.drawCircle(cx, cy, dpToPx(55).toFloat(), paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dpToPx(2).toFloat()
        paint.color = colorOrange
        canvas.drawCircle(cx, cy, dpToPx(55).toFloat(), paint)

        // 3. Draw Radial App Nodes along the spiral/ring
        var closestIdx = 0
        var minDiff = Float.MAX_VALUE

        for (i in 0 until numItems) {
            val itemAngle = (i * angleStep) + rotationAngle
            val ix = cx + baseRadius * cos(itemAngle)
            val iy = cy + baseRadius * sin(itemAngle)

            // Check which item is closest to top (angle = -PI/2)
            val normalizedAngle = ((itemAngle + Math.PI / 2.0) % (2.0 * Math.PI) + 2.0 * Math.PI) % (2.0 * Math.PI)
            val diff = min(normalizedAngle, 2.0 * Math.PI - normalizedAngle).toFloat()
            if (diff < minDiff) {
                minDiff = diff
                closestIdx = i
            }

            val isSelected = (i == closestIdx && diff < 0.35f)
            val item = appItems[i]

            // Slot Background Circle
            val slotRadius = if (isSelected) dpToPx(24).toFloat() else dpToPx(19).toFloat()
            paint.style = Paint.Style.FILL
            paint.color = if (isSelected) colorOrange else Color.parseColor("#262626")
            canvas.drawCircle(ix, iy, slotRadius, paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dpToPx(1.5f).toFloat()
            paint.color = if (isSelected) Color.WHITE else Color.parseColor("#44FFFFFF")
            canvas.drawCircle(ix, iy, slotRadius, paint)

            // Draw Icon
            item.iconBitmap?.let { bmp ->
                val iconSize = if (isSelected) dpToPx(28) else dpToPx(22)
                val src = Rect(0, 0, bmp.width, bmp.height)
                val dst = Rect((ix - iconSize / 2).toInt(), (iy - iconSize / 2).toInt(), (ix + iconSize / 2).toInt(), (iy + iconSize / 2).toInt())
                canvas.drawBitmap(bmp, src, dst, null)
            }
        }

        selectedIndex = closestIdx
        if (selectedIndex != lastHapticIndex && minDiff < 0.25f) {
            lastHapticIndex = selectedIndex
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }

        // 4. Draw Selected App Details in the Core Hub
        val selectedApp = appItems.getOrNull(selectedIndex)
        if (selectedApp != null) {
            textPaint.color = colorGreen
            textPaint.textSize = dpToPx(12).toFloat()
            canvas.drawText("Δ GEAR ∇", cx, cy - dpToPx(16), textPaint)

            textPaint.color = Color.WHITE
            textPaint.textSize = dpToPx(11).toFloat()
            val cleanLabel = selectedApp.label.take(14)
            canvas.drawText(cleanLabel, cx, cy + dpToPx(4), textPaint)

            textPaint.color = colorOrange
            textPaint.textSize = dpToPx(9).toFloat()
            canvas.drawText("TAP TO LAUNCH", cx, cy + dpToPx(20), textPaint)
        }

        // 5. Draw Top Focus Pointer Arrow
        val arrowPath = Path().apply {
            moveTo(cx, cy - gearRadius - dpToPx(14))
            lineTo(cx - dpToPx(8), cy - gearRadius - dpToPx(24))
            lineTo(cx + dpToPx(8), cy - gearRadius - dpToPx(24))
            close()
        }
        paint.style = Paint.Style.FILL
        paint.color = colorOrange
        canvas.drawPath(arrowPath, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val cx = width / 2f
        val cy = height * 0.38f
        val dx = event.x - cx
        val dy = event.y - cy
        val touchAngle = atan2(dy.toDouble(), dx.toDouble()).toFloat()
        val distFromCenter = hypot(dx.toDouble(), dy.toDouble()).toFloat()

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = true
                lastTouchAngle = touchAngle
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    var angleDelta = touchAngle - lastTouchAngle
                    // Handle wrap around -PI to PI
                    if (angleDelta > Math.PI) angleDelta -= (2 * Math.PI).toFloat()
                    if (angleDelta < -Math.PI) angleDelta += (2 * Math.PI).toFloat()

                    rotationAngle += angleDelta
                    lastTouchAngle = touchAngle
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                isDragging = false
                // If tapped center or released on pointer
                if (distFromCenter < dpToPx(65) || hypot(event.x - cx, event.y - (cy - dpToPx(130))).toFloat() < dpToPx(50)) {
                    launchSelectedApp()
                } else if (distFromCenter > dpToPx(220)) {
                    // Tap outside dismisses
                    onDismiss()
                } else {
                    launchSelectedApp()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                onDismiss()
            }
        }
        return super.onTouchEvent(event)
    }

    private fun launchSelectedApp() {
        val item = appItems.getOrNull(selectedIndex)
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
                Timber.e(e, "Failed to launch app from gearwheel")
            }
        }
        onDismiss()
    }

    private fun dpToPx(dp: Float): Int = (dp * context.resources.displayMetrics.density).toInt()
    private fun dpToPx(dp: Int): Int = (dp * context.resources.displayMetrics.density).toInt()
}
