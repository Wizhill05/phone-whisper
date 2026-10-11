package com.kafkasl.phonewhisper

import java.io.ByteArrayOutputStream

/**
 * Accumulates raw PCM16 bytes and emits them in roughly fixed-size chunks so
 * the recorder can push audio to the gateway while speaking, instead of
 * buffering the whole utterance and uploading it at the end.
 *
 * Chunk size is a byte count: at 16 kHz mono PCM16 (32 KB/s), 8000 bytes is
 * ~250 ms of audio.
 */
class PcmChunker(private val chunkBytes: Int) {

    private val buffer = ByteArrayOutputStream()

    /**
     * Appends [data]; returns the accumulated bytes once the buffer reaches
     * [chunkBytes], otherwise null.
     */
    fun push(data: ByteArray): ByteArray? {
        buffer.write(data)
        if (buffer.size() >= chunkBytes) {
            val out = buffer.toByteArray()
            buffer.reset()
            return out
        }
        return null
    }

    /** Returns any buffered remainder (and resets), or null when empty. */
    fun flush(): ByteArray? {
        if (buffer.size() == 0) return null
        val out = buffer.toByteArray()
        buffer.reset()
        return out
    }
}
