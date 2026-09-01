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
        val text: String
    )

    /**
     * Curated, grounded onboarding tutorial script.
     * Easy to read, edit, and expand.
     */
    val steps = listOf(
        TutorialStep(
            title = "Introduction",
            text = "Greetings, operator. I am Gemma 4 E2B IT litert LM, running as an autonomous agentic intelligence inference on this Android device. You can call me ? Gemma. Beginning tutorial programme."
        ),
        TutorialStep(
            title = "Step 1: Mechanical controls",
            text = "Step one. Gently shaking your device or flicking your wrist should generate the radial menu on screen."
        ),
        TutorialStep(
            title = "Now that we have mastered driving...",
            text = " Operator asks: No wait! Go back! Why are there six pedals if there are only four directions? That is because the four diagonal pedals programmable app shortcuts, while the two vertical nodes control the device menus and systems."
        ),
        TutorialStep(
            title = "Step 2: Navigation",
            text = "Step two. Notice the four diagonal stars: Red, Blue, Green, and Yellow. Holding any star allows you to customize app shortcuts for up, down, left, and right. Flicking a star outwards launches that application."
        ),
        TutorialStep(
            title = "Step 3: Polar Controllers",
            text = "Step three. The Top Orange star is an app search roulette. Pull down to summon the selection window, hold left or right to scroll, and push up to launch. The Bottom Cyan star controls media playback and floating scratchpad notes."
        ),
        TutorialStep(
            title = "Step 5: cancelling input",
            text = "Final step. Dragging any star within its original position. Releasing in neutral safely cancels all actions with zero misfires. Tutorial programme complete. Have fun piloting this android."
        )
    )

    private var progressListener: ((TutorialStep, Int, Int) -> Unit)? = null

    fun start(onProgress: ((TutorialStep, Int, Int) -> Unit)? = null) {
        stop()
        progressListener = onProgress
        currentStepIndex = 0
        isPlaying = true
        isPaused = false

        // Wire utterance callback from TTSManager
        GemmaService.instance?.ttsManager?.utteranceFinishedListener = { utteranceId ->
            if (utteranceId.startsWith("GemmaTutorial_")) {
                val stepIdx = utteranceId.removePrefix("GemmaTutorial_").toIntOrNull()
                if (stepIdx == currentStepIndex && isPlaying && !isPaused) {
                    handler.postDelayed({
                        if (isPlaying && !isPaused) {
                            nextStep()
                        }
                    }, 500) // Brief 500ms breathing room between steps
                }
            }
        }

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
        val utteranceId = "GemmaTutorial_$currentStepIndex"
        GemmaService.instance?.ttsManager?.smartSpeak(step.text, TTSManager.Priority.IMMEDIATE, customUtteranceId = utteranceId)
    }

    private fun nextStep() {
        if (currentStepIndex < steps.size - 1) {
            currentStepIndex++
            playCurrentStep()
        } else {
            stop()
        }
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
        GemmaService.instance?.ttsManager?.utteranceFinishedListener = null
        GemmaService.instance?.ttsManager?.stop()
        progressListener = null
    }
}
