package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BubbleVisibilityControllerTest {

    private var bubbleVisible = false
    private var scheduledDelayMs: Long? = null
    private var cancelScheduledCalls = 0

    private lateinit var controller: BubbleVisibilityController

    @Before
    fun setUp() {
        bubbleVisible = false
        scheduledDelayMs = null
        cancelScheduledCalls = 0

        controller = BubbleVisibilityController(
            onShow = { bubbleVisible = true },
            onHide = { bubbleVisible = false },
            onScheduleHide = { delayMs -> scheduledDelayMs = delayMs },
            onCancelScheduledHide = {
                cancelScheduledCalls++
                scheduledDelayMs = null
            }
        )
    }

    @Test
    fun initial_state_is_hidden_when_keyboard_inactive() {
        controller.init(keyboardActive = false, onlyShowWithKeyboard = true)
        assertFalse(bubbleVisible)
    }

    @Test
    fun initial_state_is_shown_when_keyboard_active() {
        controller.init(keyboardActive = true, onlyShowWithKeyboard = true)
        assertTrue(bubbleVisible)
    }

    @Test
    fun initial_state_is_shown_when_onlyShowWithKeyboard_is_false() {
        controller.init(keyboardActive = false, onlyShowWithKeyboard = false)
        assertTrue(bubbleVisible)
    }

    @Test
    fun keyboard_active_shows_bubble_and_cancels_pending_hides() {
        controller.init(keyboardActive = false, onlyShowWithKeyboard = true)
        controller.onKeyboardVisibilityChanged(true)
        assertTrue(bubbleVisible)
        assertTrue(cancelScheduledCalls > 0)
    }

    @Test
    fun keyboard_inactive_while_idle_hides_bubble() {
        controller.init(keyboardActive = true, onlyShowWithKeyboard = true)
        controller.onKeyboardVisibilityChanged(false)
        assertFalse(bubbleVisible)
    }

    @Test
    fun keyboard_inactive_while_recording_keeps_bubble_visible_without_timer() {
        controller.init(keyboardActive = true, onlyShowWithKeyboard = true)
        controller.onRecordingStarted()
        controller.onKeyboardVisibilityChanged(false)

        assertTrue("Bubble must remain visible while recording even if keyboard is closed", bubbleVisible)
        assertEquals(null, scheduledDelayMs)
    }

    @Test
    fun keyboard_inactive_while_transcribing_keeps_bubble_visible_without_timer() {
        controller.init(keyboardActive = true, onlyShowWithKeyboard = true)
        controller.onRecordingStarted()
        controller.onTranscribingStarted()
        controller.onKeyboardVisibilityChanged(false)

        assertTrue("Bubble must remain visible while transcribing even if keyboard is closed", bubbleVisible)
        assertEquals(null, scheduledDelayMs)
    }

    @Test
    fun dictation_finished_when_keyboard_inactive_schedules_10s_hide() {
        controller.init(keyboardActive = true, onlyShowWithKeyboard = true)
        controller.onRecordingStarted()
        controller.onKeyboardVisibilityChanged(false)
        controller.onTranscribingStarted()
        controller.onDictationFinished()

        assertTrue(bubbleVisible)
        assertEquals(10_000L, scheduledDelayMs)
    }

    @Test
    fun dictation_finished_when_keyboard_active_stays_visible_without_timer() {
        controller.init(keyboardActive = true, onlyShowWithKeyboard = true)
        controller.onRecordingStarted()
        controller.onTranscribingStarted()
        controller.onDictationFinished()

        assertTrue(bubbleVisible)
        assertEquals(null, scheduledDelayMs)
    }

    @Test
    fun timer_fires_hides_bubble() {
        controller.init(keyboardActive = true, onlyShowWithKeyboard = true)
        controller.onRecordingStarted()
        controller.onKeyboardVisibilityChanged(false)
        controller.onTranscribingStarted()
        controller.onDictationFinished()

        controller.onHideTimerFired()
        assertFalse(bubbleVisible)
    }

    @Test
    fun keyboard_reopens_during_10s_countdown_cancels_timer() {
        controller.init(keyboardActive = true, onlyShowWithKeyboard = true)
        controller.onRecordingStarted()
        controller.onKeyboardVisibilityChanged(false)
        controller.onTranscribingStarted()
        controller.onDictationFinished()

        assertEquals(10_000L, scheduledDelayMs)
        controller.onKeyboardVisibilityChanged(true)
        assertEquals(null, scheduledDelayMs)
        assertTrue(bubbleVisible)
    }

    @Test
    fun new_recording_during_10s_countdown_cancels_timer() {
        controller.init(keyboardActive = true, onlyShowWithKeyboard = true)
        controller.onRecordingStarted()
        controller.onKeyboardVisibilityChanged(false)
        controller.onTranscribingStarted()
        controller.onDictationFinished()

        controller.onRecordingStarted()
        assertEquals(null, scheduledDelayMs)
        assertTrue(bubbleVisible)
    }

    @Test
    fun keyboard_closes_shortly_after_dictation_finished_honors_remaining_10s_grace() {
        var currentTime = 1000L
        val testController = BubbleVisibilityController(
            onShow = { bubbleVisible = true },
            onHide = { bubbleVisible = false },
            onScheduleHide = { delayMs -> scheduledDelayMs = delayMs },
            onCancelScheduledHide = {
                cancelScheduledCalls++
                scheduledDelayMs = null
            },
            clock = { currentTime }
        )

        testController.init(keyboardActive = true, onlyShowWithKeyboard = true)
        testController.onRecordingStarted()
        testController.onTranscribingStarted()
        testController.onDictationFinished() // at t = 1000L

        // 3 seconds later, keyboard closes (t = 4000L)
        currentTime = 4000L
        testController.onKeyboardVisibilityChanged(false)

        assertTrue(bubbleVisible)
        assertEquals(7_000L, scheduledDelayMs)
    }

    @Test
    fun preference_disabled_always_shows_bubble() {
        controller.init(keyboardActive = false, onlyShowWithKeyboard = true)
        assertFalse(bubbleVisible)
        controller.onPreferenceChanged(onlyShowWithKeyboard = false)
        assertTrue(bubbleVisible)

        controller.onKeyboardVisibilityChanged(false)
        assertTrue(bubbleVisible)
    }
}
