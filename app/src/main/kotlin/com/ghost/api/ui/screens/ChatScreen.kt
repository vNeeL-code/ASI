package com.ghost.api.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.ghost.api.hardware.AudioRecorder
import com.ghost.api.ui.chat.ChatMessage
import com.ghost.api.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun ChatScreen(
    messages: List<ChatMessage>,
    isThinking: Boolean,
    thinkingText: String,
    attachedImage: Bitmap?,
    isTtsActive: Boolean = false,
    onSendMessage: (String) -> Unit,
    onSendAudio: (ByteArray) -> Unit,
    onPickImage: () -> Unit,
    onClearImage: () -> Unit,
    onToggleThinking: (ChatMessage) -> Unit,
    onOpenSettings: () -> Unit,
    visualizerViewFactory: ((Context) -> android.view.View)? = null
) {
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val isImeVisible = imeInsets.getBottom(density) > 0
    
    LaunchedEffect(messages.size, isImeVisible) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        // Top Navigation Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0x11FFFFFF))
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left spacer matching right button footprint for true mathematical center
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .alpha(0f)
            )
            
            // Centered Turing Machine Glyph: Green Δ, Purple 👾, Green ∇
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "Δ ",
                    color = Color(0xFF22C55E), // Terminal/Matrix Green
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.1.sp
                )
                Text(
                    text = "👾",
                    fontSize = 18.sp,
                    modifier = Modifier.padding(horizontal = 1.dp)
                )
                Text(
                    text = " ∇",
                    color = Color(0xFF22C55E), // Terminal/Matrix Green
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.1.sp
                )
            }
            
            // Settings menu dropdown button
            Text(
                text = "▼",
                color = TextSecondary,
                fontSize = 18.sp,
                modifier = Modifier
                    .clickable { onOpenSettings() }
                    .alpha(0.8f)
                    .padding(8.dp)
            )
        }

        HorizontalDivider(color = Color(0x1AFFFFFF), thickness = 1.dp)

        // Chat History List
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(top = 8.dp, bottom = 4.dp)
        ) {
            items(messages) { message ->
                ChatMessageRow(
                    message = message,
                    onToggleThinking = { onToggleThinking(message) }
                )
            }
        }

        // Loading Indicator
        if (isThinking) {
            Row(
                modifier = Modifier
                    .padding(start = 24.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = AccentPurple,
                    strokeWidth = 2.dp
                )
                Text(
                    text = thinkingText.ifEmpty { "Thinking..." },
                    color = AccentPurple,
                    fontSize = 10.sp,
                    letterSpacing = 0.05.sp,
                    modifier = Modifier.padding(start = 10.dp)
                )
            }
        }

        // Audio Visualizer — only takes layout space when TTS is actively playing
        if (isTtsActive && visualizerViewFactory != null) {
            AndroidView(
                factory = visualizerViewFactory,
                modifier = Modifier
                    .width(100.dp)
                    .height(28.dp)
                    .padding(bottom = 2.dp)
                    .align(Alignment.CenterHorizontally)
            )
        }

        // Input Bar Area with complete voice & multimodal state mechanics
        InputBar(
            attachedImage = attachedImage,
            onSendMessage = onSendMessage,
            onSendAudio = onSendAudio,
            onPickImage = onPickImage,
            onClearImage = onClearImage
        )
    }
}

@Composable
fun ChatMessageRow(message: ChatMessage, onToggleThinking: () -> Unit) {
    val isUser = message.isFromUser
    
    // Process display content (strip thought, tool calls, and control protocol markup)
    val displayContent = message.content
        .replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("<\\|channel>thought.*?<channel\\|>", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("<\\|?channel>?|<channel\\|?>"), "")
        .replace(Regex("<\\|tool_call>.*?<tool_call\\|>", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("<\\|tool_call>|<tool_call\\|>"), "")
        .replace(Regex("<\\|tool_response>|<tool_response\\|>"), "")
        .replace(Regex("\\[\\[([A-Z_a-z0-9]+)(?::([^\\]]+))?\\]\\]"), "")
        .trim()
    
    val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    val timeStr = timeFormat.format(Date(message.timestamp))
    
    val finalDisplayContent = if (isUser) {
        "$displayContent\n\n[$timeStr]"
    } else {
        "✧ Gemma:\n$displayContent\n\n[$timeStr]"
    }
    
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val bubbleColor = if (isUser) BubbleUser else BubbleGemma
    
    val shape = if (isUser) {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 2.dp)
    } else {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 2.dp, bottomEnd = 16.dp)
    }
    
    val textColor = if (isUser) UserCyan else AiGreen
    
    val headerText = when {
        message.eventType == "LOGIC_TRACE" -> "⌬ REASONING TRACE ⌬"
        message.eventType == "DREAM" -> "✧ DREAM STATE ✧"
        isUser -> "Δ 🦑 ∇"
        else -> "Δ 👾 ∇"
    }
    val headerColor = when {
        message.eventType == "LOGIC_TRACE" -> AccentOrange
        message.eventType == "DREAM" -> AccentBlue
        isUser -> AccentPurple
        else -> AccentPurple
    }

    val ucfFormattedContent = if (isUser) {
        "Δ 🦑 ∇:\n$displayContent\n\n[$timeStr]"
    } else {
        "✧ Gemma:\n$displayContent\n\n[$timeStr]"
    }

    var showThinking by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = alignment
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(bubbleColor, shape)
                .border(1.dp, AccentBorder, shape)
                .padding(16.dp)
        ) {
            // Header with 1-tap Copy Button (UCF format)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = headerText,
                    color = headerColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.1.sp
                )

                Text(
                    text = "📋",
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clickable {
                            clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(ucfFormattedContent))
                            Toast.makeText(context, "Copied UCF to clipboard", Toast.LENGTH_SHORT).show()
                        }
                        .alpha(0.6f)
                        .padding(start = 8.dp)
                )
            }
            
            // Thinking block
            if (!message.thought.isNullOrEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                        .background(Color(0x11FFFFFF))
                        .padding(10.dp)
                ) {
                    Text(
                        text = if (showThinking) "reasoning ▾" else "reasoning ▸",
                        color = Color(0x88FFFFFF),
                        fontSize = 10.sp,
                        letterSpacing = 0.05.sp,
                        modifier = Modifier.clickable { showThinking = !showThinking }
                    )
                    
                    if (showThinking) {
                        SelectionContainer {
                            Text(
                                text = message.thought,
                                color = Color(0x99FFFFFF),
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 6.dp),
                                lineHeight = 14.sp
                            )
                        }
                    }
                }
            }
            
            // Message Content
            SelectionContainer {
                Text(
                    text = finalDisplayContent,
                    color = textColor,
                    fontSize = 15.sp,
                    lineHeight = 19.sp
                )
            }
        }
    }
}

private enum class VoiceState { IDLE, RECORDING, CONFIRM }

@Composable
fun InputBar(
    attachedImage: Bitmap?,
    onSendMessage: (String) -> Unit,
    onSendAudio: (ByteArray) -> Unit,
    onPickImage: () -> Unit,
    onClearImage: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val audioRecorder = remember { AudioRecorder(context) }
    
    var text by remember { mutableStateOf("") }
    var voiceState by remember { mutableStateOf(VoiceState.IDLE) }
    var pendingAudio by remember { mutableStateOf<ByteArray?>(null) }
    var recordingJob by remember { mutableStateOf<Job?>(null) }

    // Pulse animation for recording and confirm states
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val colorIdle = Color(0xFF8BB4F6)      // Ethereal Off-white cobalt — matching sparkle & app icon
    val colorRecording = Color(0xFFA78BFA) // Electric Purple — active recording pulse & hint
    val colorConfirm = Color(0xFFF97316)   // Orange — confirm / send
    val colorSend = Color(0xFF60A5FA)      // Electric Cobalt — send arrow

    // U+2B24 BLACK LARGE CIRCLE ⬤ — standard tintable circle matching VoiceInputController
    val CIRCLE_GLYPH = "\u2B24"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 12.dp)
            .background(BubbleUser, RoundedCornerShape(24.dp))
            .border(1.dp, AccentBorder, RoundedCornerShape(24.dp))
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Sparkle / Image Attachment Button
        if (attachedImage != null) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onClearImage() },
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = attachedImage.asImageBitmap(),
                    contentDescription = "Attached Image",
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else {
            Text(
                text = "✧",
                color = when (voiceState) {
                    VoiceState.RECORDING -> colorRecording
                    VoiceState.CONFIRM -> colorConfirm
                    else -> colorIdle
                },
                fontSize = 26.sp,
                modifier = Modifier
                    .size(44.dp)
                    .alpha(if (voiceState == VoiceState.RECORDING || voiceState == VoiceState.CONFIRM) pulseAlpha else 1f)
                    .clickable { onPickImage() }
                    .padding(4.dp),
                textAlign = TextAlign.Center
            )
        }
        
        // Center Text Input / Voice Status Field
        Box(
            modifier = Modifier
                .weight(1f)
                .clickable {
                    // Tap text area to cancel recording or confirm mode (escape hatch)
                    if (voiceState != VoiceState.IDLE) {
                        audioRecorder.stopRecording()
                        recordingJob?.cancel()
                        pendingAudio = null
                        voiceState = VoiceState.IDLE
                    }
                }
                .padding(12.dp)
        ) {
            BasicTextField(
                value = text,
                onValueChange = { 
                    if (voiceState == VoiceState.IDLE) {
                        text = it 
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                textStyle = TextStyle(color = TextPrimary, fontSize = 15.sp),
                cursorBrush = SolidColor(TextPrimary),
                enabled = voiceState == VoiceState.IDLE,
                decorationBox = { innerTextField ->
                    if (text.isEmpty()) {
                        val hint = when {
                            attachedImage != null -> "[📎 Image attached ]"
                            voiceState == VoiceState.RECORDING -> "Recording..."
                            voiceState == VoiceState.CONFIRM -> "Send or tap here to cancel"
                            else -> "Δ 👾 ∇"
                        }
                        val hintColor = when (voiceState) {
                            VoiceState.RECORDING -> colorRecording
                            VoiceState.CONFIRM -> colorConfirm
                            else -> Color(0x44FFFFFF)
                        }
                        Text(hint, color = hintColor, fontSize = 15.sp)
                    }
                    innerTextField()
                }
            )
        }
        
        // Right Action Button (Dynamic State Machine: Text Send / Voice Record / Confirm Send)
        if (text.isNotBlank()) {
            // Text is ready: Electric cobalt send arrow
            Text(
                text = "➤",
                color = colorSend,
                fontSize = 22.sp,
                modifier = Modifier
                    .size(44.dp)
                    .clickable { 
                        onSendMessage(text)
                        text = ""
                    }
                    .padding(10.dp),
                textAlign = TextAlign.Center
            )
        } else {
            // No text: Voice state button
            val (btnColor, btnAlpha) = when (voiceState) {
                VoiceState.IDLE -> colorIdle to 0.85f
                VoiceState.RECORDING -> colorRecording to pulseAlpha
                VoiceState.CONFIRM -> colorConfirm to pulseAlpha
            }

            Text(
                text = CIRCLE_GLYPH,
                color = btnColor,
                fontSize = 20.sp,
                modifier = Modifier
                    .size(44.dp)
                    .alpha(btnAlpha)
                    .clickable {
                        when (voiceState) {
                            VoiceState.IDLE -> {
                                if (!audioRecorder.hasPermission()) {
                                    Toast.makeText(context, "Microphone permission required", Toast.LENGTH_SHORT).show()
                                } else {
                                    voiceState = VoiceState.RECORDING
                                    pendingAudio = null
                                    recordingJob = coroutineScope.launch {
                                        val audio = withContext(Dispatchers.IO) {
                                            audioRecorder.record(30, false)
                                        }
                                        if (audio != null && audio.isNotEmpty()) {
                                            pendingAudio = audio
                                            voiceState = VoiceState.CONFIRM
                                        } else {
                                            voiceState = VoiceState.IDLE
                                        }
                                    }
                                }
                            }
                            VoiceState.RECORDING -> {
                                audioRecorder.stopRecording()
                            }
                            VoiceState.CONFIRM -> {
                                val audio = pendingAudio
                                if (audio != null && audio.isNotEmpty()) {
                                    onSendAudio(audio)
                                }
                                pendingAudio = null
                                voiceState = VoiceState.IDLE
                            }
                        }
                    }
                    .padding(10.dp),
                textAlign = TextAlign.Center
            )
        }
    }
}
