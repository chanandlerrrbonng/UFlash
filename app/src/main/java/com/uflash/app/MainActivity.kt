package com.uflash.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.camera2.*
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Range
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

// ── Top-level constants shared by MainActivity and SimpleDecoder ──────────
const val BIT_MS   = 500L    // 500ms per bit
const val START_MS = 3000L   // 3s HIGH preamble
const val STOP_MS  = 4000L   // 4s LOW postamble

// How long after camera opens before the decoder is allowed to arm.
// = 1s AE Phase-1 settle  +  ~500ms threshold window warm-up  +  500ms margin
const val CAM_READY_DELAY_MS = 2000L



// ═══════════════════════════════════════════════════════
//  PROTOCOL  (minimal — raw ASCII bits, time-based)
//  TX: 3s HIGH (start) | bits (500ms each) | 4s LOW (stop)
//  RX: lock exposure → measure brightness → edge-list decode
//
//  FIXES APPLIED (v5):
//   FIX 1 — decode(runStartMs + BIT_MS): last 0-bit no longer swallowed by STOP
//   FIX 2 — removed trailing-zero trim: valid trailing data bits preserved
//   FIX 3 — two-phase AE: let camera settle 1s before locking exposure
//   FIX 4 — absolute TX timing: jitter never accumulates across bits
//   FIX 5 — camera-ready gate: decoder stays disarmed for CAM_READY_DELAY_MS
//            after openCamera(); UI shows a live countdown so the user knows
//            exactly when to start transmitting. This is the root cause of the
//            "needs 3–4 tries" issue: on early tries the AE was still hunting,
//            brightness was fluctuating, and edges were mis-tagged.
//   FIX 6 — auto-reset after DONE: after a successful (or failed) decode the
//            decoder automatically returns to IDLE after 4s so the user doesn't
//            need to press Reset between attempts.
// ═══════════════════════════════════════════════════════

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG  = "UFlash"
        private const val PERM = 100
    }

    // ── UI ────────────────────────────────────────────────────────
    private lateinit var btnTx: Button
    private lateinit var btnRx: Button
    private lateinit var layoutTx: LinearLayout
    private lateinit var layoutRx: LinearLayout

    // TX
    private lateinit var etMsg: EditText
    private lateinit var btnSend: Button
    private lateinit var tvTxStatus: TextView
    private lateinit var tvTxProgress: TextView
    private lateinit var pbTx: ProgressBar

    // RX
    private lateinit var surfaceView: SurfaceView
    private lateinit var tvBigBrightness: TextView
    private lateinit var pbBrightness: ProgressBar
    private lateinit var tvThreshold: TextView
    private lateinit var tvState: TextView
    private lateinit var tvDecoded: TextView
    private lateinit var tvFps: TextView
    private lateinit var btnClear: Button
    private lateinit var btnCalibrate: Button

    // ── Camera2 ───────────────────────────────────────────────────
    private var camManager: CameraManager? = null
    private var torchCamId: String? = null
    private var rxCamId: String? = null
    private var captureSession: CameraCaptureSession? = null
    private var cameraDevice: CameraDevice? = null
    private val cameraThread = HandlerThread("CamThread").also { it.start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private var imageReader: android.media.ImageReader? = null

    // ── State ─────────────────────────────────────────────────────
    private var isTxMode = true
    private val isTransmitting = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val txExecutor = Executors.newSingleThreadExecutor()
    private var isTorchOn = false
    private val decoder = SimpleDecoder()

    // ── FIX 5: camera-ready countdown runnables ───────────────────
    private var camReadyRunnable: Runnable? = null
    private var countdownRunnable: Runnable? = null

    // ══════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ══════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        camManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        torchCamId = findTorchId()
        rxCamId    = findBackCamId()
        buildUI()
        setupListeners()
        if (!hasPerm()) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), PERM)
        showTxMode()
    }

    override fun onDestroy() {
        super.onDestroy()
        closeCam()
        stopTorch()
        txExecutor.shutdown()
        cameraThread.quitSafely()
        cancelReadyCountdown()
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<String>, gr: IntArray) {
        super.onRequestPermissionsResult(rc, p, gr)
        if (rc == PERM && gr.isNotEmpty() && gr[0] == PackageManager.PERMISSION_GRANTED) {
            if (!isTxMode) waitForSurfaceThenOpen()
        }
    }

    // ══════════════════════════════════════════════════════════════
    // BUILD UI
    // ══════════════════════════════════════════════════════════════

    private fun buildUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(c("#0D1117"))
            setPadding(d(20), d(48), d(20), d(40))
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(c("#0D1117")); addView(root) })

        // Header
        root.addView(tv("⚡ UFlash", 24f, "#00E5FF", true).also { it.gravity = Gravity.CENTER }, lp(bm = d(4)))
        root.addView(tv("Underwater Flash Comm  ·  ${BIT_MS}ms/bit  ·  Raw OOK", 10f, "#607D8B").also { it.gravity = Gravity.CENTER }, lp(bm = d(18)))

        // Mode buttons
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnTx = btn("📡  TRANSMIT", "#00E5FF", "#000000")
        btnRx = btn("📷  RECEIVE",  "#37474F", "#FFFFFF")
        row.addView(btnTx, lpW(1f, d(8))); row.addView(btnRx, lpW(1f))
        root.addView(row, lp(bm = d(20)))

        // ── TX layout ───────────────────────────────────────────
        layoutTx = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layoutTx.addView(tv("Message:", 13f, "#B0BEC5"), lp(bm = d(6)))
        etMsg = EditText(this).apply {
            setText("Hi"); textSize = 18f
            setTextColor(c("#E0E0E0")); setBackgroundColor(c("#161B22"))
            setHintTextColor(c("#455A64"))
            setPadding(d(16), d(12), d(16), d(12))
        }
        layoutTx.addView(etMsg, lp(bm = d(10)))
        layoutTx.addView(card("START: 3s ON  ·  Bit: 500ms ON=1 / OFF=0  ·  STOP: 4s OFF"), lp(bm = d(12)))
        btnSend = btn("⚡  TRANSMIT", "#00E5FF", "#000000").apply { textSize = 15f }
        layoutTx.addView(btnSend, lp(bm = d(10)))
        pbTx = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            visibility = View.GONE
            progressDrawable?.setColorFilter(c("#00E5FF"), android.graphics.PorterDuff.Mode.SRC_IN)
        }
        layoutTx.addView(pbTx, lp(bm = d(4)))
        tvTxProgress = tv("", 11f, "#607D8B").also { it.gravity = Gravity.CENTER }
        layoutTx.addView(tvTxProgress, lp(bm = d(6)))
        tvTxStatus = tv("✅  Ready", 14f, "#4CAF50", true).also { it.gravity = Gravity.CENTER }
        layoutTx.addView(tvTxStatus, lp())
        root.addView(layoutTx, lp())

        // ── RX layout ───────────────────────────────────────────
        layoutRx = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }

        surfaceView = SurfaceView(this)
        layoutRx.addView(surfaceView, lp(height = d(180), bm = d(8)))

        tvBigBrightness = TextView(this).apply {
            text = "---"; textSize = 56f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(c("#00E5FF")); setBackgroundColor(c("#0D1117"))
            gravity = Gravity.CENTER; setPadding(0, d(4), 0, d(4))
        }
        layoutRx.addView(tvBigBrightness, lp(bm = d(4)))

        pbBrightness = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 255; progress = 0
            progressDrawable?.setColorFilter(c("#00E5FF"), android.graphics.PorterDuff.Mode.SRC_IN)
        }
        layoutRx.addView(pbBrightness, lp(bm = d(6)))

        val infoRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tvThreshold = badge("THR: --")
        tvFps       = badge("FPS: --")
        infoRow.addView(tvThreshold, lpW(1f, d(4))); infoRow.addView(tvFps, lpW(1f))
        layoutRx.addView(infoRow, lp(bm = d(4)))

        tvState = badge("🔧 IDLE — waiting for flash")
        layoutRx.addView(tvState, lp(bm = d(10)))

        layoutRx.addView(tv("Decoded message:", 13f, "#B0BEC5"), lp(bm = d(4)))
        tvDecoded = TextView(this).apply {
            text = "(waiting…)"; textSize = 30f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(c("#00FF88")); setBackgroundColor(c("#161B22"))
            setPadding(d(16), d(16), d(16), d(16)); gravity = Gravity.CENTER
        }
        layoutRx.addView(tvDecoded, lp(bm = d(12)))

        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnCalibrate = btn("🎯 Calibrate", "#1B3A2D", "#00FF88")
        btnClear     = btn("🔄 Reset",     "#263238", "#FFFFFF")
        btns.addView(btnCalibrate, lpW(1f, d(8))); btns.addView(btnClear, lpW(1f))
        layoutRx.addView(btns, lp())
        root.addView(layoutRx, lp())
    }

    // ══════════════════════════════════════════════════════════════
    // LISTENERS
    // ══════════════════════════════════════════════════════════════

    private fun setupListeners() {
        btnTx.setOnClickListener { showTxMode() }
        btnRx.setOnClickListener { showRxMode() }
        btnSend.setOnClickListener { startTx() }
        btnClear.setOnClickListener {
            cancelReadyCountdown()
            decoder.reset()
            tvDecoded.text = "(waiting…)"
            tvState.text = "🔧 IDLE — waiting for flash"
            // Re-arm the camera-ready gate since the user manually reset
            startReadyCountdown(CAM_READY_DELAY_MS)
        }
        btnCalibrate.setOnClickListener {
            decoder.calibrate()
            Toast.makeText(this, "Calibrated! Point at flash and receive.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showTxMode() {
        isTxMode = true
        layoutTx.visibility = View.VISIBLE; layoutRx.visibility = View.GONE
        btnTx.setBackgroundColor(c("#00E5FF")); btnTx.setTextColor(c("#000000"))
        btnRx.setBackgroundColor(c("#37474F")); btnRx.setTextColor(c("#FFFFFF"))
        cancelReadyCountdown()
        closeCam()
    }

    private fun showRxMode() {
        isTxMode = false
        layoutTx.visibility = View.GONE; layoutRx.visibility = View.VISIBLE
        btnRx.setBackgroundColor(c("#00E5FF")); btnRx.setTextColor(c("#000000"))
        btnTx.setBackgroundColor(c("#37474F")); btnTx.setTextColor(c("#FFFFFF"))
        if (hasPerm()) waitForSurfaceThenOpen()
        else ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), PERM)
    }

    private fun waitForSurfaceThenOpen() {
        val holder = surfaceView.holder
        if (holder.surface != null && holder.surface.isValid) {
            openCamera()
            return
        }
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) {
                holder.removeCallback(this)
                openCamera()
            }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) { closeCam() }
        })
    }

    // ══════════════════════════════════════════════════════════════
    // FIX 5 — Camera-ready countdown
    //
    // Called immediately after openCamera() succeeds.  The decoder's
    // isReady flag is false until markReady() is called, so even if
    // a flash is detected during the settle window the state machine
    // will not arm.  A live countdown in tvState tells the user
    // exactly when it's safe to start transmitting.
    // ══════════════════════════════════════════════════════════════

    private fun startReadyCountdown(totalMs: Long) {
        cancelReadyCountdown()
        decoder.markNotReady()

        val startTime = System.currentTimeMillis()
        val tickMs    = 250L

        fun tickLabel(): String {
            val elapsed  = System.currentTimeMillis() - startTime
            val remaining = ((totalMs - elapsed) / 1000.0).coerceAtLeast(0.0)
            return "⏳ Camera warming up…  %.1fs".format(remaining)
        }

        // Live countdown ticks
        val tick = object : Runnable {
            override fun run() {
                tvState.text = tickLabel()
                tvState.setBackgroundColor(c("#2B1D00"))
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed < totalMs) mainHandler.postDelayed(this, tickMs)
            }
        }
        countdownRunnable = tick
        mainHandler.post(tick)

        // Ready trigger
        val ready = Runnable {
            decoder.markReady()
            tvState.text = "✅ Ready — start transmitting now!"
            tvState.setBackgroundColor(c("#0D2B1A"))
            Log.d("UFlash", "★ Camera ready — decoder armed")
        }
        camReadyRunnable = ready
        mainHandler.postDelayed(ready, totalMs)
    }

    private fun cancelReadyCountdown() {
        camReadyRunnable?.let  { mainHandler.removeCallbacks(it) }
        countdownRunnable?.let { mainHandler.removeCallbacks(it) }
        camReadyRunnable  = null
        countdownRunnable = null
    }

    // ══════════════════════════════════════════════════════════════
    // TRANSMITTER
    //
    // FIX 4: Absolute timing from a fixed t0 so jitter never
    // accumulates — each bit target is computed from txStart,
    // not from "previous sleep finished".
    // ══════════════════════════════════════════════════════════════

    private fun startTx() {
        if (isTransmitting.get()) return
        val msg = etMsg.text.toString()
        if (msg.isEmpty()) { Toast.makeText(this, "Enter a message", Toast.LENGTH_SHORT).show(); return }
        if (torchCamId == null) { Toast.makeText(this, "No torch", Toast.LENGTH_SHORT).show(); return }

        val bytes = msg.toByteArray(Charsets.UTF_8)
        val bits  = bytes.flatMap { b -> FourB5B.encodeByte(b.toInt() and 0xFF) }
        val estSec = (START_MS + bits.size * BIT_MS + STOP_MS) / 1000

        isTransmitting.set(true)
        btnSend.isEnabled = false; btnSend.text = "📡 Sending…"
        pbTx.max = bits.size; pbTx.progress = 0; pbTx.visibility = View.VISIBLE
        tvTxStatus.text = "🔵 ~${estSec}s  ·  ${bits.size} bits"
        tvTxStatus.setTextColor(c("#FFA726"))
        Log.d(TAG, "TX '${msg}' → bits: ${bits.joinToString("")}")

        txExecutor.execute {
            try {
                val txStart = System.currentTimeMillis()

                // ── START marker: 3s HIGH ──────────────────────────────
                torch(true)
                sleepUntil(txStart + START_MS)
                torch(false)

                // ── FIX 4: absolute per-bit timing from dataStart ──────
                val dataStart = txStart + START_MS
                bits.forEachIndexed { i, bit ->
                    val targetMs = dataStart + i * BIT_MS
                    sleepUntil(targetMs)
                    torch(bit == 1)
                    mainHandler.post {
                        pbTx.progress = i + 1
                        tvTxProgress.text = "Bit ${i + 1}/${bits.size}"
                    }
                }

                // ── STOP marker: 4s LOW ────────────────────────────────
                val stopStart = dataStart + bits.size * BIT_MS
                sleepUntil(stopStart)
                torch(false)
                sleepUntil(stopStart + STOP_MS)

                mainHandler.post {
                    tvTxStatus.text  = "✅ Done! ${bits.size} bits"
                    tvTxStatus.setTextColor(c("#4CAF50"))
                    tvTxProgress.text = ""
                    btnSend.isEnabled = true; btnSend.text = "⚡  TRANSMIT"
                    pbTx.visibility = View.GONE
                }
            } catch (e: Exception) {
                mainHandler.post {
                    tvTxStatus.text = "❌ ${e.message}"
                    tvTxStatus.setTextColor(c("#F44336"))
                    btnSend.isEnabled = true; btnSend.text = "⚡  TRANSMIT"
                    pbTx.visibility = View.GONE
                }
            } finally {
                isTransmitting.set(false)
                stopTorch()
            }
        }
    }

    /** Sleep until an absolute wall-clock millisecond, absorbing jitter. */
    private fun sleepUntil(targetMs: Long) {
        val remaining = targetMs - System.currentTimeMillis()
        if (remaining > 0) try { Thread.sleep(remaining) } catch (_: InterruptedException) {}
    }

    @SuppressLint("MissingPermission")
    private fun torch(on: Boolean) {
        if (isTorchOn == on) return
        try { camManager?.setTorchMode(torchCamId ?: return, on); isTorchOn = on }
        catch (e: Exception) { Log.e(TAG, "torch: $e") }
    }
    private fun stopTorch() {
        try { torchCamId?.let { camManager?.setTorchMode(it, false) } } catch (_: Exception) {}
        finally { isTorchOn = false }
    }

    // ══════════════════════════════════════════════════════════════
    // RECEIVER — Camera2 with two-phase exposure lock
    // ══════════════════════════════════════════════════════════════

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val id = rxCamId ?: run { Log.e(TAG, "No back cam"); return }
        closeCam()
        cancelReadyCountdown()
        decoder.reset()

        imageReader = android.media.ImageReader.newInstance(
            320, 240, android.graphics.ImageFormat.YUV_420_888, 4
        )
        imageReader!!.setOnImageAvailableListener({ reader ->
            val img = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = img.planes[0]
                val buf   = plane.buffer; buf.rewind()
                val rs    = plane.rowStride; val ps = plane.pixelStride
                val w     = img.width;      val h  = img.height

                // Centre 40% brightness sample
                val x0 = w * 3 / 10; val y0 = h * 3 / 10
                val x1 = w * 7 / 10; val y1 = h * 7 / 10
                var sum = 0L; var cnt = 0
                var row = y0; while (row < y1) {
                    var col = x0; while (col < x1) {
                        val idx = row * rs + col * ps
                        if (idx < buf.limit()) { sum += buf[idx].toInt() and 0xFF; cnt++ }
                        col += 3
                    }; row += 3
                }
                val b      = if (cnt > 0) sum.toDouble() / cnt else 0.0
                val result = decoder.push(b, System.currentTimeMillis())

                // ── FIX 7: result check is OUTSIDE the %3 UI-throttle gate ──────
                // The %3 gate reduces UI refresh rate, which is fine for brightness
                // numbers and state labels. But decoder.push() returns a non-null
                // result on exactly ONE frame — the frame that triggers STOP — and
                // that frame is only a multiple-of-3 by chance (~33% of the time).
                // The old code put `if (result != null)` inside the %3 block, so
                // 2 out of every 3 transmissions the decode result was silently
                // discarded, causing the "needs 3-4 tries" symptom.
                if (result != null) {
                    Log.d(TAG, "DECODED: $result")
                    mainHandler.post {
                        tvDecoded.text = result
                        tvState.text   = "✅ Decoded! Auto-resetting in 4s…"
                        tvState.setBackgroundColor(c("#0D2B1A"))
                    }
                    // FIX 6: auto-reset 4s after a decode
                    mainHandler.postDelayed({
                        decoder.reset()
                        decoder.markReady()
                        tvState.text = "✅ Ready — start transmitting now!"
                        tvState.setBackgroundColor(c("#0D2B1A"))
                    }, 4000L)
                }

                // UI throttle: update brightness/state display every 3rd frame only
                if (decoder.totalFrames % 3L == 0L) {
                    val bInt  = b.toInt()
                    val color = when {
                        bInt > 180 -> "#00FF88"
                        bInt > 110 -> "#FFD700"
                        bInt > 60  -> "#FF8800"
                        else       -> "#FF3333"
                    }
                    mainHandler.post {
                        tvBigBrightness.text = bInt.toString()
                        tvBigBrightness.setTextColor(c(color))
                        pbBrightness.progress = bInt
                        tvThreshold.text = "THR: ${decoder.threshold.toInt()}"
                        tvFps.text       = "FPS: ${decoder.fps()}"
                        if (decoder.isReady) {
                            tvState.text = "🔧 ${decoder.stateLabel()}"
                            tvState.setBackgroundColor(c("#1C2B2D"))
                        }
                    }
                }
            } finally { img.close() }
        }, cameraHandler)

        try {
            camManager!!.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    cameraDevice = cam
                    createSession(cam)
                    // FIX 5: start countdown immediately when camera is open
                    mainHandler.post { startReadyCountdown(CAM_READY_DELAY_MS) }
                }
                override fun onDisconnected(cam: CameraDevice) { cam.close() }
                override fun onError(cam: CameraDevice, err: Int) { cam.close(); Log.e(TAG, "Cam err $err") }
            }, cameraHandler)
        } catch (e: Exception) { Log.e(TAG, "openCamera: $e") }
    }

    private fun createSession(cam: CameraDevice) {
        val previewSurface = surfaceView.holder.surface
        if (previewSurface == null || !previewSurface.isValid) {
            Log.e(TAG, "createSession: preview surface not valid")
            return
        }
        val surfaces = listOf(previewSurface, imageReader!!.surface)
        cam.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                startTwoPhaseCapture(cam, session)
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {
                Log.e(TAG, "Session config failed")
            }
        }, cameraHandler)
    }

    // ── FIX 3: Two-phase AE lock ──────────────────────────────────────────
    private fun startTwoPhaseCapture(cam: CameraDevice, session: CameraCaptureSession) {
        val chars = camManager!!.getCameraCharacteristics(rxCamId!!)
        val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        val bestFps   = fpsRanges?.maxByOrNull { it.upper } ?: Range(15, 30)

        // ── Phase 1: unlocked AE — let sensor converge ─────────────────
        try {
            val unlocked = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surfaceView.holder.surface)
                addTarget(imageReader!!.surface)
                set(CaptureRequest.CONTROL_AE_MODE,  CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AE_LOCK,  false)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, bestFps)
                set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                set<Int>(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            }
            session.setRepeatingRequest(unlocked.build(), null, cameraHandler)
            Log.d(TAG, "Phase 1: AE converging…")
        } catch (e: Exception) {
            Log.e(TAG, "Phase 1 failed: $e")
            startUnlockedCapture(cam, session)
            return
        }

        // ── Phase 2 (after 1s): lock AE at settled value ───────────────
        cameraHandler.postDelayed({
            try {
                val locked = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(surfaceView.holder.surface)
                    addTarget(imageReader!!.surface)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    set(CaptureRequest.CONTROL_AE_LOCK, true)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    val minFocus = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                    set(CaptureRequest.LENS_FOCUS_DISTANCE, minFocus * 0.1f)
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, bestFps)
                    set<Int>(CaptureRequest.NOISE_REDUCTION_MODE,
                        CaptureRequest.NOISE_REDUCTION_MODE_OFF)
                    set<Int>(CaptureRequest.TONEMAP_MODE,
                        CaptureRequest.TONEMAP_MODE_FAST)
                    set<Int>(CaptureRequest.STATISTICS_FACE_DETECT_MODE,
                        CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF)
                    set<Int>(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                    set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                    set(CaptureRequest.CONTROL_AWB_LOCK, true)
                }
                session.setRepeatingRequest(locked.build(), null, cameraHandler)
                Log.d(TAG, "Phase 2: AE locked, FPS=$bestFps")
            } catch (e: Exception) {
                Log.e(TAG, "Phase 2 lock failed: $e — falling back")
                startUnlockedCapture(cam, session)
            }
        }, 1000L)
    }

    private fun startUnlockedCapture(cam: CameraDevice, session: CameraCaptureSession) {
        val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(surfaceView.holder.surface)
            addTarget(imageReader!!.surface)
        }
        session.setRepeatingRequest(req.build(), null, cameraHandler)
        Log.d(TAG, "Fallback: plain unlocked capture")
    }

    private fun closeCam() {
        try { captureSession?.close() } catch (_: Exception) {}
        try { cameraDevice?.close()   } catch (_: Exception) {}
        try { imageReader?.close()    } catch (_: Exception) {}
        captureSession = null; cameraDevice = null; imageReader = null
    }

    // ══════════════════════════════════════════════════════════════
    // UTILITY
    // ══════════════════════════════════════════════════════════════

    private fun hasPerm() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    private fun findTorchId() = try {
        camManager?.cameraIdList?.firstOrNull {
            camManager?.getCameraCharacteristics(it)
                ?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    } catch (_: Exception) { null }
    private fun findBackCamId() = try {
        camManager?.cameraIdList?.firstOrNull {
            camManager?.getCameraCharacteristics(it)
                ?.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
    } catch (_: Exception) { null }
    private fun c(hex: String) = Color.parseColor(hex)
    private fun d(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun lp(height: Int = LinearLayout.LayoutParams.WRAP_CONTENT, bm: Int = 0) =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height)
            .also { it.bottomMargin = bm }
    private fun lpW(w: Float, rm: Int = 0) =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w)
            .also { it.rightMargin = rm }
    private fun tv(t: String, sz: Float, hex: String, bold: Boolean = false) =
        TextView(this).apply {
            text = t; textSize = sz; setTextColor(c(hex))
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
    private fun btn(lbl: String, bg: String, fg: String) =
        Button(this).apply {
            text = lbl; setBackgroundColor(c(bg)); setTextColor(c(fg))
            textSize = 13f; setPadding(d(8), d(12), d(8), d(12))
        }
    private fun card(t: String) =
        TextView(this).apply {
            text = t; textSize = 11f; setTextColor(c("#90A4AE"))
            setBackgroundColor(c("#161B22")); setPadding(d(14), d(10), d(14), d(10))
        }
    private fun badge(t: String) =
        TextView(this).apply {
            text = t; textSize = 11f; setTextColor(c("#80CBC4"))
            setBackgroundColor(c("#1C2B2D")); setPadding(d(10), d(8), d(10), d(8))
            gravity = Gravity.CENTER
        }
}


// ════════════════════════════════════════════════════════════════════════════
// SIMPLE DECODER  — Edge-list based, noise-filtered
//
// FIX 5 addition: isReady gate.  The decoder accepts brightness samples at
// all times (for threshold warm-up) but the state machine will not advance
// past IDLE until markReady() has been called by MainActivity after the
// CAM_READY_DELAY_MS countdown completes.
// ════════════════════════════════════════════════════════════════════════════

class SimpleDecoder {

    enum class State { IDLE, WAIT_FOR_LOW, RECEIVING, DONE }

    var state       = State.IDLE; private set
    var totalFrames = 0L

    // ── FIX 5: ready gate ─────────────────────────────────────────────────
    var isReady = false; private set
    fun markReady()    { isReady = true;  Log.d("UFlash", "Decoder armed") }
    fun markNotReady() { isReady = false; Log.d("UFlash", "Decoder disarmed") }

    // ── Adaptive threshold ────────────────────────────────────────────────
    private val win = ArrayDeque<Double>(200)
    var threshold   = 128.0; private set
    var swing       = 0.0;   private set
    private var calibBaseline = -1.0
    private val CALIB_OFFSET  = 30.0

    // ── FPS tracking ──────────────────────────────────────────────────────
    private var lastMs  = 0L
    private var avgFrMs = 40.0

    // ── Level-run tracking ────────────────────────────────────────────────
    private var runIsHigh  = false
    private var runStartMs = 0L

    // ── Timing thresholds ─────────────────────────────────────────────────
    private val START_TRIGGER_MS = 1200L
    private val STOP_TRIGGER_MS  = 2500L
    private val MIN_RUN_MS       = 175L

    // ── Edge list ─────────────────────────────────────────────────────────
    private data class Edge(val ms: Long, val isHigh: Boolean)
    private val edges  = mutableListOf<Edge>()
    private var t0Ms   = 0L

    // ─────────────────────────────────────────────────────────────────────

    fun calibrate() {
        if (win.size >= 10) {
            calibBaseline = win.sorted()[win.size / 2]
            Log.d("UFlash", "Calibrated baseline=$calibBaseline thr=${calibBaseline + CALIB_OFFSET}")
        }
    }

    fun reset() {
        state = State.IDLE
        isReady = false          // caller must markReady() after reset
        edges.clear(); t0Ms = 0L
        win.clear(); threshold = 128.0; swing = 0.0
        runIsHigh = false; runStartMs = 0L
        lastMs = 0; avgFrMs = 40.0
    }

    fun fps() = "%.1f".format(1000.0 / avgFrMs)

    fun stateLabel(): String {
        val runDur = if (runStartMs > 0 && lastMs > 0) lastMs - runStartMs else 0L
        return when (state) {
            State.IDLE         -> "IDLE  thr=${threshold.toInt()} swing=${swing.toInt()}"
            State.WAIT_FOR_LOW -> "WAIT_LOW  highDur=${runDur}ms"
            State.RECEIVING    -> {
                val lowDur = if (!runIsHigh) runDur else 0L
                "RX ${edges.size}edges  ${fps()}fps  lowRun=${lowDur}ms/2500"
            }
            State.DONE -> "DONE — auto-resetting…"
        }
    }

    fun push(brightness: Double, nowMs: Long): String? {
        totalFrames++

        // FPS
        if (lastMs > 0) avgFrMs = avgFrMs * 0.90 +
                (nowMs - lastMs).toDouble().coerceIn(5.0, 500.0) * 0.10
        lastMs = nowMs

        // Always update adaptive threshold (needed even while warming up)
        win.addLast(brightness)
        if (win.size > 150) win.removeFirst()
        if (win.size >= 12) {
            val s  = win.sorted()
            val lo = s[(s.size * 0.10).toInt()]
            val hi = s[(s.size * 0.90).toInt()]
            swing     = hi - lo
            threshold = when {
                calibBaseline >= 0 -> calibBaseline + CALIB_OFFSET
                swing >= 12.0      -> (lo + hi) / 2.0
                else               -> 128.0
            }
        }

        // FIX 5: Don't let the state machine advance until the camera is ready
        if (!isReady) return null

        val isHigh = brightness > threshold

        // Level-run tracking
        if (runStartMs == 0L) {
            runIsHigh = isHigh; runStartMs = nowMs
        } else if (isHigh != runIsHigh) {
            val runDur = nowMs - runStartMs

            when (state) {
                State.RECEIVING -> {
                    if (runDur >= MIN_RUN_MS) {
                        edges.add(Edge(nowMs, isHigh))
                        Log.d("UFlash", "edge @${nowMs - t0Ms}ms  level=${if (isHigh) "HIGH" else "LOW"}  dur=${runDur}ms")
                    } else {
                        Log.d("UFlash", "NOISE filtered @${nowMs - t0Ms}ms  dur=${runDur}ms < ${MIN_RUN_MS}ms")
                        return null
                    }
                }
                else -> { /* handled below */ }
            }
            runIsHigh = isHigh; runStartMs = nowMs
        }
        val runDurMs = nowMs - runStartMs

        when (state) {

            State.IDLE -> {
                if (isHigh && runDurMs >= START_TRIGGER_MS
                    && (swing >= 12.0 || calibBaseline >= 0)) {
                    state = State.WAIT_FOR_LOW
                    Log.d("UFlash", "★ START  thr=${threshold.toInt()} swing=${swing.toInt()}")
                }
            }

            State.WAIT_FOR_LOW -> {
                if (!isHigh) {
                    state = State.RECEIVING
                    t0Ms  = nowMs
                    edges.clear()
                    edges.add(Edge(nowMs, false))
                    Log.d("UFlash", "★ Data t0=$t0Ms")
                }
            }

            State.RECEIVING -> {
                if (!isHigh && runDurMs >= STOP_TRIGGER_MS) {
                    state = State.DONE
                    Log.d("UFlash", "★ STOP  ${edges.size}edges  lowRun=${runDurMs}ms")
                    // FIX 1: pass runStartMs + BIT_MS so the final data bit isn't swallowed
                    return decode(runStartMs + BIT_MS)
                }
                if ((nowMs - t0Ms) > 60_000L) {
                    state = State.DONE
                    return decode(nowMs)
                }
            }

            State.DONE -> {}
        }
        return null
    }

    // ─────────────────────────────────────────────────────────────────────
    // DECODE from edge list
    // FIX 2: No trailing-zero trim.
    // ─────────────────────────────────────────────────────────────────────

    private fun decode(stopMs: Long): String {
        if (edges.size < 2) return "(too few edges: ${edges.size})"

        val BIT  = BIT_MS.toDouble()
        val bits = mutableListOf<Int>()

        for (i in 0 until edges.size - 1) {
            val runMs = edges[i + 1].ms - edges[i].ms
            if (runMs < MIN_RUN_MS) continue
            val nBits = ((runMs + BIT / 2) / BIT).toInt().coerceAtLeast(1)
            val value = if (edges[i].isHigh) 1 else 0
            repeat(nBits) { bits.add(value) }
            Log.d("UFlash", "run ${i}: ${if (edges[i].isHigh) "HIGH" else "LOW"} for ${runMs}ms → $nBits bits")
        }

        if (edges.isNotEmpty()) {
            val lastRunMs = stopMs - edges.last().ms
            if (lastRunMs >= MIN_RUN_MS) {
                val nBits = ((lastRunMs + BIT / 2) / BIT).toInt().coerceAtLeast(0)
                val value = if (edges.last().isHigh) 1 else 0
                repeat(nBits) { bits.add(value) }
                Log.d("UFlash", "last run: ${if (edges.last().isHigh) "HIGH" else "LOW"} for ${lastRunMs}ms → $nBits bits")
            }
        }

        Log.d("UFlash", "bits(${bits.size}): ${bits.joinToString("")}")

        if (bits.size < 10) return "RAW:${bits.joinToString("")} (need ≥10 for 4B5B)"

        val sb  = StringBuilder()
        var i   = 0
        var bad = 0
        while (i + 9 < bits.size) {
            val byte = FourB5B.decodeTenBits(bits.subList(i, i + 10))
            if (byte < 0) {
                bad++
                Log.d("UFlash", "Bad 4B5B @bit$i: ${bits.subList(i, i + 10).joinToString("")}")
                sb.append("?")
            } else {
                sb.append(if (byte in 32..126) byte.toChar() else "[${byte}]")
            }
            i += 10
        }
        val leftover = bits.size - i
        if (leftover > 0)
            Log.d("UFlash", "leftover $leftover: ${bits.subList(i, bits.size).joinToString("")}")
        if (bad > 0)
            Log.d("UFlash", "$bad bad 4B5B symbol(s)")

        return sb.toString().ifEmpty { "RAW:${bits.joinToString("")}" }
    }
}


// ════════════════════════════════════════════════════════════════════════════
// 4B5B CODEC — standard 100BASE-TX table
//
// WHY NOT FEWER BITS?
// The 5-bits-per-nibble encoding is load-bearing, not wasteful.  The entire
// STOP-detection mechanism relies on "no run of 0s can last ≥ STOP_TRIGGER_MS
// (2500ms)".  With 500ms/bit that means ≤ 4 consecutive zero-bits.  4B5B
// guarantees exactly 3 maximum.  Using raw ASCII (e.g. space = 0x20 =
// 00100000) would produce a 5-bit zero run = 2500ms = false STOP mid-message.
// Using a 4-bit encoding for digits would require its own zero-run limiter
// (e.g. bit stuffing), adding more complexity than it removes.
//
// The decode errors you saw were NOT caused by too many bits — they were
// caused by the camera not being ready (fixed by FIX 5 above).
// ════════════════════════════════════════════════════════════════════════════

object FourB5B {
    private val ENC = intArrayOf(
        0b11110, // 0
        0b01001, // 1
        0b10100, // 2
        0b10101, // 3
        0b01010, // 4
        0b01011, // 5
        0b01110, // 6
        0b01111, // 7
        0b10010, // 8
        0b10011, // 9
        0b10110, // A
        0b10111, // B
        0b11010, // C
        0b11011, // D
        0b11100, // E
        0b11101  // F
    )

    private val DEC = IntArray(32) { -1 }.also { d ->
        ENC.forEachIndexed { nibble, code -> d[code] = nibble }
    }

    fun encodeByte(byte: Int): List<Int> {
        val hi = (byte shr 4) and 0xF
        val lo = byte and 0xF
        return nibbleToBits(ENC[hi]) + nibbleToBits(ENC[lo])
    }

    fun decodeTenBits(bits: List<Int>): Int {
        if (bits.size < 10) return -1
        val hi = DEC[bitsToInt(bits.subList(0, 5))]
        val lo = DEC[bitsToInt(bits.subList(5, 10))]
        return if (hi < 0 || lo < 0) -1 else (hi shl 4) or lo
    }

    private fun nibbleToBits(code: Int): List<Int> = (4 downTo 0).map { (code shr it) and 1 }
    private fun bitsToInt(bits: List<Int>): Int = bits.fold(0) { acc, b -> (acc shl 1) or b }
}