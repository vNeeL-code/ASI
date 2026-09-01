package com.ghost.api.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*
import com.ghost.api.Constants
import timber.log.Timber

/**
 * InputOverlay - Minimal voice/text input overlay for agent queries
 * Just an input bar with the ✧ Gemma sparkle button.
 * Tap ✧ to record audio → sends raw audio directly to Gemma (no STT middleman)
 * Type text → sends text query
 *
 * Audio-first design: Gemma 3n is multimodal, so raw audio is more efficient than STT→text→LLM
 */
class InputOverlay(
    context: Context,
    private val onTextQuery: (String) -> Unit,
    private val onAudioQuery: (ByteArray) -> Unit,
    private val onDismiss: () -> Unit,
    private val onFocusRequest: () -> Unit = {}
) : FrameLayout(context) {

    private val inputField: EditText
    private val sparkleButton: TextView
    private lateinit var voiceSendButton: TextView
    private val voiceController: VoiceInputController

    private var isThinkingState = false

    // Colors (still needed for sparkle menu / thinking state)
    // Premium Glassmorphic Palette
    private val colorSurface = Color.parseColor("#CC121212") // Glassy dark
    private val colorSurfaceVariant = Color.parseColor("#33FFFFFF") // Subtle border
    private val colorOnSurface = Color.WHITE
    private val colorAccent = Color.parseColor("#A78BFA")  // Vibrant Purple
    private val colorThinking = Color.parseColor("#F59E0B") // Amber
    private val colorRecording = Color.parseColor("#EF4444")  // Red-Pulse

    // Google
    private val activeSlots = mutableListOf<View>()

    private val colorGBlue = Color.parseColor("#4285F4")
    private val colorGRed = Color.parseColor("#EA4335")
    private val colorGYellow = Color.parseColor("#FBBC05")
    private val colorGGreen = Color.parseColor("#34A853")
    private val colorOrange = Color.parseColor("#F97316")
    private val colorCyan = Color.parseColor("#00F0FF")

    private val prefs = context.getSharedPreferences("Gemma_RadialPrefs", Context.MODE_PRIVATE)
    private var appPickerLayout: View? = null

    init {
        // Main Frame size (expanded for 6-point radial hexagon/diamond)
        clipChildren = false
        clipToPadding = false
        layoutParams = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dpToPx(480) 
        ).apply {
            gravity = Gravity.CENTER
        }

        // Main Bar (Horizontal)
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = createBarBackground()
            elevation = dpToPx(4).toFloat()
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dpToPx(52)
            ).apply {
                gravity = Gravity.CENTER
                marginStart = dpToPx(16)
                marginEnd = dpToPx(16)
            }
            setPadding(dpToPx(8), dpToPx(4), dpToPx(12), dpToPx(4))
        }

        // ✧ Sparkle button — Tap to open app, HOLD for slots
        sparkleButton = TextView(context).apply {
            text = "\u2727"
            textSize = 28f
            setTextColor(colorAccent)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dpToPx(44), dpToPx(44))
            background = createCircleBackground(Color.TRANSPARENT)
            
            var isSwiping = false
            var startX = 0f
            var startY = 0f
            var wasLongClicked = false
            
            setOnClickListener {
                val intent = android.content.Intent(context, com.ghost.api.MainActivity::class.java).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(intent)
                onDismiss()
            }
            
            setOnLongClickListener {
                if (!isSwiping) {
                    wasLongClicked = true
                    hapticPulse()
                    expandSparkleSubMenu(this)
                }
                true
            }
            
            setOnTouchListener { v, event ->
                val threshold = 50f
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.rawX
                        startY = event.rawY
                        isSwiping = false
                        wasLongClicked = false
                        v.animate().scaleX(1.2f).scaleY(1.2f).setDuration(100).start()
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (Math.abs(event.rawX - startX) > threshold || Math.abs(event.rawY - startY) > threshold) {
                            isSwiping = true
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                        if (wasLongClicked) {
                            wasLongClicked = false // consume the up event
                        } else if (isSwiping) {
                            val dy = event.rawY - startY
                            val direction = if (dy > 0) "DOWN" else "UP"
                            launchBoundApp("Sparkle", direction)
                        } else {
                            v.performClick()
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                        wasLongClicked = false
                    }
                }
                false // Let long click fire if hold
            }
        }

        // Text input
        inputField = EditText(context).apply {
            hint = "Δ \uD83D\uDC7E ∇"
            setTextColor(colorOnSurface)
            setHintTextColor(Color.parseColor("#66FFFFFF"))
            setBackgroundColor(Color.TRANSPARENT)
            textSize = 16f
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEND
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dpToPx(8)
                marginEnd = dpToPx(8)
            }

            setOnClickListener { 
                onFocusRequest() 
                requestFocus()
            }
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) onFocusRequest()
            }

            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    voiceController.handleTap()
                    true
                } else false
            }
        }

        // Voice / Send button — wired to VoiceInputController
        voiceSendButton = TextView(context).apply {
            text = "🟣"
            textSize = 20f
            setTextColor(colorAccent)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dpToPx(36), dpToPx(36))
            visibility = VISIBLE
        }

        // Build controller AFTER voiceSendButton and inputField exist
        // (assigned to val so it can be referenced in cleanup)
        voiceController = VoiceInputController(
            context = context,
            micButton = voiceSendButton,
            inputField = inputField,
            sparkleOrNull = sparkleButton,
            onAudioReady = { audio ->
                onAudioQuery(audio)
                onDismiss()
            },
            onTextReady = { text ->
                setThinking(true)
                dismissSubMenus()
                onTextQuery(text)
            }
        )

        bar.addView(sparkleButton)
        bar.addView(inputField)
        bar.addView(voiceSendButton)

        // Tap outside to dismiss (Escape Hatch)
        setOnClickListener { 
            Timber.i("InputOverlay: Outside tap detected - dismissing")
            dismissSubMenus()
            onDismiss() 
        }

        addView(bar)

        // Add Radial Buttons (Hexagon layout)
        addRadialButtons()
    }

    private fun addRadialButtons() {
        val horizontalOffset = dpToPx(90)
        val verticalOffset = dpToPx(105)
        val apexVerticalOffset = dpToPx(182) // Pointy rocket apex tips

        // 1. Top Apex Vertex: Orange (CS:GO Tape Reel Launcher)
        setupTopOrangeButton(0f, -apexVerticalOffset.toFloat())

        // 2. Bottom Apex Vertex: Cyan (4-Way Media & Sticky Scratchpad Puck)
        setupBottomCyanPuck(0f, apexVerticalOffset.toFloat())

        // 3. Four Diagonal Google Nodes
        setupRadialButton(colorGRed, -horizontalOffset.toFloat(), -verticalOffset.toFloat(), "Red (Camera)")
        setupRadialButton(colorGBlue, horizontalOffset.toFloat(), -verticalOffset.toFloat(), "Blue (Search)")
        setupRadialButton(colorGGreen, -horizontalOffset.toFloat(), verticalOffset.toFloat(), "Green (Diary)")
        setupRadialButton(colorGYellow, horizontalOffset.toFloat(), verticalOffset.toFloat(), "Yellow (Tools)")
        
        // Ensure parent FrameLayout doesn't block children
        isClickable = false
        isFocusable = false
    }

    private fun createBaseSocket(color: Int, tx: Float, ty: Float): TextView {
        val socketSize = dpToPx(44)
        return TextView(context).apply {
            text = "+"
            textSize = 14f
            setTextColor(Color.argb(180, 255, 255, 255))
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#1C1C24"))
                setStroke(dpToPx(1), Color.argb(110, Color.red(color), Color.green(color), Color.blue(color)))
            }
            translationX = tx
            translationY = ty
            alpha = 0.5f
            layoutParams = LayoutParams(socketSize, socketSize).apply {
                gravity = Gravity.CENTER
            }
        }
    }

    private fun triggerSocketMagneticSparkle(socket: View) {
        socket.animate()
            .scaleX(1.25f)
            .scaleY(1.25f)
            .alpha(1.0f)
            .setDuration(80)
            .withEndAction {
                socket.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .alpha(0.5f)
                    .setDuration(160)
                    .start()
            }
            .start()
    }

    private fun applyPuckWiggle(view: View, baseTx: Float, baseTy: Float, startX: Float, startY: Float, rawX: Float, rawY: Float) {
        val rawDx = rawX - startX
        val rawDy = rawY - startY
        val dist = Math.hypot(rawDx.toDouble(), rawDy.toDouble()).toFloat()
        if (dist == 0f) return
        val maxRadius = dpToPx(24).toFloat()
        val clampedDist = Math.min(dist * 0.5f, maxRadius)
        val factor = clampedDist / dist
        view.translationX = baseTx + (rawDx * factor)
        view.translationY = baseTy + (rawDy * factor)
    }

    private fun resetPuckSpring(view: View, baseTx: Float, baseTy: Float) {
        view.animate()
            .translationX(baseTx)
            .translationY(baseTy)
            .scaleX(1.0f)
            .scaleY(1.0f)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.8f))
            .setDuration(220)
            .start()
    }

    private fun setupTopOrangeButton(tx: Float, ty: Float) {
        val socket = createBaseSocket(colorOrange, tx, ty)
        addView(socket)

        val btnSize = dpToPx(48)
        val btn = TextView(context).apply {
            text = "✧"
            textSize = 24f
            setTextColor(colorOrange)
            gravity = Gravity.CENTER
            background = createCircleBackground(colorSurface)
            elevation = dpToPx(6).toFloat()
            translationX = tx
            translationY = ty
            layoutParams = LayoutParams(btnSize, btnSize).apply {
                gravity = Gravity.CENTER
            }

            var startX = 0f
            var startY = 0f
            var lastRawX = 0f
            var isSwiping = false
            var isInDeadzone = true

            setOnTouchListener { v, event ->
                val threshold = dpToPx(18).toFloat()
                val overlayMgr = com.ghost.api.GemmaService.instance?.overlayManager
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.rawX
                        startY = event.rawY
                        lastRawX = event.rawX
                        isSwiping = false
                        isInDeadzone = true
                        v.animate().scaleX(1.15f).scaleY(1.15f).setDuration(100).start()
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        applyPuckWiggle(v, tx, ty, startX, startY, event.rawX, event.rawY)
                        val totalDist = Math.hypot((event.rawX - startX).toDouble(), (event.rawY - startY).toDouble()).toFloat()

                        // Magnetic detent state transitions
                        if (isInDeadzone && totalDist >= threshold) {
                            isInDeadzone = false
                            isSwiping = true
                            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        } else if (!isInDeadzone && totalDist < threshold) {
                            isInDeadzone = true
                            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            triggerSocketMagneticSparkle(socket)
                        }

                        // Analog velocity continuous joystick spin on horizontal thumb hold
                        if (overlayMgr?.isAppReelVisible() == true) {
                            val dx = event.rawX - startX
                            val dy = event.rawY - startY
                            if (Math.abs(dx) > threshold && Math.abs(dx) > Math.abs(dy) * 0.7f) {
                                val deflection = dx - (Math.signum(dx) * threshold)
                                val norm = (deflection / dpToPx(24).toFloat()).coerceIn(-1.5f, 1.5f)
                                val speed = -Math.signum(norm) * Math.pow(Math.abs(norm).toDouble(), 1.4).toFloat() * dpToPx(9).toFloat()
                                overlayMgr.setAppReelJoystickVelocity(speed)
                            } else {
                                overlayMgr.setAppReelJoystickVelocity(0f)
                            }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        overlayMgr?.setAppReelJoystickVelocity(0f)
                        resetPuckSpring(v, tx, ty)
                        val dx = event.rawX - startX
                        val dy = event.rawY - startY
                        val totalDist = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()

                        if (totalDist >= threshold) {
                            if (Math.abs(dy) > Math.abs(dx)) {
                                if (dy > 0) {
                                    // Pull DOWN -> Summon / Dismiss App Reel
                                    hapticPulse()
                                    if (overlayMgr?.isAppReelVisible() == true) {
                                        overlayMgr.hideAppReel()
                                    } else {
                                        overlayMgr?.showAppReel()
                                    }
                                } else {
                                    // Push UP -> Remote Confirm / Launch selected item in App Reel
                                    hapticPulse()
                                    if (overlayMgr?.isAppReelVisible() == true) {
                                        overlayMgr.launchAppReelSelected()
                                    } else {
                                        // If Reel wasn't open, open it
                                        overlayMgr?.showAppReel()
                                    }
                                }
                            }
                            // If horizontal drag (LEFT / RIGHT) -> already spun via joystick, release stays open at rest!
                        } else {
                            // Released in deadzone / neutral -> Safe zero state + Magnetic snap sparkle!
                            triggerSocketMagneticSparkle(socket)
                            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        overlayMgr?.setAppReelJoystickVelocity(0f)
                        resetPuckSpring(v, tx, ty)
                        true
                    }
                    else -> false
                }
            }
        }
        addView(btn)
    }

    private fun setupBottomCyanPuck(tx: Float, ty: Float) {
        val socket = createBaseSocket(colorCyan, tx, ty)
        addView(socket)

        val btnSize = dpToPx(48)
        val btn = TextView(context).apply {
            text = "✧"
            textSize = 24f
            setTextColor(colorCyan)
            gravity = Gravity.CENTER
            background = createCircleBackground(colorSurface)
            elevation = dpToPx(6).toFloat()
            translationX = tx
            translationY = ty
            layoutParams = LayoutParams(btnSize, btnSize).apply {
                gravity = Gravity.CENTER
            }

            var startX = 0f
            var startY = 0f
            var isSwiping = false
            var isInDeadzone = true

            setOnTouchListener { v, event ->
                val threshold = dpToPx(18).toFloat()
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.rawX
                        startY = event.rawY
                        isSwiping = false
                        isInDeadzone = true
                        v.animate().scaleX(1.2f).scaleY(1.2f).setDuration(100).start()
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        applyPuckWiggle(v, tx, ty, startX, startY, event.rawX, event.rawY)
                        val totalDist = Math.hypot((event.rawX - startX).toDouble(), (event.rawY - startY).toDouble()).toFloat()

                        if (isInDeadzone && totalDist >= threshold) {
                            isInDeadzone = false
                            isSwiping = true
                            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        } else if (!isInDeadzone && totalDist < threshold) {
                            isInDeadzone = true
                            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            triggerSocketMagneticSparkle(socket)
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        resetPuckSpring(v, tx, ty)
                        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                        val dx = event.rawX - startX
                        val dy = event.rawY - startY
                        val totalDist = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()

                        if (totalDist >= threshold) {
                            if (Math.abs(dx) > Math.abs(dy)) {
                                if (dx > 0) {
                                    // Right -> Next Track
                                    hapticPulse()
                                    audioManager?.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_NEXT))
                                    audioManager?.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_NEXT))
                                    Toast.makeText(context, "⏭ Next Track", Toast.LENGTH_SHORT).show()
                                } else {
                                    // Left -> Previous Track
                                    hapticPulse()
                                    audioManager?.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS))
                                    audioManager?.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS))
                                    Toast.makeText(context, "⏮ Previous Track", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                if (dy > 0) {
                                    // Down -> Play/Pause
                                    hapticPulse()
                                    audioManager?.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                                    audioManager?.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                                    Toast.makeText(context, "⏯ Play / Pause", Toast.LENGTH_SHORT).show()
                                } else {
                                    // Up -> Summon Scratchpad PiP
                                    hapticPulse()
                                    com.ghost.api.GemmaService.instance?.overlayManager?.showScratchpad()
                                }
                            }
                        } else {
                            // Released in deadzone -> Magnetic snap sparkle & neutral cancel!
                            triggerSocketMagneticSparkle(socket)
                            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        resetPuckSpring(v, tx, ty)
                        true
                    }
                    else -> false
                }
            }
        }
        addView(btn)
    }

    private fun setupRadialButton(color: Int, tx: Float, ty: Float, label: String) {
        val socket = createBaseSocket(color, tx, ty)
        addView(socket)

        val btnSize = dpToPx(48)
        val btn = TextView(context).apply {
            text = "✧"
            textSize = 24f
            setTextColor(color)
            gravity = Gravity.CENTER
            background = createCircleBackground(colorSurface)
            elevation = dpToPx(6).toFloat()
            translationX = tx
            translationY = ty
            layoutParams = LayoutParams(btnSize, btnSize).apply {
                gravity = Gravity.CENTER
            }
            
            var startX = 0f
            var startY = 0f
            var isSwiping = false
            var wasLongClicked = false
            var isInDeadzone = true

            // Interaction: Pullable logic (Hold to expand)
            setOnLongClickListener {
                if (!isSwiping) {
                    wasLongClicked = true
                    hapticPulse()
                    expandSubMenu(this, tx, ty, label)
                }
                true
            }

            setOnTouchListener { v, event ->
                val deadzoneThreshold = dpToPx(18).toFloat()
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        startX = event.rawX
                        startY = event.rawY
                        isSwiping = false
                        wasLongClicked = false
                        isInDeadzone = true
                        v.animate().scaleX(1.2f).scaleY(1.2f).setDuration(100).start()
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        applyPuckWiggle(v, tx, ty, startX, startY, event.rawX, event.rawY)
                        val totalDist = Math.hypot((event.rawX - startX).toDouble(), (event.rawY - startY).toDouble()).toFloat()

                        if (isInDeadzone && totalDist >= deadzoneThreshold) {
                            isInDeadzone = false
                            isSwiping = true
                            v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        } else if (!isInDeadzone && totalDist < deadzoneThreshold) {
                            isInDeadzone = true
                            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            triggerSocketMagneticSparkle(socket)
                        }
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        resetPuckSpring(v, tx, ty)
                        val dx = event.rawX - startX
                        val dy = event.rawY - startY
                        val totalDist = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()

                        if (wasLongClicked) {
                            wasLongClicked = false
                        } else if (totalDist >= deadzoneThreshold) {
                            // Intentional directional flick outside deadzone -> launch vector gate
                            val direction = if (Math.abs(dx) > Math.abs(dy)) {
                                if (dx > 0) "RIGHT" else "LEFT"
                            } else {
                                if (dy > 0) "DOWN" else "UP"
                            }
                            launchBoundApp(label, direction)
                        } else {
                            // Released in center deadzone / neutral -> Zero action + Magnetic snap spark!
                            triggerSocketMagneticSparkle(socket)
                            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            Timber.d("Puck $label released in deadzone ($totalDist < $deadzoneThreshold) - neutral cancel")
                        }
                    }
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        resetPuckSpring(v, tx, ty)
                        wasLongClicked = false
                    }
                }
                false // Let long click fire if hold
            }
        }
        addView(btn)
    }

    private fun launchBoundApp(label: String, direction: String) {
        if (label.contains("Orange") && direction == "DOWN") {
            hapticPulse()
            com.ghost.api.GemmaService.instance?.overlayManager?.showAppReel()
            return
        }

        val boundPackage = prefs.getString("BIND_${label}_${direction}", null)
        
        // Audit 9.0: Agentic Shortcuts
        // If not bound to an app, or bound to a /command, trigger Gemma directly
        val command = when {
            boundPackage?.startsWith("/") == true -> boundPackage
            boundPackage == null && label.contains("Red") -> "/scan"
            boundPackage == null && label.contains("Blue") -> "/search"
            boundPackage == null && label.contains("Green") -> "/diary"
            boundPackage == null && label.contains("Yellow") -> "/tools"
            boundPackage == null && label.contains("Orange") && direction == "UP" -> {
                // Default unassigned UP slot on Orange: Toggle Flashlight!
                hapticPulse()
                com.ghost.api.hardware.HardwareToolSet(context).flashlight("TOGGLE")
                return
            }
            boundPackage == null && label.contains("Orange") && direction == "TAP" -> {
                // Default unassigned TAP on Orange: System Settings!
                hapticPulse()
                try {
                    val intent = android.content.Intent(android.provider.Settings.ACTION_SETTINGS).apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    onDismiss()
                } catch (e: Exception) {
                    Timber.e(e, "Failed to open Settings")
                }
                return
            }
            else -> null
        }

        if (command != null) {
            hapticPulse()
            onTextQuery(command)
            return
        }

        if (boundPackage != null) {
            try {
                val pm = context.packageManager
                val intent = pm.getLaunchIntentForPackage(boundPackage)?.apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (intent != null) {
                    context.startActivity(intent)
                    onDismiss()
                }
            } catch (e: Exception) { 
                Timber.e(e, "Failed to launch bound app: $boundPackage")
            }
        }
    }

    private fun expandSubMenu(parent: View, px: Float, py: Float, parentLabel: String) {
        Timber.i("Radial: Expanding submenu for $parentLabel")
        // Visual feedback: Scale up parent even more
        parent.animate().scaleX(1.4f).scaleY(1.4f).setDuration(250).start()
        
        // Add 4 mini-slots around the button (North, South, East, West)
        val parentGroup = this@InputOverlay
        val slotSize = dpToPx(32)
        val distance = dpToPx(60)

        // Calculate center of parent relative to this overlay
        val parentLoc = IntArray(2)
        parent.getLocationInWindow(parentLoc)
        val overlayLoc = IntArray(2)
        this@InputOverlay.getLocationInWindow(overlayLoc)
        
        val centerX = (parentLoc[0] - overlayLoc[0]) + parent.width / 2f
        val centerY = (parentLoc[1] - overlayLoc[1]) + parent.height / 2f

        val slots = listOf(
            Pair(0f, -distance.toFloat()), // North
            Pair(0f, distance.toFloat()),  // South
            Pair(-distance.toFloat(), 0f), // West
            Pair(distance.toFloat(), 0f)   // East
        )

        val directions = listOf("UP", "DOWN", "LEFT", "RIGHT")

        slots.forEachIndexed { index, pos ->
            val isOrangeDown = parentLabel.contains("Orange") && directions[index] == "DOWN"
            val boundPkg = prefs.getString("BIND_${parentLabel}_${directions[index]}", null)

            val slot = TextView(context).apply {
                text = when {
                    isOrangeDown -> "📱"
                    boundPkg != null -> boundPkg.split('.').lastOrNull()?.take(2)?.uppercase() ?: "+"
                    parentLabel.contains("Orange") && directions[index] == "UP" -> "🔦"
                    else -> "+"
                }
                textSize = if (isOrangeDown || text.length > 1) 11f else 14f
                setTextColor(if (isOrangeDown) colorOrange else if (boundPkg != null) Color.WHITE else Color.GRAY)
                gravity = Gravity.CENTER
                background = createCircleBackground(if (isOrangeDown) Color.parseColor("#4D1C1C22") else Color.parseColor("#3D3D3D"))
                
                layoutParams = LayoutParams(slotSize, slotSize).apply {
                    gravity = Gravity.TOP or Gravity.START
                }
                
                x = centerX - slotSize / 2f + pos.first
                y = centerY - slotSize / 2f + pos.second
                
                alpha = 0f
                scaleX = 0f
                scaleY = 0f
                
                setOnClickListener {
                    hapticPulse()
                    if (isOrangeDown) {
                        Toast.makeText(context, "Slot dedicated to App Drawer Reel", Toast.LENGTH_SHORT).show()
                    } else {
                        showAppPicker(parentLabel, directions[index])
                    }
                }
            }
            parentGroup.addView(slot)
            activeSlots.add(slot)
            slot.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(300).setStartDelay((index * 50).toLong()).start()
        }
    }

    private fun expandSparkleSubMenu(parent: View) {
        Timber.i("Radial: Expanding submenu for Sparkle")
        parent.animate().scaleX(1.4f).scaleY(1.4f).setDuration(250).start()
        
        val parentGroup = this@InputOverlay
        val slotSize = dpToPx(32)
        val distance = dpToPx(60)

        // Only UP and DOWN for sparkle
        val slots = listOf(
            Pair(0f, -distance.toFloat()), // UP
            Pair(0f, distance.toFloat())   // DOWN
        )
        val directions = listOf("UP", "DOWN")

        val parentLoc = IntArray(2)
        parent.getLocationInWindow(parentLoc)
        val overlayLoc = IntArray(2)
        this@InputOverlay.getLocationInWindow(overlayLoc)
        
        val centerX = (parentLoc[0] - overlayLoc[0]) + parent.width / 2f
        val centerY = (parentLoc[1] - overlayLoc[1]) + parent.height / 2f

        slots.forEachIndexed { index, pos ->
            val slot = TextView(context).apply {
                text = "+"
                textSize = 14f
                setTextColor(Color.GRAY)
                gravity = Gravity.CENTER
                background = createCircleBackground(Color.parseColor("#3D3D3D"))
                
                layoutParams = LayoutParams(slotSize, slotSize).apply {
                    gravity = Gravity.TOP or Gravity.START
                }
                
                x = centerX - slotSize / 2f + pos.first
                y = centerY - slotSize / 2f + pos.second
                
                alpha = 0f
                scaleX = 0f
                scaleY = 0f
                
                setOnClickListener {
                    hapticPulse()
                    showAppPicker("Sparkle", directions[index])
                }
            }
            parentGroup.addView(slot)
            activeSlots.add(slot)
            slot.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(300).setStartDelay((index * 50).toLong()).start()
        }
    }

    private fun dismissSubMenus() {
        activeSlots.forEach { it.animate().alpha(0f).scaleX(0f).scaleY(0f).setDuration(200).withEndAction { removeView(it) }.start() }
        activeSlots.clear()
        
        appPickerLayout?.let { removeView(it); appPickerLayout = null }
        
        // Also reset main buttons scale
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            if (v is TextView && v.text == "✧") {
                v.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
            }
        }
    }

    private fun showAppPicker(parentLabel: String, direction: String) {
        if (appPickerLayout != null) removeView(appPickerLayout)
        
        val pm = context.packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN, null).apply { addCategory(android.content.Intent.CATEGORY_LAUNCHER) }
        val resolveInfos = pm.queryIntentActivities(intent, 0).sortedBy { it.loadLabel(pm).toString() }
        
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#E61E1E1E"))
            layoutParams = LayoutParams(dpToPx(240), dpToPx(300)).apply {
                gravity = Gravity.CENTER
            }
            elevation = dpToPx(16).toFloat()
        }
        
        val title = TextView(context).apply {
            text = "Bind App to $direction swipe"
            setTextColor(Color.WHITE)
            setPadding(16, 16, 16, 16)
            setBackgroundColor(Color.parseColor("#333333"))
        }
        container.addView(title)
        
        val scrollView = ScrollView(context)
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        
        for (info in resolveInfos) {
            val appName = info.loadLabel(pm).toString()
            val pkgName = info.activityInfo.packageName
            val item = TextView(context).apply {
                text = appName
                setTextColor(Color.LTGRAY)
                setPadding(32, 24, 32, 24)
                setOnClickListener {
                    prefs.edit().putString("BIND_${parentLabel}_${direction}", pkgName).apply()
                    dismissSubMenus()
                }
            }
            list.addView(item)
            val divider = View(context).apply { setBackgroundColor(Color.DKGRAY); layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 1) }
            list.addView(divider)
        }
        
        scrollView.addView(list)
        container.addView(scrollView)
        
        appPickerLayout = container
        addView(container)
    }


    // Haptic pulse — used by sparkle and radial button interactions
    private fun hapticPulse() {
        try {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator?.vibrate(android.os.VibrationEffect.createOneShot(50, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(50)
            }
        } catch (e: Exception) {
            Timber.w(e, "Haptic failed")
        }
    }

    fun setThinking(thinking: Boolean) {
        isThinkingState = thinking
        voiceController.setThinking(thinking)
        if (thinking) {
            sparkleButton.setTextColor(Color.parseColor("#F59E0B")) // Amber
        } else {
            sparkleButton.setTextColor(colorAccent)
        }
    }

    fun setLoading(loading: Boolean) {
        if (loading) {
            inputField.isEnabled = false
            inputField.hint = "Loading Engine (1m)..."
            sparkleButton.setTextColor(Color.GRAY)
            sparkleButton.alpha = 0.5f
        } else {
            inputField.isEnabled = true
            inputField.hint = "Ask GHOST..."
            sparkleButton.setTextColor(colorAccent)
            sparkleButton.alpha = 1f
        }
    }



    fun focusInput() {
        inputField.requestFocus()
    }

    fun appendText(text: String) {
        inputField.append(text)
    }

    fun cleanup() {
        voiceController.cleanup()
    }

    // === Drawing helpers ===

    private fun createBarBackground(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(26).toFloat()
            setColor(colorSurface)
            setStroke(dpToPx(1), colorSurfaceVariant)
        }
    }

    private fun createCircleBackground(bgColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(bgColor)
            // Removed setStroke to eliminate the white ring outlines on transparent buttons
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }
}
