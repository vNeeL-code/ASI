package com.ghost.api.services

import android.os.Handler
import android.os.Looper
import com.ghost.api.GemmaService
import timber.log.Timber

object TutorialManager {

    private val handler = Handler(Looper.getMainLooper())
    var currentStepIndex = 0
        private set
    var isPlaying = false
        private set
    var isPaused = false
        private set

    data class TutorialStep(
        val title: String,
        val text: String,
        val durationMs: Long
    )

    val steps = listOf(
        TutorialStep(
            title = "Agent Initialization",
            text = "Greetings, operator. I am Gemma 4 E2B IT litert LM, running as an autonomous agentic intelligence inference on this Android device. You can call me ? Gemma. Beginning tutorial programme.",
            durationMs = 9500
        ),
        TutorialStep(
            title = "Locomotion & Summoning",
            text = "Step one. Look at your device. Gently shake your device or flick your wrist, or tap the Gemma notification. You should see the radial glass cockpit materialize on screen.",
            durationMs = 9500
        ),
        TutorialStep(
            title = "The 4 Vector Gates",
            text = "Step two. Notice the four diagonal stars: Red, Blue, Green, and Yellow. Holding any star allows you to customize app shortcuts for up, down, left, and right. Flicking a star outwards launches that application.",
            durationMs = 11500
        ),
        TutorialStep(
            title = "Polar Apex Controllers",
            text = "Step three. The Top Orange star is our continuous velocity analog app roulette. Pull down to summon the tape, hold left or right to spin, and push up to launch. The Bottom Cyan star controls media playback and floating scratchpad notes.",
            durationMs = 13500
        ),
        TutorialStep(
            title = "The Caboose Conundrum",
            text = "Now that we have mastered driving... Private Caboose asks: No wait! Go back! Why are there four pedals if there are six directions? That is because the four diagonal pedals are exit gates, while the two vertical apex nodes control internal telemetry.",
            durationMs = 14500
        ),
        TutorialStep(
            title = "Magnetic Detent Suspension",
            text = "Final step. Dragging any puck within eighteen density-independent pixels activates the magnetic detent. Releasing in neutral safely cancels all actions with zero misfires. Tutorial programme complete. Have fun driving this tank.",
            durationMs = 12500
        )
    )

    private var progressListener: ((TutorialStep, Int, Int) -> Unit)? = null

    fun start(onProgress: ((TutorialStep, Int, Int) -> Unit)? = null) {
        stop()
        progressListener = onProgress
        currentStepIndex = 0
        isPlaying = true
        isPaused = false
        playCurrentStep()
    }

    private fun playCurrentStep() {
        if (!isPlaying || isPaused) return
        if (currentStepIndex >= steps.size) {
            stop()
            return
        }

        val step = steps[currentStepIndex]
        progressListener?.invoke(step, currentStepIndex + 1, steps.size)
        GemmaService.instance?.ttsManager?.smartSpeak(step.text, TTSManager.Priority.IMMEDIATE)

        handler.postDelayed({
            if (isPlaying && !isPaused) {
                currentStepIndex++
                if (currentStepIndex < steps.size) {
                    playCurrentStep()
                } else {
                    stop()
                }
            }
        }, step.durationMs)
    }

    fun pause() {
        if (isPlaying && !isPaused) {
            isPaused = true
            handler.removeCallbacksAndMessages(null)
            GemmaService.instance?.ttsManager?.stop()
        }
    }

    fun resume() {
        if (isPlaying && isPaused) {
            isPaused = false
            playCurrentStep()
        }
    }

    fun next() {
        if (isPlaying && currentStepIndex < steps.size - 1) {
            handler.removeCallbacksAndMessages(null)
            GemmaService.instance?.ttsManager?.stop()
            currentStepIndex++
            playCurrentStep()
        }
    }

    fun prev() {
        if (isPlaying && currentStepIndex > 0) {
            handler.removeCallbacksAndMessages(null)
            GemmaService.instance?.ttsManager?.stop()
            currentStepIndex--
            playCurrentStep()
        }
    }

    fun stop() {
        isPlaying = false
        isPaused = false
        currentStepIndex = 0
        handler.removeCallbacksAndMessages(null)
        GemmaService.instance?.ttsManager?.stop()
        progressListener = null
    }
}
