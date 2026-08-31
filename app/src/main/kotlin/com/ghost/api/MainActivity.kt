package com.ghost.api

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.ghost.api.hardware.HardwareToggleReceiver
import com.ghost.api.services.TTSManager
import com.ghost.api.ui.AudioVisualizerView
import com.ghost.api.ui.EdgeLightsManager
import com.ghost.api.ui.chat.ChatMessage
import com.ghost.api.ui.screens.ChatScreen
import com.ghost.api.ui.theme.GHOSTTheme
import com.ghost.api.ui.viewmodels.ChatViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import timber.log.Timber

class MainActivity : ComponentActivity(), GemmaService.UiCallback {
    
    private val chatViewModel: ChatViewModel by viewModels()
    private lateinit var ttsManager: TTSManager
    
    private var gemmaService: GemmaService? = null
    private var isBound = false
    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Main + job)
    private val handler = Handler(Looper.getMainLooper())
    private var isRitualComplete = false
    
    private var audioVisualizerView: AudioVisualizerView? = null

    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { handlePickedImage(it) }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as GemmaService.LocalBinder
            gemmaService = binder.getService()
            gemmaService?.uiCallback = this@MainActivity
            isBound = true
            scope.launch {
                gemmaService?.isSystemReady?.collect { ready ->
                    if (ready) loadHistoricalChat()
                }
            }
        }
        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            gemmaService = null
        }
    }

    private val ttsStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.ghost.api.ACTION_TTS_START" -> {
                    chatViewModel.setTtsActive(true)
                    audioVisualizerView?.visibility = View.VISIBLE
                    audioVisualizerView?.startAnimating()
                }
                "com.ghost.api.ACTION_TTS_STOP" -> {
                    chatViewModel.setTtsActive(false)
                    audioVisualizerView?.stopAnimating()
                    audioVisualizerView?.visibility = View.GONE
                }
                "com.ghost.api.ACTION_DIARY_ENTRY_POSTED" -> {
                    Timber.i("📔 Diary entry recorded and persisted to storage")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.attributes.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        ttsManager = TTSManager(this)
        
        val filter = android.content.IntentFilter().apply {
            addAction("com.ghost.api.ACTION_TTS_START")
            addAction("com.ghost.api.ACTION_TTS_STOP")
            addAction("com.ghost.api.ACTION_DIARY_ENTRY_POSTED")
        }
        val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) Context.RECEIVER_NOT_EXPORTED else 0
        registerReceiver(ttsStateReceiver, filter, flags)
        
        // Ensure overlay permission
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Overlay permission required", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }

        completeRitual()

        setContent {
            GHOSTTheme {
                val messages by chatViewModel.messages.collectAsState()
                val isThinking by chatViewModel.isThinking.collectAsState()
                val thinkingText by chatViewModel.thinkingText.collectAsState()
                val attachedImage by chatViewModel.attachedImage.collectAsState()
                val isTtsActive by chatViewModel.isTtsActive.collectAsState()

                ChatScreen(
                    messages = messages,
                    isThinking = isThinking,
                    thinkingText = thinkingText,
                    attachedImage = attachedImage,
                    isTtsActive = isTtsActive,
                    onSendMessage = { text ->
                        sendStagedMessage(text)
                    },
                    onSendAudio = { audio ->
                        handleSendAudio(audio)
                    },
                    onPickImage = {
                        imagePicker.launch("image/*")
                    },
                    onClearImage = {
                        chatViewModel.setAttachedImage(null)
                    },
                    onToggleThinking = { message ->
                        // Future reasoning toggle expansion
                    },
                    onOpenSettings = {
                        showSettingsDialog()
                    },
                    visualizerViewFactory = { context ->
                        AudioVisualizerView(context).apply {
                            audioVisualizerView = this
                            visibility = View.GONE
                        }
                    }
                )
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        checkNotificationPermission()
        checkCalendarPermissions()
    }

    private fun completeRitual() {
        if (!isRitualComplete) {
            isRitualComplete = true
            ttsManager.speak("Online.")
            val intent = Intent(this, GemmaService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }
    
    private fun loadHistoricalChat() {
        scope.launch {
            try {
                val history = gemmaService?.getRecentTurns(50) ?: return@launch
                withContext(Dispatchers.Main) {
                    val messages = history.sortedBy { it.timestamp }.flatMap { turn ->
                        val list = mutableListOf<ChatMessage>()
                        if (turn.userMessage.isNotEmpty()) list.add(ChatMessage(turn.userMessage, isFromUser = true, timestamp = turn.timestamp))
                        if (turn.assistantResponse.isNotEmpty()) list.add(ChatMessage(turn.assistantResponse, isFromUser = false, timestamp = turn.timestamp + 1))
                        list
                    }
                    chatViewModel.setMessages(messages)
                }
            } catch (e: Exception) { Timber.e(e) }
        }
    }

    override fun onMessageAdded(message: String, isUser: Boolean, isComplete: Boolean) {
        lifecycleScope.launch(Dispatchers.Main) {
            if (isUser) {
                chatViewModel.addMessage(ChatMessage(message, isFromUser = true))
            } else {
                val current = chatViewModel.messages.value
                val last = current.lastOrNull()
                if (last != null && !last.isFromUser && !last.isComplete) {
                    chatViewModel.updateLastMessage(message)
                } else {
                    chatViewModel.addMessage(ChatMessage(message, isFromUser = false, isComplete = isComplete))
                }
            }
        }
    }

    override fun onThoughtUpdated(thought: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            chatViewModel.setThinking(true, "Processing: $thought")
        }
    }

    override fun onThinkingStateChanged(isThinking: Boolean) {
        lifecycleScope.launch(Dispatchers.Main) {
            val ts = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            chatViewModel.setThinking(isThinking, if (isThinking) "Thinking... $ts" else "")
        }
    }

    private fun sendStagedMessage(text: String) {
        val bitmap = chatViewModel.attachedImage.value
        if (bitmap != null) {
            scope.launch {
                gemmaService?.processMultimodalFromUi(text, listOf(bitmap))
                withContext(Dispatchers.Main) {
                    chatViewModel.setAttachedImage(null)
                    Toast.makeText(this@MainActivity, "Sent with image", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            gemmaService?.processQueryFromUi(text)
            Toast.makeText(this@MainActivity, "Transmission sent", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleSendAudio(audio: ByteArray) {
        scope.launch {
            gemmaService?.processMultimodalFromUi("[Audio message received]", audio = audio)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "Voice audio transmitted", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handlePickedImage(uri: Uri) {
        scope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    val reqWidth = 1024
                    val reqHeight = 1024
                    val options = android.graphics.BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                        contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, this) }
                        
                        val height: Int = outHeight
                        val width: Int = outWidth
                        var inSampleSize = 1
                        if (height > reqHeight || width > reqWidth) {
                            val halfHeight = height / 2
                            val halfWidth = width / 2
                            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                                inSampleSize *= 2
                            }
                        }
                        this.inSampleSize = inSampleSize
                        inJustDecodeBounds = false
                    }
                    contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, options) }
                }
                if (bitmap != null) {
                    withContext(Dispatchers.Main) {
                        chatViewModel.setAttachedImage(bitmap)
                        Toast.makeText(this@MainActivity, "Image staged. Type a prompt and send.", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) { Timber.e(e) }
        }
    }

    private fun showSettingsDialog() {
        val prefs = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
        
        // Root container
        val root = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0A0A0A"))
            setPadding(0, 0, 0, 0)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 32)
        }
        root.addView(container)

        val accentColor = Color.parseColor("#A78BFA")
        val dimTextColor = Color.parseColor("#99FFFFFF")
        val dividerColor = Color.parseColor("#1AFFFFFF")

        // === SECTION: Title ===
        container.addView(TextView(this).apply {
            text = "✧ GHOST Settings"
            textSize = 16f
            setTextColor(accentColor)
            letterSpacing = 0.1f
            setPadding(0, 0, 0, 24)
        })

        // === SECTION: Toggle Switches ===
        fun addToggleRow(label: String, isOn: Boolean, onToggle: (Boolean) -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 16, 0, 16)
            }
            row.addView(TextView(this).apply {
                text = label
                textSize = 14f
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            val switch = Switch(this).apply {
                isChecked = isOn
                thumbTintList = ColorStateList.valueOf(if (isOn) accentColor else Color.parseColor("#555555"))
                trackTintList = ColorStateList.valueOf(if (isOn) Color.parseColor("#4DA78BFA") else Color.parseColor("#333333"))
            }
            switch.setOnCheckedChangeListener { _, checked ->
                onToggle(checked)
                switch.thumbTintList = ColorStateList.valueOf(if (checked) accentColor else Color.parseColor("#555555"))
                switch.trackTintList = ColorStateList.valueOf(if (checked) Color.parseColor("#4DA78BFA") else Color.parseColor("#333333"))
            }
            row.addView(switch)
            container.addView(row)
        }

        // Edge Lights toggle
        addToggleRow("Edge Lights", EdgeLightsManager.isShowing) { checked ->
            if (checked != EdgeLightsManager.isShowing) {
                sendBroadcast(Intent(this, HardwareToggleReceiver::class.java).apply { action = "com.ghost.api.ACTION_TOGGLE_EDGE_LIGHTS" })
            }
        }

        // Passive TTS toggle
        addToggleRow("Passive Notification TTS", prefs.getBoolean(Constants.PREF_PASSIVE_TTS, true)) { checked ->
            prefs.edit().putBoolean(Constants.PREF_PASSIVE_TTS, checked).apply()
            Toast.makeText(this, if (checked) "Passive TTS on" else "Passive TTS off", Toast.LENGTH_SHORT).show()
        }

        // PiP Tool Visibility toggle
        addToggleRow("PiP Tool Visibility", prefs.getBoolean(Constants.PREF_PIP_VISIBILITY, true)) { checked ->
            prefs.edit().putBoolean(Constants.PREF_PIP_VISIBILITY, checked).apply()
            Toast.makeText(this, if (checked) "PiP overlays on" else "PiP overlays off", Toast.LENGTH_SHORT).show()
        }

        // === Divider ===
        container.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).apply { topMargin = 16; bottomMargin = 16 }
            setBackgroundColor(dividerColor)
        })

        // === SECTION: Autonomous Diary Cadence ===
        container.addView(TextView(this).apply {
            text = "Autonomous Diary Cadence"
            textSize = 12f
            setTextColor(dimTextColor)
            letterSpacing = 0.08f
            setPadding(0, 0, 0, 12)
        })

        val currentCadence = prefs.getString(Constants.PREF_DIARY_CADENCE, "12") ?: "12"
        val diaryRadioGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
        }
        val cadences = listOf("1H" to "1", "3H" to "3", "12H" to "12", "OFF" to "OFF")
        for ((label, value) in cadences) {
            diaryRadioGroup.addView(RadioButton(this).apply {
                text = label
                textSize = 12f
                setTextColor(Color.WHITE)
                buttonTintList = ColorStateList.valueOf(accentColor)
                isChecked = (value == currentCadence)
                id = View.generateViewId()
                setPadding(0, 0, 24, 0)
            })
        }
        diaryRadioGroup.setOnCheckedChangeListener { group, checkedId ->
            val checkedRb = group.findViewById<RadioButton>(checkedId)
            val selectedLabel = checkedRb?.text?.toString() ?: "12H"
            val selectedValue = cadences.firstOrNull { it.first == selectedLabel }?.second ?: "12"
            
            prefs.edit()
                .putString(Constants.PREF_DIARY_CADENCE, selectedValue)
                .putBoolean(Constants.PREF_AUTONOMOUS_DIARY, selectedValue != "OFF")
                .apply()
            
            com.ghost.api.workers.DiaryWorker.schedule(this)
            val toastMsg = if (selectedValue == "OFF") "Autonomous diary disabled" else "Diary set to $selectedLabel"
            Toast.makeText(this, toastMsg, Toast.LENGTH_SHORT).show()
        }
        container.addView(diaryRadioGroup)

        // Mute TTS toggle (momentary — stops current speech)
        addToggleRow("Mute TTS", false) { checked ->
            if (checked) {
                GemmaService.instance?.ttsManager?.stop()
            }
            Toast.makeText(this, if (checked) "TTS muted" else "TTS unmuted", Toast.LENGTH_SHORT).show()
        }

        // === Divider ===
        container.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).apply { topMargin = 16; bottomMargin = 16 }
            setBackgroundColor(dividerColor)
        })

        // === SECTION: Wallpapers (action buttons) ===
        fun addActionRow(label: String, onClick: () -> Unit) {
            container.addView(TextView(this).apply {
                text = label
                textSize = 14f
                setTextColor(Color.WHITE)
                setPadding(0, 24, 0, 24)
                setOnClickListener { onClick() }
                setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, android.R.drawable.ic_media_play, 0)
                compoundDrawableTintList = ColorStateList.valueOf(dimTextColor)
                compoundDrawablePadding = 16
            })
        }

        addActionRow("Camera Wallpaper") {
            sendBroadcast(Intent(this, HardwareToggleReceiver::class.java).apply { action = "com.ghost.api.ACTION_SET_CAMERA_WALLPAPER" })
        }
        addActionRow("Avatar Wallpaper") {
            sendBroadcast(Intent(this, HardwareToggleReceiver::class.java).apply { action = "com.ghost.api.ACTION_SET_AVATAR_WALLPAPER" })
        }

        // === Divider ===
        container.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).apply { topMargin = 16; bottomMargin = 16 }
            setBackgroundColor(dividerColor)
        })

        // === SECTION: Backend Selector ===
        container.addView(TextView(this).apply {
            text = "Inference Backend"
            textSize = 12f
            setTextColor(dimTextColor)
            letterSpacing = 0.08f
            setPadding(0, 0, 0, 12)
        })

        val currentBackend = prefs.getString(Constants.PREF_USER_BACKEND, "AUTO") ?: "AUTO"
        val radioGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
        }
        val backends = listOf("AUTO", "CPU", "GPU", "NPU")
        for (backend in backends) {
            radioGroup.addView(RadioButton(this).apply {
                text = backend
                textSize = 12f
                setTextColor(Color.WHITE)
                buttonTintList = ColorStateList.valueOf(accentColor)
                isChecked = (backend == currentBackend)
                id = View.generateViewId()
                setPadding(0, 0, 24, 0)
            })
        }
        radioGroup.setOnCheckedChangeListener { group, checkedId ->
            val selected = group.findViewById<RadioButton>(checkedId)?.text?.toString() ?: "AUTO"
            prefs.edit().putString(Constants.PREF_USER_BACKEND, selected).apply()
            Toast.makeText(this, "Backend set to $selected — takes effect on next restart", Toast.LENGTH_SHORT).show()
        }
        container.addView(radioGroup)

        // === Divider ===
        container.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).apply { topMargin = 16; bottomMargin = 16 }
            setBackgroundColor(dividerColor)
        })

        // === SECTION: Utility Actions ===
        addActionRow("Clear Safe Mode") {
            GemmaService.instance?.resetRecoveryState()
            Toast.makeText(this, "Safe mode cleared — GPU/NPU restored on next restart", Toast.LENGTH_SHORT).show()
        }
        addActionRow("Trigger Diary Log Now") {
            GemmaService.instance?.startDiaryCycle()
            Toast.makeText(this, "Generating diary entry...", Toast.LENGTH_SHORT).show()
        }
        addActionRow("View Diary History") {
            scope.launch {
                val entries = gemmaService?.memoryManager?.getRecentDiaryEntries(25) ?: emptyList()
                withContext(Dispatchers.Main) {
                    showDiaryHistoryDialog(entries)
                }
            }
        }

        // Build and show dialog
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
            .setView(root)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }

    private fun showDiaryHistoryDialog(entries: List<com.ghost.api.database.DiaryEntry>) {
        val root = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0A0A0A"))
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 32)
        }
        root.addView(container)

        // Filter for human-readable diary/dream reflections (skipping legacy JSON dumps)
        val validEntries = entries.filter {
            !it.observation.trim().startsWith("{") && !it.observation.trim().startsWith("Session distilled:")
        }

        container.addView(TextView(this).apply {
            text = "✧ Gemma Diary Logs (${validEntries.size})"
            textSize = 16f
            setTextColor(Color.parseColor("#A78BFA"))
            letterSpacing = 0.1f
            setPadding(0, 0, 0, 24)
        })

        if (validEntries.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "No dream diary entries yet. Tap 'Trigger Diary Log Now' or let the autonomous worker run at noon/midnight."
                textSize = 13f
                setTextColor(Color.parseColor("#99FFFFFF"))
            })
        } else {
            val sdf = SimpleDateFormat("EEEE, MMMM d, yyyy • h:mm a", Locale.US)
            for (entry in validEntries) {
                val dateStr = sdf.format(Date(entry.timestamp))
                val cleanBody = entry.observation
                    .replace(Regex("^✧ Gemma 📔\\s*"), "")
                    .replace(Regex("^✧ Diary Entry:[^\\n]*\\n"), "")
                    .trim()

                // Entry card container
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(32, 24, 32, 24)
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(Color.parseColor("#141414"))
                        cornerRadius = 24f
                        setStroke(1, Color.parseColor("#26FFFFFF"))
                    }
                    val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    params.bottomMargin = 24
                    layoutParams = params
                }

                card.addView(TextView(this).apply {
                    text = "✧ DREAM • $dateStr"
                    textSize = 11f
                    setTextColor(Color.parseColor("#A78BFA"))
                    setPadding(0, 0, 0, 8)
                })

                card.addView(TextView(this).apply {
                    text = cleanBody
                    textSize = 13.5f
                    setTextColor(Color.WHITE)
                    setLineSpacing(6f, 1f)
                })

                container.addView(card)
            }
        }

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
            .setView(root)
            .setPositiveButton("Close", null)
            .create()
            .apply {
                window?.setBackgroundDrawableResource(android.R.color.transparent)
                show()
            }
    }

    private var hasPromptedForNotification = false

    private val calendarPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[android.Manifest.permission.WRITE_CALENDAR] == true
        if (granted) {
            Timber.i("Calendar permissions granted for autonomous diary sync")
        }
    }

    private fun checkCalendarPermissions() {
        if (checkSelfPermission(android.Manifest.permission.WRITE_CALENDAR) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            calendarPermissionLauncher.launch(
                arrayOf(
                    android.Manifest.permission.READ_CALENDAR,
                    android.Manifest.permission.WRITE_CALENDAR
                )
            )
        }
    }

    private fun checkNotificationPermission() {
        val cn = ComponentName(this, GemmaNotificationListener::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        val isEnabled = flat != null && flat.contains(cn.flattenToString())
        
        if (!isEnabled && !hasPromptedForNotification) {
            hasPromptedForNotification = true
            Toast.makeText(this, "Please grant Notification Access for context awareness.", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
        if (isBound) unbindService(serviceConnection)
        ttsManager.shutdown()
        unregisterReceiver(ttsStateReceiver)
    }
}
