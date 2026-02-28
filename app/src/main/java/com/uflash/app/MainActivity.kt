package com.uflash.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Range
import android.util.Size
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

// ═══════════════════════════════════════════════════════
//  PROTOCOL  (minimal — raw ASCII bits, time-based)
//  TX: 3s HIGH (start) | 1s gap | bits (1s each) | 3s LOW (stop)
//  RX: lock exposure → measure brightness → slice by ms → decode
// ═══════════════════════════════════════════════════════

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG  = "UFlash"
        private const val PERM = 100

        // ── Protocol constants ──────────────────────────────────────────
        // WHY 300ms/bit:
        //   Worst-case printable ASCII = 7 consecutive zero-bits across two
        //   bytes (e.g. '@' 0x40 + '@' 0x40 = ...0000000...).
        //   7 × 300ms = 2100ms  <  STOP_TRIGGER (2500ms) — safe gap exists.
        //   Old 1000ms/bit: 7 × 1000 = 7000ms — indistinguishable from STOP.
        //
        // WHY no GAP after START:
        //   Old GAP (1000ms LOW) + first data bit '0' (1000ms LOW) = 2000ms
        //   consecutive LOW → exceeded STOP_TRIGGER (1800ms) → false STOP
        //   fired on the very first data bit every single time.
        const val BIT_MS   = 400L    // 300ms per bit
        const val START_MS = 3000L   // 3s HIGH preamble
        const val STOP_MS  = 4500L   // 4s LOW  postamble — clearly > max zero-run
        // NO GAP constant — data starts immediately after START goes LOW
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
    private lateinit var tvBigBrightness: TextView   // large live brightness number
    private lateinit var pbBrightness: ProgressBar   // visual bar
    private lateinit var tvThreshold: TextView
    private lateinit var tvState: TextView
    private lateinit var tvDecoded: TextView
    private lateinit var tvFps: TextView
    private lateinit var btnClear: Button
    private lateinit var btnCalibrate: Button

    // ── Camera2 (direct — for exposure lock) ─────────────────────
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
        layoutTx.addView(tv("Message (start with 1–3 chars):", 13f, "#B0BEC5"), lp(bm = d(6)))
        etMsg = EditText(this).apply {
            setText("Hi"); textSize = 18f
            setTextColor(c("#E0E0E0")); setBackgroundColor(c("#161B22"))
            setHintTextColor(c("#455A64"))
            setPadding(d(16), d(12), d(16), d(12))
        }
        layoutTx.addView(etMsg, lp(bm = d(10)))
        layoutTx.addView(card("START: 3s flash ON  ·  Each bit: 1s ON=1 / OFF=0  ·  STOP: 3s OFF"), lp(bm = d(12)))
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

        // Camera preview
        surfaceView = SurfaceView(this)
        layoutRx.addView(surfaceView, lp(height = d(180), bm = d(8)))

        // ★ BIG live brightness number — most important diagnostic
        tvBigBrightness = TextView(this).apply {
            text = "---"; textSize = 56f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(c("#00E5FF")); setBackgroundColor(c("#0D1117"))
            gravity = Gravity.CENTER; setPadding(0, d(4), 0, d(4))
        }
        layoutRx.addView(tvBigBrightness, lp(bm = d(4)))

        // Brightness bar (0–255)
        pbBrightness = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 255; progress = 0
            progressDrawable?.setColorFilter(c("#00E5FF"), android.graphics.PorterDuff.Mode.SRC_IN)
        }
        layoutRx.addView(pbBrightness, lp(bm = d(6)))

        // Threshold + FPS info row
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
            decoder.reset()
            tvDecoded.text = "(waiting…)"
            tvState.text = "🔧 IDLE — waiting for flash"
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

    /**
     * SurfaceView surface is created asynchronously — if we call openCamera()
     * before surfaceCreated() fires we get "Surface was abandoned" crash.
     * This method waits for the surface to be valid before proceeding.
     */
    private fun waitForSurfaceThenOpen() {
        val holder = surfaceView.holder
        if (holder.surface != null && holder.surface.isValid) {
            openCamera()   // already ready (e.g. switching back from TX mode)
            return
        }
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) {
                holder.removeCallback(this)  // one-shot
                openCamera()
            }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) { closeCam() }
        })
    }

    // ══════════════════════════════════════════════════════════════
    // TRANSMITTER
    // ══════════════════════════════════════════════════════════════

    private fun startTx() {
        if (isTransmitting.get()) return
        val msg = etMsg.text.toString()
        if (msg.isEmpty()) { Toast.makeText(this, "Enter a message", Toast.LENGTH_SHORT).show(); return }
        if (torchCamId == null) { Toast.makeText(this, "No torch", Toast.LENGTH_SHORT).show(); return }

        val bytes = msg.toByteArray(Charsets.UTF_8)
        // Build bits with even-parity bit appended after each 8 data bits
        // Even parity: parity bit = 1 if number of 1s in byte is odd (makes total even)
        val bits = bytes.flatMap { b ->
            val dataBits = (7 downTo 0).map { (b.toInt() shr it) and 1 }
            val parity   = dataBits.count { it == 1 } % 2  // 0=even already, 1=needs correction
            dataBits + listOf(parity)   // 9 bits per byte
        }
        val estSec = (START_MS + bits.size * BIT_MS + STOP_MS) / 1000

        isTransmitting.set(true)
        btnSend.isEnabled = false; btnSend.text = "📡 Sending…"
        pbTx.max = bits.size; pbTx.progress = 0; pbTx.visibility = View.VISIBLE
        tvTxStatus.text = "🔵 ~${estSec}s  ·  ${bits.size} bits"
        tvTxStatus.setTextColor(c("#FFA726"))
        Log.d(TAG, "TX '${msg}' → bits: ${bits.joinToString("")}")

        txExecutor.execute {
            try {
                // START marker — 3s HIGH
                torch(true);  sleep(START_MS)
                torch(false)
                // Data bits start IMMEDIATELY — no gap
                // (gap caused false STOP: GAP LOW + first 0-bit LOW > STOP_TRIGGER)
                bits.forEachIndexed { i, bit ->
                    torch(bit == 1); sleep(BIT_MS)
                    mainHandler.post { pbTx.progress = i + 1; tvTxProgress.text = "Bit ${i+1}/${bits.size}" }
                }
                torch(false); sleep(STOP_MS)
                mainHandler.post {
                    tvTxStatus.text = "✅ Done! ${bits.size} bits"; tvTxStatus.setTextColor(c("#4CAF50"))
                    tvTxProgress.text = ""; btnSend.isEnabled = true; btnSend.text = "⚡  TRANSMIT"
                    pbTx.visibility = View.GONE
                }
            } catch (e: Exception) {
                mainHandler.post {
                    tvTxStatus.text = "❌ ${e.message}"; tvTxStatus.setTextColor(c("#F44336"))
                    btnSend.isEnabled = true; btnSend.text = "⚡  TRANSMIT"; pbTx.visibility = View.GONE
                }
            } finally { isTransmitting.set(false); stopTorch() }
        }
    }

    @SuppressLint("MissingPermission")
    private fun torch(on: Boolean) {
        if (isTorchOn == on) return
        try { camManager?.setTorchMode(torchCamId ?: return, on); isTorchOn = on }
        catch (e: Exception) { Log.e(TAG, "torch: $e") }
    }
    private fun stopTorch() { try { torchCamId?.let { camManager?.setTorchMode(it, false) } } catch (_: Exception) {} finally { isTorchOn = false } }
    private fun sleep(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) {} }

    // ══════════════════════════════════════════════════════════════
    // RECEIVER — Camera2 with LOCKED EXPOSURE
    //
    // Auto-exposure is the silent killer: the camera sees the bright
    // flash and reduces gain so the flash looks as dim as ambient.
    // We lock ISO + exposure time so brightness truly reflects ON/OFF.
    // ══════════════════════════════════════════════════════════════

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val id = rxCamId ?: run { Log.e(TAG, "No back cam"); return }
        closeCam()

        // ImageReader: small res, fast, YUV
        imageReader = android.media.ImageReader.newInstance(320, 240, android.graphics.ImageFormat.YUV_420_888, 4)
        imageReader!!.setOnImageAvailableListener({ reader ->
            val img = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = img.planes[0]
                val buf   = plane.buffer; buf.rewind()
                val rs    = plane.rowStride; val ps = plane.pixelStride
                val w     = img.width;     val h  = img.height

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
                val b = if (cnt > 0) sum.toDouble() / cnt else 0.0
                val result = decoder.push(b, System.currentTimeMillis())

                if (decoder.totalFrames % 3L == 0L) {
                    val bInt = b.toInt()
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
                        tvFps.text = "FPS: ${decoder.fps()}"
                        tvState.text = "🔧 ${decoder.stateLabel()}"
                    }
                    if (result != null) {
                        Log.d(TAG, "DECODED: $result")
                        mainHandler.post {
                            tvDecoded.text = result
                            tvState.text = "✅ Decoded! Reset to try again."
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
                }
                override fun onDisconnected(cam: CameraDevice) { cam.close() }
                override fun onError(cam: CameraDevice, err: Int) { cam.close(); Log.e(TAG, "Cam err $err") }
            }, cameraHandler)
        } catch (e: Exception) { Log.e(TAG, "openCamera: $e") }
    }

    private fun createSession(cam: CameraDevice) {
        val previewSurface = surfaceView.holder.surface
        if (previewSurface == null || !previewSurface.isValid) {
            Log.e(TAG, "createSession: preview surface not valid, aborting")
            return
        }
        val surfaces = listOf(previewSurface, imageReader!!.surface)
        cam.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                startLockedCapture(cam, session)
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {
                Log.e(TAG, "Session config failed")
            }
        }, cameraHandler)
    }

    // ★ KEY FIX: lock AE/AF so camera can't auto-compensate for the flash
    private fun startLockedCapture(cam: CameraDevice, session: CameraCaptureSession) {
        try {
            val chars = camManager!!.getCameraCharacteristics(rxCamId!!)

            // Step 1: Run a short AE precapture to get a baseline exposure,
            // then immediately lock it so it doesn't chase the flash.
            val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surfaceView.holder.surface)
                addTarget(imageReader!!.surface)

                // ── LOCK auto-exposure ──────────────────────────────────────
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AE_LOCK, true)          // ← THIS is the fix

                // ── Lock auto-focus at hyperfocal (infinity) ────────────────
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                val minFocus = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                set(CaptureRequest.LENS_FOCUS_DISTANCE, minFocus * 0.1f)  // near-infinity

                // ── Force highest possible frame rate ───────────────────────
                val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                val bestFps   = fpsRanges?.maxByOrNull { it.upper } ?: Range(15, 30)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, bestFps)

                // ── Disable all processing that could smear brightness ──────
                set<Int>(CaptureRequest.NOISE_REDUCTION_MODE,   CaptureRequest.NOISE_REDUCTION_MODE_OFF)
                // SHARPENING_MODE removed — not a standard Camera2 key
                set<Int>(CaptureRequest.TONEMAP_MODE,           CaptureRequest.TONEMAP_MODE_FAST)
                set<Int>(CaptureRequest.STATISTICS_FACE_DETECT_MODE, CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF)
                set<Int>(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)

                // ── Disable flash on RX phone ───────────────────────────────
                set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                set(CaptureRequest.CONTROL_AWB_LOCK, true)
            }
            session.setRepeatingRequest(req.build(), null, cameraHandler)
            Log.d(TAG, "Camera locked: AE=LOCKED, AF=OFF, FPS=${session}")
        } catch (e: Exception) {
            Log.e(TAG, "startLockedCapture: $e")
            // Fallback: plain preview without locks
            startUnlockedCapture(cam, session)
        }
    }

    private fun startUnlockedCapture(cam: CameraDevice, session: CameraCaptureSession) {
        val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(surfaceView.holder.surface)
            addTarget(imageReader!!.surface)
        }
        session.setRepeatingRequest(req.build(), null, cameraHandler)
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

    private fun hasPerm() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    private fun findTorchId() = try { camManager?.cameraIdList?.firstOrNull { camManager?.getCameraCharacteristics(it)?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true } } catch (_: Exception) { null }
    private fun findBackCamId() = try { camManager?.cameraIdList?.firstOrNull { camManager?.getCameraCharacteristics(it)?.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK } } catch (_: Exception) { null }
    private fun c(hex: String) = Color.parseColor(hex)
    private fun d(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun lp(height: Int = LinearLayout.LayoutParams.WRAP_CONTENT, bm: Int = 0) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height).also { it.bottomMargin = bm }
    private fun lpW(w: Float, rm: Int = 0) = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w).also { it.rightMargin = rm }
    private fun tv(t: String, sz: Float, hex: String, bold: Boolean = false) = TextView(this).apply { text = t; textSize = sz; setTextColor(c(hex)); if (bold) typeface = Typeface.DEFAULT_BOLD }
    private fun btn(lbl: String, bg: String, fg: String) = Button(this).apply { text = lbl; setBackgroundColor(c(bg)); setTextColor(c(fg)); textSize = 13f; setPadding(d(8), d(12), d(8), d(12)) }
    private fun card(t: String) = TextView(this).apply { text = t; textSize = 11f; setTextColor(c("#90A4AE")); setBackgroundColor(c("#161B22")); setPadding(d(14), d(10), d(14), d(10)) }
    private fun badge(t: String) = TextView(this).apply { text = t; textSize = 11f; setTextColor(c("#80CBC4")); setBackgroundColor(c("#1C2B2D")); setPadding(d(10), d(8), d(10), d(8)); gravity = Gravity.CENTER }
}


// ════════════════════════════════════════════════════════════════════════════
// SIMPLE DECODER  — 4-state time-based machine
//
// THE BUG FIXED HERE (found from logcat):
//   TX 'Hi' bits: 0100100001101001
//   RX decoded:   1111110100100001101001  ← 6 extra 1s at start
//
//   Root cause: old RECEIVING state started collecting the moment START was
//   detected — but the flash was STILL ON. The tail of the 3s START pulse
//   got recorded as data bits (HIGH = 1), polluting the front of the buffer.
//
//   Fix: add WAIT_FOR_LOW state. After detecting START, pause collection
//   until the signal actually drops LOW. That LOW edge = exact t=0 of data.
//   tData[] stores ms offsets from that edge — bit slicing is now precise.
//
// State flow:
//   IDLE → (sustained HIGH ≥ 1200ms) → WAIT_FOR_LOW
//   WAIT_FOR_LOW → (signal goes LOW) → RECEIVING  [t0 = this exact moment]
//   RECEIVING → (sustained LOW ≥ 2500ms) → DONE → decode()
//
// STOP_TRIGGER = 2500ms safely between:
//   max data zero-run = 7 bits × 300ms = 2100ms  (worst-case ASCII)
//   actual STOP marker = 4000ms LOW
// ════════════════════════════════════════════════════════════════════════════


// ════════════════════════════════════════════════════════════════════════════
// SIMPLE DECODER  — Edge-recovery clock sync
//
// PROBLEM SOLVED HERE:
//   Thread.sleep(300) on Android has ±15–30ms jitter per call.
//   Over 40 bits ("Amrit") that accumulates to ~1200ms total drift —
//   enough to lose a full bit boundary and corrupt every byte after it.
//
//   Observed in logcat:
//     TX: 01000001 01101101 01110010 01101001 01110100  (A m r i t)
//     RX: 01000001 01101101 01111001 00110100 1011101
//                           ↑ drift visible from byte 3 onward
//
// THE FIX — Edge-based clock recovery:
//   Every time the brightness crosses the threshold (HIGH↔LOW transition),
//   that edge is a known bit boundary. We snap our clock to it.
//   Between edges (long runs of the same level) we interpolate.
//
//   This means TX jitter resets every transition — it never accumulates
//   across more than one "same-level run" of bits.
//
// ALSO FIXED:
//   - BIT_MS raised 300→400ms: more frames per slot, better majority vote
//   - Parity bit added per byte (even parity): catches single-bit errors,
//     costs only 1 extra bit (11% overhead) — "Amrit" still < 25s total
// ════════════════════════════════════════════════════════════════════════════

class SimpleDecoder {

    enum class State { IDLE, WAIT_FOR_LOW, RECEIVING, DONE }

    var state       = State.IDLE; private set
    var totalFrames = 0L

    // ── Adaptive threshold ────────────────────────────────────────────────
    private val win  = ArrayDeque<Double>(200)
    var threshold    = 128.0; private set
    var swing        = 0.0;   private set
    private var calibBaseline = -1.0
    private val CALIB_OFFSET  = 30.0

    // ── FPS measurement ───────────────────────────────────────────────────
    private var lastMs  = 0L
    private var avgFrMs = 40.0

    // ── Level-run tracking ────────────────────────────────────────────────
    private var runIsHigh  = false
    private var runStartMs = 0L          // wall-clock ms when current run started

    // ── Timing thresholds ─────────────────────────────────────────────────
    private val START_TRIGGER_MS = 1200L
    private val STOP_TRIGGER_MS  = 3200L  // 7×400ms=2800 max zero-run — raise to 3200

    // ── Edge-recovery clock state ─────────────────────────────────────────
    // clockEdgeMs: the ms timestamp of the last known bit-boundary edge.
    // Every transition snaps this forward to the exact transition time.
    // Slot n starts at: clockEdgeMs + n * BIT_MS  (within current run)
    private var clockEdgeMs     = 0L
    private var clockBitOffset  = 0      // how many bits have been decided before this edge

    // ── Sample buffer ─────────────────────────────────────────────────────
    // Stores raw samples for per-slot majority voting
    private var t0Ms  = 0L
    private val bData = mutableListOf<Double>()
    private val tData = mutableListOf<Long>()    // ms offset from t0

    // ── Per-slot vote accumulators (rolling) ──────────────────────────────
    // We accumulate votes slot-by-slot as samples arrive, so decode() just
    // reads the already-computed bit list.
    private val decidedBits = mutableListOf<Int>()
    private var currentSlot = 0          // which slot we're currently accumulating
    private var slotHiCount = 0
    private var slotTotCount = 0

    // ─────────────────────────────────────────────────────────────────────

    fun calibrate() {
        if (win.size >= 10) {
            calibBaseline = win.sorted()[win.size / 2]
            Log.d("UFlash", "Calibrated baseline=$calibBaseline thr=${calibBaseline + CALIB_OFFSET}")
        }
    }

    fun reset() {
        state = State.IDLE
        bData.clear(); tData.clear(); decidedBits.clear()
        win.clear(); threshold = 128.0; swing = 0.0
        runIsHigh = false; runStartMs = 0L
        clockEdgeMs = 0L; clockBitOffset = 0
        currentSlot = 0; slotHiCount = 0; slotTotCount = 0
        t0Ms = 0L; lastMs = 0; avgFrMs = 40.0
    }

    fun fps() = "%.1f".format(1000.0 / avgFrMs)

    fun stateLabel(): String {
        val runDur = if (runStartMs > 0 && lastMs > 0) lastMs - runStartMs else 0L
        return when (state) {
            State.IDLE         -> "IDLE  thr=${threshold.toInt()} swing=${swing.toInt()}"
            State.WAIT_FOR_LOW -> "WAIT_LOW  highDur=${runDur}ms"
            State.RECEIVING    -> {
                val lowDur = if (!runIsHigh && runStartMs > 0) lastMs - runStartMs else 0L
                "RX ${bData.size}smp ${fps()}fps  bits=${decidedBits.size}  lowRun=${lowDur}ms"
            }
            State.DONE         -> "DONE — tap Reset"
        }
    }

    fun push(brightness: Double, nowMs: Long): String? {
        totalFrames++

        // FPS tracking
        if (lastMs > 0) avgFrMs = avgFrMs * 0.90 +
                (nowMs - lastMs).toDouble().coerceIn(5.0, 500.0) * 0.10
        lastMs = nowMs

        // Adaptive threshold
        win.addLast(brightness)
        if (win.size > 150) win.removeFirst()
        if (win.size >= 12) {
            val s  = win.sorted()
            val lo = s[(s.size * 0.10).toInt()]
            val hi = s[(s.size * 0.90).toInt()]
            swing = hi - lo
            threshold = when {
                calibBaseline >= 0 -> calibBaseline + CALIB_OFFSET
                swing >= 12.0      -> (lo + hi) / 2.0
                else               -> 128.0
            }
        }

        val prevIsHigh = runIsHigh
        val isHigh     = brightness > threshold

        // Detect transition and update run tracking
        if (runStartMs == 0L) {
            runIsHigh = isHigh; runStartMs = nowMs
        } else if (isHigh != runIsHigh) {
            // ── TRANSITION DETECTED ──────────────────────────────────────
            // Snap clock to this edge (edge recovery)
            if (state == State.RECEIVING) {
                onEdge(nowMs, prevIsHigh)
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
                    // Flash went OFF → t=0 of data stream
                    state         = State.RECEIVING
                    t0Ms          = nowMs
                    clockEdgeMs   = nowMs
                    clockBitOffset = 0
                    currentSlot    = 0
                    slotHiCount    = 0
                    slotTotCount   = 0
                    decidedBits.clear()
                    bData.clear(); tData.clear()
                    bData.add(brightness); tData.add(0L)
                    Log.d("UFlash", "★ Data t0=$t0Ms")
                }
            }

            State.RECEIVING -> {
                bData.add(brightness)
                val tOff = nowMs - t0Ms
                tData.add(tOff)

                // ── Accumulate vote for current slot ─────────────────────
                // Which slot does this sample belong to (relative to last clock edge)?
                val slotInRun = ((nowMs - clockEdgeMs) / MainActivity.BIT_MS).toInt()
                val absSlot   = clockBitOffset + slotInRun

                if (absSlot > currentSlot) {
                    // Finalize previous slot
                    if (slotTotCount >= 2) {
                        decidedBits.add(if (slotHiCount.toDouble() / slotTotCount >= 0.5) 1 else 0)
                    }
                    currentSlot  = absSlot
                    slotHiCount  = 0
                    slotTotCount = 0
                }
                if (brightness > threshold) slotHiCount++
                slotTotCount++

                // STOP detection: sustained LOW for > STOP_TRIGGER_MS
                if (!isHigh && runDurMs >= STOP_TRIGGER_MS) {
                    // Finalize last partial slot before returning
                    if (slotTotCount >= 2) {
                        decidedBits.add(if (slotHiCount.toDouble() / slotTotCount >= 0.5) 1 else 0)
                    }
                    state = State.DONE
                    Log.d("UFlash", "★ STOP  ${bData.size} samples  lowRun=${runDurMs}ms  bits=${decidedBits.size}")
                    return decode()
                }

                if (bData.size > 2500) { state = State.DONE; return decode() }
            }

            State.DONE -> {}
        }
        return null
    }

    // Called on every HIGH↔LOW transition while RECEIVING.
    // Finalizes all complete slots up to this edge, then resets clock.
    private fun onEdge(edgeMs: Long, wasHigh: Boolean) {
        val msInRun = edgeMs - clockEdgeMs
        val slotsInRun = (msInRun / MainActivity.BIT_MS).toInt()

        // Any complete slots between last clock edge and this transition
        // get the level that was active during the run (wasHigh → 1 or 0)
        val bitVal = if (wasHigh) 1 else 0
        // Slots we haven't explicitly decided yet up to slotsInRun-1:
        // (currentSlot tracks the last slot we were accumulating votes for)
        val firstMissing = decidedBits.size
        val lastComplete = clockBitOffset + slotsInRun - 1
        for (s in firstMissing..lastComplete) {
            if (s >= clockBitOffset) decidedBits.add(bitVal)
        }

        // Snap clock to this transition
        clockBitOffset = decidedBits.size
        clockEdgeMs    = edgeMs
        currentSlot    = decidedBits.size
        slotHiCount    = 0
        slotTotCount   = 0

        Log.d("UFlash", "edge @${edgeMs-t0Ms}ms  slotsInRun=$slotsInRun  bits so far=${decidedBits.size}")
    }

    // ──────────────────────────────────────────────────────────────────────
    // DECODE
    //
    // decidedBits[] is already built by push() via edge-recovery voting.
    // Trim trailing STOP zeros, then convert 8-bit groups → ASCII + parity.
    // ──────────────────────────────────────────────────────────────────────

    private fun decode(): String {
        // Strip trailing zeros added by STOP marker
        var endIdx = decidedBits.size
        while (endIdx > 0 && decidedBits[endIdx - 1] == 0) endIdx--
        if (endIdx < 8) {
            Log.d("UFlash", "bits after trim: ${decidedBits.size}→$endIdx")
            return "RAW:${decidedBits.joinToString("")} (too few)"
        }

        val bits = decidedBits.subList(0, endIdx)
        Log.d("UFlash", "bits(${bits.size}): ${bits.joinToString("")}")
        Log.d("UFlash", "expected 'Hi':     0100100001101001")

        val sb = StringBuilder()
        var i  = 0
        // Each byte = 8 data bits + 1 even-parity bit = 9 bits
        // Fall back to plain 8-bit if bit count suggests no parity was used
        val useParity = (bits.size % 9 == 0)
        val stride    = if (useParity) 9 else 8

        while (i + 7 < bits.size) {
            var byte = 0
            for (b in 0..7) byte = (byte shl 1) or bits[i + b]

            if (useParity) {
                val expectedParity = bits.subList(i, i + 8).count { it == 1 } % 2
                val receivedParity = bits.getOrElse(i + 8) { 0 }
                if (expectedParity != receivedParity) {
                    Log.d("UFlash", "Parity error at byte ${sb.length}: byte=0x${byte.toString(16)}")
                }
            }

            sb.append(if (byte in 32..126) byte.toChar() else "[${byte}]")
            i += stride
        }
        if (i < bits.size) Log.d("UFlash",
            "leftover ${bits.size - i} bits: ${bits.subList(i, bits.size).joinToString("")}")

        return sb.toString().ifEmpty { "RAW:${bits.joinToString("")}" }
    }
}