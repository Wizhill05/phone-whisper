package com.kafkasl.phonewhisper

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread
import kotlin.math.abs

class WhisperAccessibilityService : AccessibilityService() {

    companion object {
        var instance: WhisperAccessibilityService? = null
        private const val TAG = "PhoneWhisper"
        private const val SAMPLE_RATE = 16000
        private const val BTN_DP = 44
        private const val PAD_DP = 10
        private const val MARGIN_DP = 8
        private const val TAP_THRESHOLD_DP = 10
        private const val RING_DP = 56
        private const val FEEDBACK_OFFSET_DP = 64

        private const val COLOR_IDLE = 0xDD1C1C1E.toInt()
        private const val COLOR_RECORDING = 0xDDEF4444.toInt()
        private const val COLOR_BUSY = 0xDD6B6B6B.toInt()
        private const val COLOR_FEEDBACK_BG = 0xEE1C1C1E.toInt()
        private const val COLOR_RING = 0xFFE8EAED.toInt()

        private const val REC_NOTIFICATION_ID = 1
        private const val REC_CHANNEL_ID = "phonewhisper_rec"
    }

    private enum class State { IDLE, RECORDING, TRANSCRIBING }

    private var state = State.IDLE
    private var overlayView: FrameLayout? = null
    private var button: ImageView? = null
    private var spinner: ProgressBar? = null
    private var feedbackView: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var feedbackLayoutParams: WindowManager.LayoutParams? = null
    private var audioRecord: AudioRecord? = null
    private var pcmStream: ByteArrayOutputStream? = null
    @Volatile private var gatewayStream: GatewayStream? = null
    @Volatile private var streamController: GatewayStreamController? = null
    @Volatile private var pcmChunker: PcmChunker? = null
    @Volatile private var warmStream: GatewayStream? = null
    @Volatile private var streamFailedEarly = false
    @Volatile private var tuning: GatewayTuning.Tuning = GatewayTuning.DEFAULT
    private val handler = Handler(Looper.getMainLooper())
    private var visibilityController: BubbleVisibilityController? = null
    private val hideBubbleRunnable = Runnable {
        visibilityController?.onHideTimerFired()
    }
    private val checkKeyboardRunnable = Runnable {
        updateKeyboardState()
    }
    /** While the mic is hot, give the stream its connect budget, then go batch. */
    private val streamConnectTimeoutRunnable = Runnable {
        if (state == State.RECORDING && streamController?.isActive != true) {
            Log.w(TAG, "Gateway stream not connected in time; will upload at end")
            streamFailedEarly = true
            cleanupGatewayStream()
            showFeedback("Streaming unavailable — will upload at end", 2000)
        }
    }
    private val hideFeedback = Runnable {
        feedbackView?.animate()?.alpha(0f)?.setDuration(180)?.withEndAction {
            feedbackView?.visibility = View.GONE
        }?.start()
    }

    // Cloud-only transcription: audio goes to the user's configured gateway.
    // Direct OpenAI (TranscriberClient) remains only as a manual fallback.

    private val dp get() = resources.displayMetrics.density
    private val screenW get() = resources.displayMetrics.widthPixels
    private val screenH get() = resources.displayMetrics.heightPixels

    override fun onServiceConnected() {
        instance = this
        showOverlay()
        loadTuning()
        ensureWarmConnection()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                handler.removeCallbacks(checkKeyboardRunnable)
                handler.postDelayed(checkKeyboardRunnable, 100)
            }
        }
    }
    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacks(hideBubbleRunnable)
        handler.removeCallbacks(checkKeyboardRunnable)
        handler.removeCallbacks(streamConnectTimeoutRunnable)
        cleanupGatewayStream()
        cleanupWarmStream()
        stopMicForeground()
        instance = null
        removeOverlay()
        super.onDestroy()
    }

    private fun isOnlyShowWithKeyboard(): Boolean =
        prefs().getBoolean("only_show_with_keyboard", true)

    fun updateVisibilityPreference() {
        handler.post {
            visibilityController?.onPreferenceChanged(isOnlyShowWithKeyboard())
            updateKeyboardState()
        }
    }

    private fun updateKeyboardState() {
        val active = isKeyboardActive()
        visibilityController?.onKeyboardVisibilityChanged(active)
    }

    private fun isKeyboardActive(): Boolean {
        val windowList = try { windows } catch (e: Exception) { null }
        if (!windowList.isNullOrEmpty()) {
            val rect = Rect()
            for (win in windowList) {
                if (win.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    win.getBoundsInScreen(rect)
                    if (rect.height() > 0 && rect.width() > 0) {
                        return true
                    }
                }
            }
            return false
        }

        // Fallback: check if currently focused node is editable
        val root = rootInActiveWindow ?: return false
        try {
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null) {
                val isEdit = focused.isEditable
                focused.recycle()
                return isEdit
            }
        } finally {
            root.recycle()
        }
        return false
    }

    private fun markIdle() {
        state = State.IDLE
        setBusy(false)
        setAppearance(COLOR_IDLE)
        visibilityController?.onDictationFinished()
        // Re-warm the next stream while idle so the next tap starts hot.
        handler.post { ensureWarmConnection() }
    }

    private fun useGateway(): Boolean =
        prefs().getBoolean("use_gateway", true)

    private fun gatewayCreds(): Pair<String, String>? {
        val baseUrl = (prefs().getString("gateway_url", "") ?: "").trim()
        val token = prefs().getString("gateway_token", "") ?: ""
        return if (baseUrl.isBlank() || token.isBlank()) null else baseUrl to token
    }

    private fun wsUrl(baseUrl: String): String =
        baseUrl.replace("http://", "ws://").replace("https://", "wss://").trimEnd('/')

    private fun loadTuning() {
        val p = prefs()
        tuning = GatewayTuning.fromMap(
            mapOf(
                "chunk_bytes" to p.getLong("tuning_chunk_bytes", GatewayTuning.DEFAULT.chunkBytes.toLong()),
                "connect_timeout_ms" to p.getLong("tuning_connect_timeout_ms", GatewayTuning.DEFAULT.connectTimeoutMs),
                "result_timeout_ms" to p.getLong("tuning_result_timeout_ms", GatewayTuning.DEFAULT.resultTimeoutMs),
            )
        )
        val creds = gatewayCreds() ?: return
        GatewayTuning.fetch(creds.first, creds.second) { fresh ->
            tuning = fresh
            prefs().edit()
                .putLong("tuning_chunk_bytes", fresh.chunkBytes.toLong())
                .putLong("tuning_connect_timeout_ms", fresh.connectTimeoutMs)
                .putLong("tuning_result_timeout_ms", fresh.resultTimeoutMs)
                .apply()
        }
    }

    /**
     * Pre-connects a warm gateway stream while idle so the mic never waits on
     * a handshake. Called at service start, when the bubble becomes visible,
     * and after each dictation. Never throws.
     */
    private fun ensureWarmConnection() {
        try {
            if (state != State.IDLE) return
            if (!useGateway()) return
            if (warmStream != null || gatewayStream != null) return
            val creds = gatewayCreds() ?: return
            val stream = GatewayStream(
                wsUrl(creds.first), creds.second,
                resultTimeoutMs = tuning.resultTimeoutMs,
                connectTimeoutMs = tuning.connectTimeoutMs,
            )
            warmStream = stream
            stream.connect(object : GatewayStream.Listener {
                override fun onConnected() {}
                override fun onResult(json: JSONObject) {}
                override fun onFailure(message: String) {
                    if (warmStream === stream) {
                        stream.close()
                        warmStream = null
                    }
                }
            })
        } catch (t: Throwable) {
            Log.w(TAG, "Warm connection failed", t)
        }
    }

    private fun cleanupWarmStream() {
        warmStream?.close()
        warmStream = null
    }

    // --- Overlay ---

    private fun showOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val buttonSize = (BTN_DP * dp).toInt()
        val ringSize = (RING_DP * dp).toInt()
        val pad = (PAD_DP * dp).toInt()
        val margin = (MARGIN_DP * dp).toInt()

        val ring = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(COLOR_RING)
            visibility = View.GONE
        }

        val img = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(pad, pad, pad, pad)
            background = circle(COLOR_IDLE)
        }

        val overlay = FrameLayout(this).apply {
            addView(ring, FrameLayout.LayoutParams(ringSize, ringSize, Gravity.CENTER))
            addView(img, FrameLayout.LayoutParams(buttonSize, buttonSize, Gravity.CENTER))
        }

        val params = WindowManager.LayoutParams(
            ringSize, ringSize,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenW - ringSize - margin
            y = screenH / 2 - ringSize / 2
        }

        var startX = 0; var startY = 0
        var touchX = 0f; var touchY = 0f

        overlay.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    touchX = ev.rawX; touchY = ev.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (ev.rawX - touchX).toInt()
                    params.y = startY + (ev.rawY - touchY).toInt()
                    wm.updateViewLayout(v, params)
                    feedbackLayoutParams?.let {
                        positionFeedback(it, params)
                        wm.updateViewLayout(feedbackView, it)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = abs(ev.rawX - touchX) + abs(ev.rawY - touchY)
                    if (moved < TAP_THRESHOLD_DP * dp) {
                        onTap()
                    } else {
                        params.x = if (params.x + ringSize / 2 > screenW / 2)
                            screenW - ringSize - margin else margin
                        wm.updateViewLayout(v, params)
                        feedbackLayoutParams?.let {
                            positionFeedback(it, params)
                            wm.updateViewLayout(feedbackView, it)
                        }
                    }
                    true
                }
                else -> false
            }
        }

        val feedback = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
            background = pill(COLOR_FEEDBACK_BG)
            alpha = 0f
            visibility = View.GONE
        }

        val feedbackParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        positionFeedback(feedbackParams, params)

        val keyboardActive = isKeyboardActive()
        val onlyShowWithKeyboard = isOnlyShowWithKeyboard()

        if (onlyShowWithKeyboard && !keyboardActive) {
            overlay.alpha = 0f
            overlay.visibility = View.GONE
        }

        wm.addView(overlay, params)
        wm.addView(feedback, feedbackParams)
        overlayView = overlay
        button = img
        spinner = ring
        feedbackView = feedback
        layoutParams = params
        feedbackLayoutParams = feedbackParams

        val controller = BubbleVisibilityController(
            onShow = {
                overlayView?.let { v ->
                    v.animate().cancel()
                    if (v.visibility != View.VISIBLE) {
                        v.alpha = 0f
                        v.visibility = View.VISIBLE
                        v.animate().alpha(1f).setDuration(150).start()
                    } else {
                        v.alpha = 1f
                    }
                }
                // Bubble visible (keyboard shown): warm the stream now, not on tap.
                handler.post { ensureWarmConnection() }
            },
            onHide = {
                overlayView?.let { v ->
                    v.animate().cancel()
                    v.animate().alpha(0f).setDuration(150).withEndAction {
                        v.visibility = View.GONE
                    }.start()
                }
                feedbackView?.let { fv ->
                    fv.visibility = View.GONE
                    fv.alpha = 0f
                }
            },
            onScheduleHide = { delayMs ->
                handler.removeCallbacks(hideBubbleRunnable)
                handler.postDelayed(hideBubbleRunnable, delayMs)
            },
            onCancelScheduledHide = {
                handler.removeCallbacks(hideBubbleRunnable)
            }
        )
        visibilityController = controller
        controller.init(keyboardActive, onlyShowWithKeyboard)
    }

    private fun removeOverlay() {
        handler.removeCallbacks(hideBubbleRunnable)
        handler.removeCallbacks(checkKeyboardRunnable)
        visibilityController = null
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayView?.let {
            wm.removeView(it)
            overlayView = null
        }
        feedbackView?.let {
            wm.removeView(it)
            feedbackView = null
        }
        button = null
        spinner = null
        layoutParams = null
        feedbackLayoutParams = null
    }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(color)
    }

    private fun pill(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 16 * dp
        setColor(color)
    }

    private fun setAppearance(color: Int) {
        handler.post { button?.background = circle(color) }
    }

    private fun setBusy(visible: Boolean) {
        handler.post {
            spinner?.visibility = if (visible) View.VISIBLE else View.GONE
        }
    }

    private fun positionFeedback(
        feedbackParams: WindowManager.LayoutParams,
        bubbleParams: WindowManager.LayoutParams
    ) {
        val margin = (MARGIN_DP * dp).toInt()
        val offset = (FEEDBACK_OFFSET_DP * dp).toInt()
        feedbackParams.x = maxOf(margin, bubbleParams.x - offset)
        feedbackParams.y = maxOf(margin, bubbleParams.y - margin)
    }

    private fun showFeedback(text: String, durationMs: Long = 2000) {
        handler.post {
            val view = feedbackView ?: return@post
            val bubbleParams = layoutParams ?: return@post
            val feedbackParams = feedbackLayoutParams ?: return@post
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager

            // Don't show feedback popup if the bubble is hidden
            if (overlayView?.visibility != View.VISIBLE) return@post

            view.text = text
            positionFeedback(feedbackParams, bubbleParams)
            wm.updateViewLayout(view, feedbackParams)

            handler.removeCallbacks(hideFeedback)
            view.animate().cancel()
            view.visibility = View.VISIBLE
            view.alpha = 0f
            view.animate().alpha(1f).setDuration(120).start()
            handler.postDelayed(hideFeedback, durationMs)
        }
    }

    private fun startPulse() {
        button?.let {
            it.animate().alpha(0.4f).setDuration(500).withEndAction {
                it.animate().alpha(1f).setDuration(500).withEndAction {
                    if (state == State.RECORDING) startPulse()
                }.start()
            }.start()
        }
    }

    private fun stopPulse() {
        button?.animate()?.cancel()
        button?.alpha = 1f
    }

    // --- State machine ---

    private fun onTap() {
        when (state) {
            State.IDLE -> startRecording()
            State.RECORDING -> stopAndTranscribe()
            State.TRANSCRIBING -> {}
        }
    }

    private fun startRecording() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            toast("Grant audio permission in Phone Whisper app"); return
        }

        val bufSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
            )
        } catch (_: SecurityException) { toast("Audio permission denied"); return }

        pcmStream = ByteArrayOutputStream()
        audioRecord!!.startRecording()
        state = State.RECORDING
        streamFailedEarly = false
        visibilityController?.onRecordingStarted()
        setBusy(false)
        setAppearance(COLOR_RECORDING)
        startPulse()
        startMicForeground()

        if (useGateway() && gatewayCreds() != null) {
            // Adopt the warm socket when live; otherwise connect fresh and
            // drop the warming socket so it cannot dangle. Either way the
            // mic is already hot — recording never waits.
            val warming = warmStream
            warmStream = null
            val adopted = warming?.takeIf { it.isConnected } ?: run {
                warming?.close()
                null
            }
            startGatewayStreaming(adopted)
            handler.postDelayed(streamConnectTimeoutRunnable, tuning.connectTimeoutMs)
        }

        thread {
            val buf = ByteArray(bufSize)
            while (state == State.RECORDING) {
                val n = audioRecord?.read(buf, 0, buf.size) ?: break
                if (n > 0) {
                    pcmStream?.write(buf, 0, n)
                    val stream = gatewayStream
                    if (stream != null) {
                        pcmChunker?.push(buf.copyOf(n))?.let { chunk ->
                            stream.send(chunk)
                        }
                    }
                }
            }
        }
    }

    private fun stopAndTranscribe() {
        state = State.TRANSCRIBING
        visibilityController?.onTranscribingStarted()
        stopPulse()
        setAppearance(COLOR_BUSY)
        setBusy(true)
        handler.removeCallbacks(streamConnectTimeoutRunnable)

        showFeedback(activeModeLabel(), 2000)

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        stopMicForeground()

        val pcm = pcmStream?.toByteArray() ?: ByteArray(0)
        pcmStream = null

        showFeedback("Captured ${pcm.size} bytes of audio", 1500)

        if (pcm.isEmpty()) { reset("No audio captured"); return }

        if (useGateway()) {
            if (!streamFailedEarly) {
                val stream = gatewayStream
                val controller = streamController
                if (stream != null && controller != null) {
                    controller.prepareFallback(pcm)
                    pcmChunker?.flush()?.let { stream.send(it) }
                    val finalized = stream.finalize()
                    if (!finalized) {
                        cleanupGatewayStream()
                        transcribeGateway(pcm)
                    }
                } else {
                    cleanupGatewayStream()
                    transcribeGateway(pcm)
                }
                return
            }
            // Stream never connected while the mic was hot: batch upload.
            streamFailedEarly = false
            cleanupGatewayStream()
            transcribeGateway(pcm)
            return
        }

        // Manual fallback: direct OpenAI transcription.
        transcribeApi(pcm)
    }

    private fun transcribeApi(pcm: ByteArray) {
        val wav = WavWriter.encode(pcm)
        val apiKey = prefs().getString("api_key", "") ?: ""
        if (apiKey.isBlank()) { reset("Set API key in Phone Whisper app"); return }

        TranscriberClient.transcribe(wav, apiKey) { result ->
            if (result.text != null && result.text.isNotBlank()) {
                handleTranscriptionResult(result.text)
            } else {
                handler.post {
                    toast("Error: ${result.error ?: "empty transcript"}")
                    markIdle()
                }
            }
        }
    }

    private fun activeModeLabel(): String = when {
        useGateway() -> "VPS gateway"
        else -> "Cloud (OpenAI)"
    }

    private fun transcribeGateway(pcm: ByteArray) {
        val wav = WavWriter.encode(pcm)
        val creds = gatewayCreds()
        if (creds == null) {
            val msg = "Set gateway URL and token in the Phone Whisper app"
            showFeedback(msg, 5000)
            reset(msg)
            return
        }
        val (baseUrl, token) = creds

        Log.i(TAG, "VPS gateway: POST $baseUrl/v1/dictation (wav ${wav.size} bytes)")
        showFeedback("Sending ${wav.size} bytes to gateway…", 1500)
        DictationClient.dictate(wav, baseUrl, token) { result ->
            val text = result.text
            if (!text.isNullOrBlank()) {
                Log.i(TAG, "VPS gateway: got ${text.length} chars (clean=${result.isClean})")
                if (result.isClean) {
                    // Single pass: gateway already cleaned — finish immediately.
                    finishWith(text)
                } else {
                    polishRawText(text)
                }
            } else {
                Log.w(TAG, "VPS gateway failed: ${result.error}")
                showFeedback("Gateway: ${result.error ?: "failed"}", 5000)
                handler.post {
                    toast("Error: ${result.error ?: "gateway error"}")
                    markIdle()
                }
            }
        }
    }

    private fun handleStreamResult(result: StreamResult) {
        if (result.text.isBlank()) {
            cleanupGatewayStream()
            handler.post {
                toast("Gateway returned empty transcript")
                markIdle()
            }
            return
        }
        if (result.isClean) {
            // Single pass: clean_text arrived — skip the second cleanup call.
            finishWith(result.text)
            return
        }
        polishRawText(result.text)
    }

    /** One separate cleanup call, only for raw-text-only results. */
    private fun polishRawText(raw: String) {
        val creds = gatewayCreds()
        if (creds == null) {
            finishWith(raw)
            return
        }
        showFeedback("Polishing…", 1500)
        val prompt = prefs().getString("post_processing_prompt", null)
        GatewayCleanupClient.clean(raw, creds.first, creds.second, prompt) { result ->
            if (result.error != null) {
                Log.w(TAG, "Gateway cleanup error: ${result.error}")
                showFeedback("Cleanup failed — used raw transcript", 3000)
            }
            finishWith(result.cleanText)
        }
    }

    private fun startGatewayStreaming(adopted: GatewayStream? = null) {
        val creds = gatewayCreds() ?: return
        val (baseUrl, token) = creds

        val stream = adopted ?: GatewayStream(
            wsUrl(baseUrl), token,
            resultTimeoutMs = tuning.resultTimeoutMs,
            connectTimeoutMs = tuning.connectTimeoutMs,
        )
        val chunker = PcmChunker(tuning.chunkBytes)
        val controller = GatewayStreamController(
            onResult = { result -> handleStreamResult(result) },
            onFallback = { pcm ->
                cleanupGatewayStream()
                transcribeGateway(pcm)
            },
            onConnected = {
                showFeedback("Streaming to gateway…", 2000)
            }
        )

        gatewayStream = stream
        pcmChunker = chunker
        streamController = controller
        if (adopted != null) {
            stream.setListener(controller)
            controller.onConnected()
        } else {
            stream.connect(controller)
        }
    }

    private fun cleanupGatewayStream() {
        gatewayStream?.close()
        gatewayStream = null
        streamController = null
        pcmChunker = null
    }

    private fun finishWith(text: String) {
        cleanupGatewayStream()
        handler.post {
            injectText(text)
            markIdle()
        }
    }

    private fun handleTranscriptionResult(text: String?) {
        if (text.isNullOrBlank()) {
            handler.post {
                toast("No speech detected")
                markIdle()
            }
            return
        }

        val usePostProcessing = prefs().getBoolean("use_post_processing", false)
        val apiKey = prefs().getString("api_key", "") ?: ""

        if (usePostProcessing) {
            if (apiKey.isBlank()) {
                handler.post {
                    toast("Post-processing needs API key. Using raw text.")
                    injectText(text)
                    markIdle()
                }
                return
            }

            val prompt = prefs().getString("post_processing_prompt", PostProcessor.DEFAULT_PROMPT) ?: PostProcessor.DEFAULT_PROMPT

            PostProcessor.process(text, prompt, apiKey) { result ->
                handler.post {
                    if (result.text != null && result.text.isNotBlank()) {
                        injectText(result.text)
                    } else {
                        injectText(text, feedback = "Cleanup failed — raw copied to clipboard", feedbackDurationMs = 3000)
                    }
                    markIdle()
                }
            }
        } else {
            handler.post {
                injectText(text)
                markIdle()
            }
        }
    }

    private fun reset(msg: String) {
        cleanupGatewayStream()
        stopMicForeground()
        toast(msg)
        markIdle()
    }

    // --- Text injection ---

    private fun injectText(
        text: String,
        feedback: String? = "Copied to clipboard",
        feedbackDurationMs: Long = 2000
    ) {
        val clip = ClipData.newPlainText("phonewhisper", text)
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
        feedback?.let { showFeedback(it, feedbackDurationMs) }

        val candidates = findInjectionCandidates()
        Log.i(TAG, "Injecting text into ${candidates.size} candidate node(s)")

        var injected = false
        try {
            for (candidate in candidates) {
                if (tryInjectIntoNode(candidate, text)) {
                    injected = true
                    break
                }
            }
        } finally {
            candidates.forEach { it.recycle() }
        }

        Log.i(TAG, if (injected) "Text injection action reported success" else "No injection action succeeded; clipboard fallback only")
    }

    private fun findInjectionCandidates(): List<AccessibilityNodeInfo> {
        val candidates = mutableListOf<AccessibilityNodeInfo>()

        rootInActiveWindow?.let { root ->
            Log.i(TAG, "Active root: package=${root.packageName} class=${root.className}")
            collectInjectionCandidates(root, candidates)
            root.recycle()
        }

        windows
            ?.filter { it.isActive || it.isFocused }
            ?.forEach { window ->
                val root = window.root ?: return@forEach
                Log.i(
                    TAG,
                    "Window root: type=${window.type} active=${window.isActive} focused=${window.isFocused} package=${root.packageName} class=${root.className}"
                )
                collectInjectionCandidates(root, candidates)
                root.recycle()
            }

        return candidates.sortedByDescending(::candidateScore)
    }

    private fun collectInjectionCandidates(
        root: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { out += it }
        root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)?.let { out += it }
        collectPotentialTargets(root, out)
    }

    private fun collectPotentialTargets(
        node: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        if (isPotentialInjectionTarget(node)) {
            out += AccessibilityNodeInfo.obtain(node)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                collectPotentialTargets(child, out)
            } finally {
                child.recycle()
            }
        }
    }

    private fun isPotentialInjectionTarget(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString().orEmpty()
        return node.isFocused ||
            node.isEditable ||
            className.contains("EditText") ||
            className.contains("TerminalView") ||
            findCustomPasteAction(node) != null
    }

    private fun candidateScore(node: AccessibilityNodeInfo): Int {
        val className = node.className?.toString().orEmpty()
        var score = 0
        if (findCustomPasteAction(node) != null) score += 100
        if (className.contains("TerminalView")) score += 80
        if (node.isEditable) score += 60
        if (node.isFocused) score += 40
        if (className.contains("EditText")) score += 20
        return score
    }

    private fun tryInjectIntoNode(node: AccessibilityNodeInfo, text: String): Boolean {
        logNode("Trying node", node)

        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

        // First: cursor-aware set-text (commitText equivalent) with verification.
        if (node.isEditable || node.className?.toString()?.contains("EditText") == true) {
            val current = node.text?.toString().orEmpty()
            val start = if (node.textSelectionStart >= 0) node.textSelectionStart else current.length
            val end = if (node.textSelectionEnd >= 0) node.textSelectionEnd else start
            val updated = TextInserter.buildUpdatedText(current, start, end, text)
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    updated
                )
            }
            val setTextOk = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            Log.i(TAG, "ACTION_SET_TEXT (verified) => $setTextOk")
            if (setTextOk && TextInserter.verifyInserted(node.text, text)) {
                return true
            }
        }

        findCustomPasteAction(node)?.let { action ->
            val ok = node.performAction(action.id)
            Log.i(TAG, "Custom action '${action.label}' (${action.id}) => $ok")
            if (ok) return true
        }

        val pasteOk = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        Log.i(TAG, "ACTION_PASTE => $pasteOk")
        if (pasteOk) return true

        if (node.isEditable || node.className?.toString()?.contains("EditText") == true) {
            val current = node.text?.toString().orEmpty()
            val start = if (node.textSelectionStart >= 0) node.textSelectionStart else current.length
            val end = if (node.textSelectionEnd >= 0) node.textSelectionEnd else start
            val updated = TextInserter.buildUpdatedText(current, start, end, text)
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    updated
                )
            }
            val setTextOk = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            Log.i(TAG, "ACTION_SET_TEXT => $setTextOk")
            if (setTextOk) return true
        }

        return false
    }

    private fun findCustomPasteAction(node: AccessibilityNodeInfo): AccessibilityNodeInfo.AccessibilityAction? =
        node.actionList.firstOrNull { action ->
            action.label?.toString()?.contains("paste", ignoreCase = true) == true
        }

    private fun logNode(prefix: String, node: AccessibilityNodeInfo) {
        val actions = node.actionList.joinToString { action ->
            action.label?.toString() ?: action.id.toString()
        }
        Log.i(
            TAG,
            "$prefix package=${node.packageName} class=${node.className} focused=${node.isFocused} editable=${node.isEditable} text=${node.text} desc=${node.contentDescription} actions=[$actions]"
        )
    }

    // --- Mic foreground keep-alive ---

    /** Keeps recording/streaming alive under a microphone foreground service. */
    private fun startMicForeground() {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(REC_CHANNEL_ID, "Dictation", NotificationManager.IMPORTANCE_LOW)
            )
            val intent = Intent(this, MainActivity::class.java)
            val pending = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = Notification.Builder(this, REC_CHANNEL_ID)
                .setContentTitle("Phone Whisper recording")
                .setContentText("Tap the bubble again to stop")
                .setSmallIcon(R.drawable.ic_mic)
                .setContentIntent(pending)
                .setOngoing(true)
                .build()
            startForeground(
                REC_NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Could not start mic foreground service", t)
        }
    }

    private fun stopMicForeground() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (t: Throwable) {
            Log.w(TAG, "Could not stop mic foreground service", t)
        }
    }

    private fun prefs() = getSharedPreferences("phonewhisper", MODE_PRIVATE)
    private fun toast(msg: String) { handler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() } }
}
