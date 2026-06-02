package com.ghost.api

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.net.Uri
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import timber.log.Timber
import com.ghost.api.services.TTSManager
import com.ghost.api.ui.chat.ChatAdapter
import com.ghost.api.ui.chat.ChatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.activity.ComponentActivity
import java.io.File
import com.ghost.api.database.ConversationTurn

class MainActivity : ComponentActivity(), GemmaService.UiCallback {
    
    private lateinit var statusTextView: TextView
    private lateinit var actionButton: Button
    
    private var chatRecyclerView: RecyclerView? = null
    private var chatInputText: EditText? = null
    private var btnSend: TextView? = null
    private var thinkingProgress: ProgressBar? = null
    private var thinkingText: TextView? = null
    private var visualizer: com.ghost.api.ui.AudioVisualizerView? = null
    
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var ttsManager: TTSManager
    private var voiceController: com.ghost.api.ui.VoiceInputController? = null
    
    private var gemmaService: GemmaService? = null
    private var isBound = false
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val handler = Handler(Looper.getMainLooper())
    private var isRitualComplete = false
    private var isShowingDiary = false
    
    // Holds the picked image until the user types a prompt and hits send
    private var pendingImageBitmap: android.graphics.Bitmap? = null

    private val imagePicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { handlePickedImage(it) }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as GemmaService.LocalBinder
            gemmaService = binder.getService()
            gemmaService?.uiCallback = this@MainActivity
            isBound = true
            transitionToChatUi()
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
                    visualizer?.visibility = View.VISIBLE
                    visualizer?.startAnimating()
                }
                "com.ghost.api.ACTION_TTS_STOP" -> {
                    visualizer?.stopAnimating()
                    visualizer?.visibility = View.GONE
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ttsManager = TTSManager(this)
        
        val filter = android.content.IntentFilter().apply {
            addAction("com.ghost.api.ACTION_TTS_START")
            addAction("com.ghost.api.ACTION_TTS_STOP")
        }
        val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) Context.RECEIVER_NOT_EXPORTED else 0
        registerReceiver(ttsStateReceiver, filter, flags)
        
        setupLauncherUi()
    }
    
    private fun setupLauncherUi() {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
            setBackgroundColor(android.graphics.Color.BLACK)
        }
        statusTextView = TextView(this).apply { textSize = 18f; setTextColor(android.graphics.Color.WHITE); text = "Initializing..." }
        actionButton = Button(this).apply { text = "Check State"; visibility = View.GONE }
        layout.addView(statusTextView)
        layout.addView(actionButton)
        setContentView(layout)
    }
    
    override fun onResume() {
        super.onResume()
        if (!isRitualComplete) handler.postDelayed({ checkSystemState() }, 500)
    }

    private fun checkSystemState() {
        val perms = mutableListOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.WRITE_CALENDAR,
            android.Manifest.permission.READ_CALENDAR,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.READ_PHONE_STATE
        )
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            perms.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            perms.add(android.Manifest.permission.BLUETOOTH_CONNECT)
        }

        val missing = perms.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            statusTextView.text = "Missing permissions: ${missing.size}"
            actionButton.text = "Grant Permissions"
            actionButton.visibility = View.VISIBLE
            actionButton.setOnClickListener { requestPermissions(missing.toTypedArray(), 100) }
            return
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && !android.os.Environment.isExternalStorageManager()) {
            statusTextView.text = "Storage access required to read model"
            actionButton.text = "Allow Storage"
            actionButton.visibility = View.VISIBLE
            actionButton.setOnClickListener { 
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) 
            }
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            statusTextView.text = "Overlay permission required"
            actionButton.text = "Enable Overlay"
            actionButton.visibility = View.VISIBLE
            actionButton.setOnClickListener { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
            return
        }

        completeRitual()
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

    private fun transitionToChatUi() {
        setContentView(R.layout.activity_main_chat)
        chatRecyclerView = findViewById(R.id.chatRecyclerView)
        chatInputText = findViewById(R.id.chatInputText)
        btnSend = findViewById(R.id.btnSend)
        thinkingProgress = findViewById(R.id.thinkingProgress)
        thinkingText = findViewById(R.id.thinkingText)
        visualizer = findViewById(R.id.visualizer)
        
        chatAdapter = ChatAdapter()
        chatRecyclerView?.apply {
            layoutManager = LinearLayoutManager(this@MainActivity).apply { stackFromEnd = true }
            adapter = chatAdapter
        }
        
        val btnMic = findViewById<TextView>(R.id.btnMic)
        voiceController = com.ghost.api.ui.VoiceInputController(
            context = this,
            micButton = btnMic ?: return,
            inputField = chatInputText ?: return,
            sparkleOrNull = null,
            onAudioReady = { audio ->
                gemmaService?.let { svc ->
                    scope.launch {
                        svc.processMultimodalFromUi("[Audio message received]", audio = audio)
                    }
                }
            },
            onTextReady = { text -> sendStagedMessage(text) }
        )

        chatInputText?.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val hasText = !s.isNullOrBlank()
                btnSend?.visibility = if (hasText) View.VISIBLE else View.GONE
                btnMic?.visibility  = if (hasText) View.GONE  else View.VISIBLE
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        btnSend?.setOnClickListener {
            val text = chatInputText?.text?.toString()?.trim()
            if (!text.isNullOrEmpty()) {
                chatInputText?.setText("")
                sendStagedMessage(text)
            }
        }
        
        findViewById<TextView>(R.id.btnSparkle)?.setOnClickListener { imagePicker.launch("image/*") }
        val btnDropdown = findViewById<TextView>(R.id.btnMinimize)
        btnDropdown?.setOnClickListener { showSettingsDialog() }
        
        loadHistoricalChat()
    }

    private fun showSettingsDialog() {
        val prefs = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
        
        // Root container
        val root = android.widget.ScrollView(this).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#0A0A0A"))
            setPadding(0, 0, 0, 0)
        }
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 32, 48, 32)
        }
        root.addView(container)

        val accentColor = android.graphics.Color.parseColor("#A78BFA")
        val dimTextColor = android.graphics.Color.parseColor("#99FFFFFF")
        val dividerColor = android.graphics.Color.parseColor("#1AFFFFFF")

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
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 16, 0, 16)
            }
            row.addView(TextView(this).apply {
                text = label
                textSize = 14f
                setTextColor(android.graphics.Color.WHITE)
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            val switch = android.widget.Switch(this).apply {
                isChecked = isOn
                setOnCheckedChangeListener { _, checked -> onToggle(checked) }
                thumbTintList = android.content.res.ColorStateList.valueOf(if (isOn) accentColor else android.graphics.Color.parseColor("#555555"))
                trackTintList = android.content.res.ColorStateList.valueOf(if (isOn) android.graphics.Color.parseColor("#4DA78BFA") else android.graphics.Color.parseColor("#333333"))
            }
            switch.setOnCheckedChangeListener { _, checked ->
                onToggle(checked)
                switch.thumbTintList = android.content.res.ColorStateList.valueOf(if (checked) accentColor else android.graphics.Color.parseColor("#555555"))
                switch.trackTintList = android.content.res.ColorStateList.valueOf(if (checked) android.graphics.Color.parseColor("#4DA78BFA") else android.graphics.Color.parseColor("#333333"))
            }
            row.addView(switch)
            container.addView(row)
        }

        // Edge Lights toggle
        addToggleRow("Edge Lights", com.ghost.api.ui.EdgeLightsManager.isShowing) { checked ->
            if (checked != com.ghost.api.ui.EdgeLightsManager.isShowing) {
                sendBroadcast(Intent(this, com.ghost.api.hardware.HardwareToggleReceiver::class.java).apply { action = "com.ghost.api.ACTION_TOGGLE_EDGE_LIGHTS" })
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

        // Mute TTS toggle (momentary — stops current speech)
        addToggleRow("Mute TTS", false) { checked ->
            if (checked) {
                GemmaService.instance?.ttsManager?.stop()
            }
            Toast.makeText(this, if (checked) "TTS muted" else "TTS unmuted", Toast.LENGTH_SHORT).show()
        }

        // === Divider ===
        container.addView(View(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).apply { topMargin = 16; bottomMargin = 16 }
            setBackgroundColor(dividerColor)
        })

        // === SECTION: Wallpapers (action buttons) ===
        fun addActionRow(label: String, onClick: () -> Unit) {
            container.addView(TextView(this).apply {
                text = label
                textSize = 14f
                setTextColor(android.graphics.Color.WHITE)
                setPadding(0, 24, 0, 24)
                setOnClickListener { onClick() }
                setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, android.R.drawable.ic_media_play, 0)
                compoundDrawableTintList = android.content.res.ColorStateList.valueOf(dimTextColor)
                compoundDrawablePadding = 16
            })
        }

        addActionRow("Camera Wallpaper") {
            sendBroadcast(Intent(this, com.ghost.api.hardware.HardwareToggleReceiver::class.java).apply { action = "com.ghost.api.ACTION_SET_CAMERA_WALLPAPER" })
        }
        addActionRow("Avatar Wallpaper") {
            sendBroadcast(Intent(this, com.ghost.api.hardware.HardwareToggleReceiver::class.java).apply { action = "com.ghost.api.ACTION_SET_AVATAR_WALLPAPER" })
        }

        // === Divider ===
        container.addView(View(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1
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
        val radioGroup = android.widget.RadioGroup(this).apply {
            orientation = android.widget.RadioGroup.HORIZONTAL
        }
        val backends = listOf("AUTO", "CPU", "GPU", "NPU")
        for (backend in backends) {
            radioGroup.addView(android.widget.RadioButton(this).apply {
                text = backend
                textSize = 12f
                setTextColor(android.graphics.Color.WHITE)
                buttonTintList = android.content.res.ColorStateList.valueOf(accentColor)
                isChecked = (backend == currentBackend)
                id = View.generateViewId()
                setPadding(0, 0, 24, 0)
            })
        }
        radioGroup.setOnCheckedChangeListener { group, checkedId ->
            val selected = group.findViewById<android.widget.RadioButton>(checkedId)?.text?.toString() ?: "AUTO"
            prefs.edit().putString(Constants.PREF_USER_BACKEND, selected).apply()
            Toast.makeText(this, "Backend set to $selected — takes effect on next restart", Toast.LENGTH_SHORT).show()
        }
        container.addView(radioGroup)

        // === Divider ===
        container.addView(View(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).apply { topMargin = 16; bottomMargin = 16 }
            setBackgroundColor(dividerColor)
        })

        // === SECTION: Utility Actions ===
        addActionRow("Clear Safe Mode") {
            GemmaService.instance?.resetRecoveryState()
            Toast.makeText(this, "Safe mode cleared — GPU/NPU restored on next restart", Toast.LENGTH_SHORT).show()
        }
        addActionRow("Internal Diary") {
            /* TODO: switch to diary view */
            Toast.makeText(this, "Diary view coming soon", Toast.LENGTH_SHORT).show()
        }
        addActionRow("Minimize") {
            moveTaskToBack(true)
        }

        // Build and show dialog
        val dialog = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
            .setView(root)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
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
                    chatAdapter.setMessages(messages)
                    chatRecyclerView?.scrollToPosition(chatAdapter.itemCount - 1)
                }
            } catch (e: Exception) { Timber.e(e) }
        }
    }
    override fun onMessageAdded(message: String, isUser: Boolean, isComplete: Boolean) {
        runOnUiThread {
            if (isUser) {
                chatAdapter.addMessage(ChatMessage(message, isFromUser = true))
            } else {
                val last = chatAdapter.getLastMessage()
                if (last != null && !last.isFromUser && !last.isComplete) {
                    chatAdapter.updateLastMessage(message, isComplete, null, null, null)
                } else {
                    chatAdapter.addMessage(ChatMessage(message, isFromUser = false, isComplete = isComplete))
                }
            }
            chatRecyclerView?.scrollToPosition(chatAdapter.itemCount - 1)
        }
    }

    override fun onThoughtUpdated(thought: String) {
        runOnUiThread { thinkingText?.text = "Processing: $thought" }
    }

    override fun onThinkingStateChanged(isThinking: Boolean) {
        runOnUiThread {
            thinkingProgress?.visibility = if (isThinking) View.VISIBLE else View.GONE
            thinkingText?.visibility = if (isThinking) View.VISIBLE else View.GONE
            if (isThinking) {
                val ts = java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                thinkingText?.text = "Thinking... $ts"
            }
        }
    }

    private fun sendStagedMessage(text: String) {
        val bitmap = pendingImageBitmap
        if (bitmap != null) {
            scope.launch {
                gemmaService?.processMultimodalFromUi(text, listOf(bitmap))
                withContext(Dispatchers.Main) {
                    pendingImageBitmap = null
                    chatInputText?.hint = "Δ \uD83D\uDC7E ∇" // Revert to motif
                    Toast.makeText(this@MainActivity, "Sent with image", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            gemmaService?.processQueryFromUi(text)
            Toast.makeText(this@MainActivity, "Transmission sent", Toast.LENGTH_SHORT).show()
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
                        pendingImageBitmap = bitmap
                        chatInputText?.hint = "[📎 Image attached]"
                        Toast.makeText(this@MainActivity, "Image staged. Type a prompt and send.", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) { Timber.e(e) }
        }
        checkNotificationPermission()
    }

    private var hasPromptedForNotification = false

    private fun checkNotificationPermission() {
        val cn = android.content.ComponentName(this, com.ghost.api.GemmaNotificationListener::class.java)
        val flat = android.provider.Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        val isEnabled = flat != null && flat.contains(cn.flattenToString())
        
        if (!isEnabled && !hasPromptedForNotification) {
            hasPromptedForNotification = true
            Toast.makeText(this, "Please grant Notification Access for context awareness.", Toast.LENGTH_LONG).show()
            val intent = Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) unbindService(serviceConnection)
        ttsManager.shutdown()
        unregisterReceiver(ttsStateReceiver)
    }
}
