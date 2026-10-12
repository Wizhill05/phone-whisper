package com.kafkasl.phonewhisper

/**
 * Pure text-insertion helpers for cross-app injection.
 *
 * The accessibility service has no InputConnection to the target app, so the
 * closest equivalent to commitText is a cursor-aware ACTION_SET_TEXT: read the
 * node's current text plus selection, splice the dictated text in at the
 * cursor, set the combined text, then verify the insertion survived by
 * re-reading the node (surrounding-text verification).
 */
object TextInserter {

    /**
     * Splices [insert] into [current], replacing [start, end) (order-agnostic,
     * clamped to bounds). Mirrors InputConnection.commitText cursor semantics.
     */
    fun buildUpdatedText(current: String, start: Int, end: Int, insert: String): String {
        val s = start.coerceIn(0, current.length)
        val e = end.coerceIn(0, current.length)
        val replacementStart = minOf(s, e)
        val replacementEnd = maxOf(s, e)
        return current.replaceRange(replacementStart, replacementEnd, insert)
    }

    /** True when the node text visibly contains the inserted text. */
    fun verifyInserted(updated: CharSequence?, inserted: String): Boolean =
        inserted.isNotBlank() && (updated?.contains(inserted) == true)
}
