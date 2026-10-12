package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextInserterTest {

    @Test
    fun buildUpdatedText_inserts_at_cursor() {
        assertEquals("hello WORLD", TextInserter.buildUpdatedText("hello ", 6, 6, "WORLD"))
    }

    @Test
    fun buildUpdatedText_replaces_selection() {
        assertEquals("hello there", TextInserter.buildUpdatedText("hello world", 6, 11, "there"))
    }

    @Test
    fun buildUpdatedText_handles_reversed_selection() {
        assertEquals("hello there", TextInserter.buildUpdatedText("hello world", 11, 6, "there"))
    }

    @Test
    fun buildUpdatedText_clamps_out_of_range_selection() {
        assertEquals("!", TextInserter.buildUpdatedText("hi", -5, 100, "!"))
    }

    @Test
    fun verifyInserted_true_when_text_present() {
        assertTrue(TextInserter.verifyInserted("hello WORLD", "WORLD"))
    }

    @Test
    fun verifyInserted_false_when_text_missing() {
        assertFalse(TextInserter.verifyInserted("hello ", "WORLD"))
    }

    @Test
    fun verifyInserted_false_on_null_or_blank_insertion() {
        assertFalse(TextInserter.verifyInserted("hello", ""))
        assertFalse(TextInserter.verifyInserted(null, "WORLD"))
    }
}
