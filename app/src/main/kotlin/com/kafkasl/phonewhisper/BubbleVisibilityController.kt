package com.kafkasl.phonewhisper

/**
 * Manages floating bubble overlay visibility based on keyboard presence,
 * dictation state, and post-completion grace periods.
 */
class BubbleVisibilityController(
    private val onShow: () -> Unit,
    private val onHide: () -> Unit,
    private val onScheduleHide: (delayMs: Long) -> Unit,
    private val onCancelScheduledHide: () -> Unit,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    enum class State {
        IDLE,
        RECORDING,
        TRANSCRIBING,
    }

    private var onlyShowWithKeyboard: Boolean = true
    private var isKeyboardActive: Boolean = false
    private var dictationState: State = State.IDLE
    private var isVisible: Boolean = false
    private var lastDictationFinishedMs: Long = 0L

    fun init(keyboardActive: Boolean, onlyShowWithKeyboard: Boolean) {
        this.onlyShowWithKeyboard = onlyShowWithKeyboard
        this.isKeyboardActive = keyboardActive
        this.dictationState = State.IDLE
        this.lastDictationFinishedMs = 0L
        onCancelScheduledHide()
        updateVisibility()
    }

    fun onPreferenceChanged(onlyShowWithKeyboard: Boolean) {
        this.onlyShowWithKeyboard = onlyShowWithKeyboard
        updateVisibility()
    }

    fun onKeyboardVisibilityChanged(active: Boolean) {
        this.isKeyboardActive = active
        if (active) {
            onCancelScheduledHide()
            updateVisibility()
        } else {
            if (onlyShowWithKeyboard && dictationState == State.IDLE) {
                val elapsed = clock() - lastDictationFinishedMs
                if (lastDictationFinishedMs > 0 && elapsed < GRACE_PERIOD_MS) {
                    val remaining = GRACE_PERIOD_MS - elapsed
                    onScheduleHide(remaining)
                    return
                }
            }
            updateVisibility()
        }
    }

    fun onRecordingStarted() {
        this.dictationState = State.RECORDING
        onCancelScheduledHide()
        updateVisibility()
    }

    fun onTranscribingStarted() {
        this.dictationState = State.TRANSCRIBING
        onCancelScheduledHide()
        updateVisibility()
    }

    fun onDictationFinished() {
        this.dictationState = State.IDLE
        this.lastDictationFinishedMs = clock()

        if (!onlyShowWithKeyboard) {
            updateVisibility()
            return
        }

        if (!isKeyboardActive) {
            // Keyboard is already closed: schedule grace period before hiding
            onScheduleHide(GRACE_PERIOD_MS)
        } else {
            // Keyboard is still open: stay visible
            updateVisibility()
        }
    }

    fun onHideTimerFired() {
        if (!onlyShowWithKeyboard) return
        if (dictationState != State.IDLE) return
        if (isKeyboardActive) return

        if (isVisible) {
            isVisible = false
            onHide()
        }
    }

    private fun updateVisibility() {
        if (!onlyShowWithKeyboard) {
            onCancelScheduledHide()
            if (!isVisible) {
                isVisible = true
                onShow()
            }
            return
        }

        val shouldBeVisible = isKeyboardActive || dictationState != State.IDLE
        if (shouldBeVisible) {
            onCancelScheduledHide()
            if (!isVisible) {
                isVisible = true
                onShow()
            }
        } else {
            if (isVisible) {
                isVisible = false
                onHide()
            }
        }
    }

    companion object {
        const val GRACE_PERIOD_MS = 10_000L
    }
}
