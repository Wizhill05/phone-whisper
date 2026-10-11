package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PcmChunkerTest {

    @Test
    fun push_below_threshold_returns_null() {
        val chunker = PcmChunker(8000)
        assertNull(chunker.push(ByteArray(4000)))
    }

    @Test
    fun push_past_threshold_returns_all_accumulated_bytes() {
        val chunker = PcmChunker(8000)
        assertNull(chunker.push(ByteArray(4000)))
        val out = chunker.push(ByteArray(4000))
        assertEquals(8000, out!!.size)
    }

    @Test
    fun push_exactly_at_threshold_emits() {
        val chunker = PcmChunker(8000)
        val out = chunker.push(ByteArray(8000))
        assertEquals(8000, out!!.size)
    }

    @Test
    fun flush_returns_remainder_and_resets() {
        val chunker = PcmChunker(8000)
        chunker.push(ByteArray(3000))
        val out = chunker.flush()
        assertEquals(3000, out!!.size)
        assertNull(chunker.flush())
    }

    @Test
    fun flush_on_empty_returns_null() {
        assertNull(PcmChunker(8000).flush())
    }

    @Test
    fun multiple_chunks_accumulate_independently() {
        val chunker = PcmChunker(8000)
        assertNull(chunker.push(ByteArray(2000)))
        assertNull(chunker.push(ByteArray(2000)))
        assertNull(chunker.push(ByteArray(2000)))
        val out = chunker.flush()
        assertEquals(6000, out!!.size)
        assertNull(chunker.flush())
    }
}
