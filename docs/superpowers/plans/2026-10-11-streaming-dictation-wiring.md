# Streaming Dictation Wiring — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans or superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace buffer-then-upload in the Phone Whisper bubble with true streaming to the gateway's WebSocket endpoint, with automatic batch-upload fallback when streaming is unavailable.

**Architecture:** Two new pure Kotlin classes (`PcmChunker`, `GatewayStreamController`) carry all the stateful logic and are fully unit-testable without Android. The accessibility service only wires them into the existing record loop and feedback paths — no logic in the service itself.

**Tech Stack:** Kotlin, JUnit 4, `org.json` (already a `testImplementation` dependency), existing `GatewayStream` client, Gradle.

**Spec:** `docs/superpowers/specs/2026-10-11-streaming-dictation-design.md`

**Repo:** `~/phone-whisper-vps` (branch `vps`). All paths below are relative to it.

**Known-good context:** `GatewayStream.kt` exists (connect/send/finalize/close + watchdog). The gateway's `WS /v1/dictation/stream` endpoint is deployed and verified live. The service currently buffers all PCM and POSTs the complete WAV at stop (`transcribeGateway`).

---

## Task 1: PcmChunker

**Files:**
- Create: `app/src/main/kotlin/com/kafkasl/phonewhisper/PcmChunker.kt`
- Test: `app/src/test/kotlin/com/kafkasl/phonewhisper/PcmChunkerTest.kt`

**Interfaces:**
- Produces: `class PcmChunker(private val chunkBytes: Int)` with `fun push(data: ByteArray): ByteArray?` (returns accumulated bytes once the buffer reaches `chunkBytes`, else `null`) and `fun flush(): ByteArray?` (returns any remainder, else `null`).

- [ ] **Step 1: Write the failing test**

```kotlin
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
        val out = chunker.push(ByteArray(2000))
        assertEquals(6000, out!!.size)
        assertNull(chunker.flush())
    }
}
```

- [ ] **Step 2: Run to verify failure**: `./gradlew testDebugUnitTest --tests '*PcmChunkerTest*'` → fails (class missing).
- [ ] **Step 3: Implement `PcmChunker.kt`** — `ByteArrayOutputStream` internally; `push` writes then checks `size() >= chunkBytes` and returns `toByteArray()` + `reset()`; `flush` returns `toByteArray()` + `reset()` only when non-empty, else `null`.
- [ ] **Step 4: Run to verify pass**: `./gradlew testDebugUnitTest --tests '*PcmChunkerTest*'` → 6 passed.
- [ ] **Step 5: Commit**: `git add app && git commit -m "feat: add PcmChunker for 250ms audio batching"`

---

## Task 2: GatewayStreamController

**Files:**
- Create: `app/src/main/kotlin/com/kafkasl/phonewhisper/GatewayStreamController.kt`
- Test: `app/src/test/kotlin/com/kafkasl/phonewhisper/GatewayStreamControllerTest.kt`

**Interfaces:**
- Consumes: `GatewayStream.Listener`.
- Produces: `class GatewayStreamController(private val onResult: (String) -> Unit, private val onFallback: (ByteArray) -> Unit) : GatewayStream.Listener` with `val isActive: Boolean` (public get, private set), `override fun onConnected()`, `override fun onResult(json: JSONObject)`, `override fun onFailure(message: String)`, and `fun prepareFallback(pcm: ByteArray)`.
- Behavior contract (exact):
  - `onConnected()` → `isActive = true`
  - `onResult(json)` → `isActive = false`; clear any held fallback; deliver `json.optString("clean_text").ifBlank { json.optString("text") }.trim()` via `onResult` (may be an empty string — the service decides what to do with it)
  - `onFailure(msg)` → capture `wasActive`; `isActive = false`; clear held fallback; if `wasActive && fallback != null` call `onFallback(fallback)` exactly once
  - `prepareFallback(pcm)` → stores PCM for a possible post-finalize failure fallback

- [ ] **Step 1: Write the failing test**

```kotlin
package com.kafkasl.phonewhisper

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayStreamControllerTest {

    private var delivered: String? = null
    private var fallbackCount = 0

    private fun controller() = GatewayStreamController(
        onResult = { delivered = it },
        onFallback = { fallbackCount++ },
    )

    @Test
    fun onConnected_activates() {
        val c = controller()
        c.onConnected()
        assertTrue(c.isActive)
    }

    @Test
    fun onResult_delivers_clean_text_and_deactivates() {
        val c = controller()
        c.onConnected()
        c.onResult(JSONObject("""{"clean_text":"We ship Friday.","text":"so um we ship friday"}"""))
        assertFalse(c.isActive)
        assertEquals("We ship Friday.", delivered)
    }

    @Test
    fun onResult_falls_back_to_text_field() {
        val c = controller()
        c.onConnected()
        c.onResult(JSONObject("""{"text":"raw transcript only"}"""))
        assertEquals("raw transcript only", delivered)
    }

    @Test
    fun onResult_with_no_text_delivers_empty_string() {
        val c = controller()
        c.onConnected()
        c.onResult(JSONObject("""{"clean_text":""}"""))
        assertEquals("", delivered)
    }

    @Test
    fun failure_while_active_with_prepared_fallback_triggers_fallback() {
        val c = controller()
        c.onConnected()
        c.prepareFallback(ByteArray(10))
        c.onFailure("boom")
        assertFalse(c.isActive)
        assertEquals(1, fallbackCount)
        assertNull(delivered)
    }

    @Test
    fun failure_mid_recording_without_fallback_does_nothing() {
        val c = controller()
        c.onConnected()
        c.onFailure("dropped mid-record")
        assertFalse(c.isActive)
        assertEquals(0, fallbackCount)
    }

    @Test
    fun late_failure_after_result_does_not_double_fallback() {
        val c = controller()
        c.onConnected()
        c.prepareFallback(ByteArray(10))
        c.onResult(JSONObject("""{"text":"ok"}"""))
        c.onFailure("watchdog fired late")
        assertEquals("ok", delivered)
        assertEquals(0, fallbackCount)
    }
}
```

- [ ] **Step 2: Run to verify failure**: `./gradlew testDebugUnitTest --tests '*GatewayStreamControllerTest*'` → fails (class missing).
- [ ] **Step 3: Implement `GatewayStreamController.kt`** exactly per the behavior contract above.
- [ ] **Step 4: Run to verify pass**: `./gradlew testDebugUnitTest --tests '*GatewayStreamControllerTest*'` → 7 passed.
- [ ] **Step 5: Commit**: `git add app && git commit -m "feat: add GatewayStreamController state machine with fallback"`

---

## Task 3: Wire streaming into WhisperAccessibilityService

**Files:**
- Modify: `app/src/main/kotlin/com/kafkasl/phonewhisper/WhisperAccessibilityService.kt`
- Test: none (Android `AccessibilityService`; logic lives in Tasks 1–2 and is covered there)

**Interfaces:**
- Consumes: `PcmChunker` (Task 1), `GatewayStreamController` (Task 2), `GatewayStream` (existing), `WavWriter`, `DictationClient`, `prefs()`.
- Produces: service behavior — gateway mode streams while recording; on stop it finalizes the stream; if streaming was never active or died, it uploads the full capture via the existing batch path.

- [ ] **Step 1: Read `startRecording()` and its record loop** in `WhisperAccessibilityService.kt`; note the exact line where the recording thread is started and where `pcmStream?.write(buf, 0, n)` happens.
- [ ] **Step 2: Read `stopAndTranscribe()`, `transcribeGateway(pcm)`, `finishWith(text)`, and `reset(msg)`**; note line numbers.
- [ ] **Step 3: Add fields** near the other service fields:
  ```kotlin
  private var gatewayStream: GatewayStream? = null
  private var streamController: GatewayStreamController? = null
  private var pcmChunker: PcmChunker? = null
  ```
- [ ] **Step 4: Add `startGatewayStreaming()`**, called at the end of `startRecording()` when `prefs().getBoolean("use_gateway", false)` is true. It:
  - reads `gateway_url` / `gateway_token`; if either is blank, returns without streaming (the stop path will show the existing "Set gateway URL and token" message)
  - derives the WS URL via `baseUrl.replace("http://", "ws://").replace("https://", "wss://").trimEnd('/')`
  - creates `GatewayStream(wsUrl, token)` and a `GatewayStreamController` whose `onResult` calls `finishWith(text)` when non-blank, else posts `toast("Gateway returned empty transcript")` + resets UI state via the handler; whose `onFallback` calls `transcribeGateway(pcm)`
  - creates `PcmChunker(8000)` (250 ms at 16 kHz mono PCM16)
  - calls `connect(controller)`
  - in `onConnected`, calls `showFeedback("Streaming to gateway…", 2000)`
- [ ] **Step 5: In the record loop**, immediately after `pcmStream?.write(buf, 0, n)`, add:
  ```kotlin
  if (streamController?.isActive == true) {
      pcmChunker?.push(buf.copyOf(n))?.let { chunk ->
          gatewayStream?.send(chunk)
      }
  }
  ```
- [ ] **Step 6: Replace the gateway branch in `stopAndTranscribe()`** (currently `if (prefs().getBoolean("use_gateway", false)) { transcribeGateway(pcm); return }`) with:
  ```kotlin
  if (prefs().getBoolean("use_gateway", false)) {
      val stream = gatewayStream
      if (stream != null && streamController?.isActive == true) {
          streamController?.prepareFallback(pcm)
          pcmChunker?.flush()?.let { stream.send(it) }
          stream.finalize()
          // result (or a post-finalize failure → fallback) arrives via the controller
      } else {
          transcribeGateway(pcm)  // never connected, or died mid-record
      }
      return
  }
  ```
- [ ] **Step 7: Add cleanup** — a `cleanupGatewayStream()` that closes and nulls `gatewayStream`, `streamController`, `pcmChunker`; call it from `finishWith` and from the error/reset paths so a second recording starts clean.
- [ ] **Step 8: Verify compile + suite**: `./gradlew assembleDebug` → BUILD SUCCESSFUL; `./gradlew test` → all tests pass (PcmChunker 6 + GatewayStreamController 7 + DictationClient 6 + upstream).
- [ ] **Step 9: Commit**: `git add app && git commit -m "feat: stream mic audio to gateway while recording, finalize on stop"`

---

## Task 4: Version bump, build, deliver

- [ ] **Step 1**: Bump `versionCode` 4 → 5, `versionName` → `"0.3.3-stream"` in `app/build.gradle.kts`.
- [ ] **Step 2**: `./gradlew assembleDebug` → BUILD SUCCESSFUL; confirm `app/build/outputs/apk/debug/app-debug.apk` exists.
- [ ] **Step 3**: `cp app/build/outputs/apk/debug/app-debug.apk /tmp/apk-serve/`; confirm the tailnet file server on port 9000 is still serving (`ss -tln | grep 9000`; if dead, restart `python3 -m http.server 9000 --bind 100.122.152.101` from `/tmp/apk-serve`).
- [ ] **Step 4**: Commit the version bump: `git add app && git commit -m "chore: release 0.3.3-stream"`.
- [ ] **Step 5**: Report the download URL `http://100.122.152.101:9000/app-debug.apk?v=3` and the manual e2e checklist: enable gateway mode, tap-and-hold a 20–30 s rambling sentence, confirm text lands cleaned; then kill Tailscale mid-recording and confirm the batch fallback still produces text.
