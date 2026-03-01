
package com.uflash.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.*
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Range
import android.view.Gravity
import android.view.KeyEvent
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

// ── Top-level constants ───────────────────────────────────────────────────
const val BIT_MS             = 200L
const val START_MS           = 800L
const val STOP_MS            = 900L
const val CAM_READY_DELAY_MS = 2000L

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG        = "UFlash"
        private const val PERM       = 100
        private const val PERM_NOTIF = 101

        // ── VOID HAZARD palette ───────────────────────────────────────────
        // Deep void black with acid yellow-green accents.
        // TX = neon yellow (D4FF00) — maximum visibility at depth.
        // RX = cool mint-white (B8FFE8) — distinct from TX, never confused.
        // Backgrounds: near-black with a faint warm-green tint, not cold blue.
        const val BG       = "#050A05"   // void black, faint green undertone
        const val SURFACE  = "#0A100A"   // slightly lifted surface
        const val SURFACE1 = "#101810"   // input / card background
        const val DIVIDER  = "#1A2A1A"   // yellow-tinted dark divider
        const val TX       = "#D4FF00"   // acid neon yellow-green — TX accent
        const val TX_DIM   = "#2A3300"   // very dark yellow for TX glow fills
        const val RX       = "#B8FFE8"   // cool mint-white — RX accent
        const val RX_DIM   = "#0D2A1E"   // very dark mint for RX glow fills
        const val LABEL    = "#8AAA7A"   // muted yellow-green for secondary labels
        const val HINT     = "#5A7A4A"   // readable secondary text on dark bg
        const val RED      = "#FF3B30"   // danger red — unchanged (universal signal)
        const val AMBER    = "#FFBB00"   // warmer amber to complement yellow palette
        const val BLUE     = "#4FC3F7"   // softer blue, less clash with yellow
        const val WHITE    = "#F0FFE8"   // warm white with green tint

        val QUICK_CMDS = listOf(
            Triple("🆘 HELP",     "H", RED),
            Triple("🆘 SOS",      "S", RED),
            Triple("⚠️ THREAT",   "T", AMBER),
            Triple("😮 LOW O2",   "X", AMBER),
            Triple("⬆️ UP",       "U", BLUE),
            Triple("⬇️ DOWN",     "D", BLUE),
            Triple("✅ OK",       "K", RX),
            Triple("▶️ CONTINUE", "C", RX),
            Triple("📤 SEND",     "G", TX)
        )

        val CODE_TO_LABEL: Map<String, String> = QUICK_CMDS.associate { (label, code, _) ->
            code to label.substringAfter(" ").trim()
        }
    }

    // ── UI ────────────────────────────────────────────────────────────────
    private lateinit var btnTx: Button
    private lateinit var btnRx: Button
    private lateinit var tabIndicator: View
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

    // ── Camera2 ───────────────────────────────────────────────────────────
    private var camManager: CameraManager? = null
    private var torchCamId: String? = null
    private var rxCamId: String? = null
    private var captureSession: CameraCaptureSession? = null
    private var cameraDevice: CameraDevice? = null
    private val cameraThread = HandlerThread("CamThread").also { it.start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private var imageReader: android.media.ImageReader? = null

    // ── State ─────────────────────────────────────────────────────────────
    private var isTxMode = true
    private val isTransmitting = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val txExecutor = Executors.newSingleThreadExecutor()
    private var isTorchOn = false
    private val decoder = SimpleDecoder()
    private var camReadyRunnable: Runnable? = null
    private var countdownRunnable: Runnable? = null

    // ── Volume-button navigation ──────────────────────────────────────────
    private val cmdButtons       = mutableListOf<Button>()
    private var selectedCmdIndex = 0
    private var volHoldRunnable: Runnable? = null

    // ── BroadcastReceiver registration guard ─────────────────────────────
    private var isReceiverRegistered = false

    // ══════════════════════════════════════════════════════════════════════
    // ── LOCK-SCREEN NOTIFICATION ──────────────────────────────────────────
    //
    // How it works:
    //   1. MainActivity starts UFlashNotifService (foreground) in onResume.
    //   2. The service shows a MediaStyle notification with three buttons:
    //      ◀ PREV   ⚡ SEND   NEXT ▶
    //   3. Each button fires a broadcast intent (ACTION_PREV/SEND/NEXT).
    //   4. notifReceiver (registered in onResume, unregistered in onPause)
    //      catches those intents and runs the same logic as volume keys.
    //   5. NotifActionReceiver (declared in manifest) catches the same
    //      intents when the app is in the background and re-launches
    //      MainActivity via onNewIntent so the action still executes.
    // ══════════════════════════════════════════════════════════════════════

    private val notifReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                UFlashNotifService.ACTION_PREV -> stepCmd(-1)
                UFlashNotifService.ACTION_NEXT -> stepCmd(+1)
                UFlashNotifService.ACTION_SEND -> {
                    if (!isTransmitting.get() && isTxMode) {
                        etMsg.setText(QUICK_CMDS[selectedCmdIndex].second)
                        startTx()
                        vibrateHold()
                    }
                }
            }
        }
    }

    /** Move selection by [delta], update highlight + notification. */
    private fun stepCmd(delta: Int) {
        selectedCmdIndex = (selectedCmdIndex + delta + QUICK_CMDS.size) % QUICK_CMDS.size
        updateCmdSelection()
        pushNotification()
        vibrateShort()
    }

    /** Start or update the foreground service / lock-screen notification. */
    private fun pushNotification() {
        val (label, code, _) = QUICK_CMDS[selectedCmdIndex]
        val intent = Intent(this, UFlashNotifService::class.java).apply {
            putExtra(UFlashNotifService.EXTRA_LABEL, label)
            putExtra(UFlashNotifService.EXTRA_CODE, code)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            startForegroundService(intent)
        else
            startService(intent)
    }

    private fun stopNotifService() =
        stopService(Intent(this, UFlashNotifService::class.java))

    /** Request POST_NOTIFICATIONS at runtime (Android 13+ only). */
    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), PERM_NOTIF)
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═════════════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.parseColor(BG)
        camManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        torchCamId = findTorchId()
        rxCamId    = findBackCamId()
        buildUI()
        setupListeners()
        if (!hasPerm()) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), PERM)
        requestNotifPermission()
        showTxMode()
    }

    override fun onResume() {
        super.onResume()
        // Guard against double-registration on rapid lifecycle events
        if (!isReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(UFlashNotifService.ACTION_PREV)
                addAction(UFlashNotifService.ACTION_SEND)
                addAction(UFlashNotifService.ACTION_NEXT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                registerReceiver(notifReceiver, filter, RECEIVER_NOT_EXPORTED)
            else
                registerReceiver(notifReceiver, filter)
            isReceiverRegistered = true
        }
        // Launch / refresh notification with current selected command
        pushNotification()
    }

    override fun onPause() {
        super.onPause()
        if (isReceiverRegistered) {
            try { unregisterReceiver(notifReceiver) } catch (_: Exception) {}
            isReceiverRegistered = false
        }
        // Service stays alive so lock-screen controls remain usable.
        // Call stopNotifService() here if you want it to die when backgrounded.
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Called by NotifActionReceiver when app is brought back from background
        intent ?: return
        when (intent.action) {
            UFlashNotifService.ACTION_PREV -> stepCmd(-1)
            UFlashNotifService.ACTION_NEXT -> stepCmd(+1)
            UFlashNotifService.ACTION_SEND -> {
                if (!isTransmitting.get() && isTxMode) {
                    etMsg.setText(QUICK_CMDS[selectedCmdIndex].second)
                    startTx(); vibrateHold()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        closeCam(); stopTorch(); txExecutor.shutdown()
        cameraThread.quitSafely(); cancelReadyCountdown()
        stopNotifService()
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<String>, gr: IntArray) {
        super.onRequestPermissionsResult(rc, p, gr)
        when (rc) {
            PERM      -> if (gr.isNotEmpty() && gr[0] == PackageManager.PERMISSION_GRANTED
                && !isTxMode) waitForSurfaceThenOpen()
            PERM_NOTIF -> pushNotification()   // retry now that permission granted
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // BUILD UI — split into helpers for readability
    // ═════════════════════════════════════════════════════════════════════

    private fun buildUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(c(BG))
        }
        setContentView(root)

        root.addView(buildHeader(), lp())
        root.addView(buildTabBar(), lp())
        root.addView(View(this).apply { setBackgroundColor(c(DIVIDER)) }, lp(height = 1))

        val txScroll = ScrollView(this).apply { setBackgroundColor(c(BG)) }
        txScroll.addView(buildTxLayout())
        root.addView(txScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(buildRxLayout(), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun buildHeader(): LinearLayout {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(c(BG))
            setPadding(d(24), d(48), d(24), d(0))
        }
        val logoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        logoRow.addView(View(this).apply { background = ovalDrawable(TX) }, d(9), d(9))
        logoRow.addView(Space(this).apply { minimumWidth = d(10) })
        logoRow.addView(TextView(this).apply {
            text = "UFLASH"; textSize = 21f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(c(WHITE)); letterSpacing = 0.24f
        })
        header.addView(logoRow, lp(bm = d(3)))
        header.addView(TextView(this).apply {
            text = "UNDERWATER OPTICAL COMM  ·  ${BIT_MS}ms/bit  ·  4B5B"
            textSize = 9f; typeface = Typeface.MONOSPACE
            setTextColor(c(HINT)); letterSpacing = 0.10f
        }, lp(bm = d(18)))
        return header
    }

    private fun buildTabBar(): LinearLayout {
        val tabWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(c(SURFACE))
        }
        val tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnTx = tabBtn("⚡  TRANSMIT", TX)
        btnRx = tabBtn("◎  RECEIVE",  RX)
        tabRow.addView(btnTx, lpW(1f)); tabRow.addView(btnRx, lpW(1f))
        tabWrap.addView(tabRow, lp())
        tabIndicator = View(this).apply { setBackgroundColor(c(TX)) }
        tabWrap.addView(tabIndicator, lp(height = d(2)))
        return tabWrap
    }

    private fun buildTxLayout(): LinearLayout {
        layoutTx = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(c(BG))
            setPadding(d(20), d(18), d(20), d(32))
        }

        layoutTx.addView(sectionLabel("QUICK COMMANDS"), lp(bm = d(10)))

        cmdButtons.clear()
        for (rowStart in QUICK_CMDS.indices step 3) {
            val cmdRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (col in 0 until 3) {
                val idx = rowStart + col
                if (idx >= QUICK_CMDS.size) break
                val (label, code, accent) = QUICK_CMDS[idx]
                val cmdBtn = Button(this).apply {
                    text = label; textSize = 12f; typeface = Typeface.DEFAULT_BOLD
                    setTextColor(c(accent))
                    background = cardDrawable(blendDark(accent, 0.22f), accent, 10f)
                    setPadding(d(4), d(16), d(4), d(16))
                    setOnClickListener {
                        if (!isTransmitting.get()) {
                            selectedCmdIndex = idx
                            updateCmdSelection()
                            pushNotification()
                            etMsg.setText(code)
                            startTx()
                        }
                    }
                }
                cmdButtons.add(cmdBtn)
                val p = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .also { it.marginEnd = if (col < 2) d(6) else 0 }
                cmdRow.addView(cmdBtn, p)
            }
            layoutTx.addView(cmdRow, lp(bm = d(6)))
        }
        updateCmdSelection()

        layoutTx.addView(TextView(this).apply {
            text = "🔊 VOL▲▼ navigate  ·  hold 1.5s to send  ·  🔔 lock-screen controls active"
            textSize = 10f; setTextColor(c(HINT)); gravity = Gravity.CENTER
        }, lp(bm = d(10)))
        layoutTx.addView(View(this).apply { setBackgroundColor(c(DIVIDER)) }, lp(height = 1, bm = d(12)))

        layoutTx.addView(sectionLabel("CUSTOM MESSAGE"), lp(bm = d(10)))
        etMsg = EditText(this).apply {
            setText("Hi"); textSize = 17f; typeface = Typeface.MONOSPACE
            setTextColor(c(WHITE)); background = cardDrawable(SURFACE1, TX_DIM, 10f)
            setPadding(d(16), d(12), d(16), d(12))
            setHintTextColor(c(HINT)); hint = "Type message…"
        }
        layoutTx.addView(etMsg, lp(bm = d(10)))
        layoutTx.addView(TextView(this).apply {
            text = "START ${START_MS}ms  ·  BIT ${BIT_MS}ms  ·  STOP ${STOP_MS}ms  ·  4B5B"
            textSize = 9f; typeface = Typeface.MONOSPACE; setTextColor(c(HINT))
            background = cardDrawable(SURFACE, DIVIDER, 8f)
            setPadding(d(14), d(10), d(14), d(10)); gravity = Gravity.CENTER
        }, lp(bm = d(12)))

        btnSend = Button(this).apply {
            text = "⚡   TRANSMIT"; textSize = 14f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(c(TX)); background = cardDrawable(TX_DIM, TX, 12f)
            setPadding(d(16), d(14), d(16), d(14))
        }
        layoutTx.addView(btnSend, lp(bm = d(10)))

        pbTx = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            visibility = View.GONE; max = 100
            progressDrawable?.setColorFilter(c(TX), android.graphics.PorterDuff.Mode.SRC_IN)
        }
        layoutTx.addView(pbTx, lp(bm = d(4)))
        tvTxProgress = TextView(this).apply {
            textSize = 10f; typeface = Typeface.MONOSPACE
            setTextColor(c(HINT)); gravity = Gravity.CENTER
        }
        layoutTx.addView(tvTxProgress, lp(bm = d(6)))
        tvTxStatus = TextView(this).apply {
            text = "●  READY"; textSize = 13f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(c(RX)); gravity = Gravity.CENTER; letterSpacing = 0.12f
        }
        layoutTx.addView(tvTxStatus, lp())

        return layoutTx
    }

    private fun buildRxLayout(): LinearLayout {
        layoutRx = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setBackgroundColor(c(BG))
            setPadding(d(16), d(14), d(16), d(16))
        }

        // Viewfinder — NO background on SurfaceView (prevents surface-hole bug)
        val vfCard = android.widget.FrameLayout(this).apply { background = cardDrawable(SURFACE, RX_DIM, 12f) }
        surfaceView = SurfaceView(this)
        vfCard.addView(surfaceView, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT, d(130)))
        layoutRx.addView(vfCard, lp(bm = d(10)))

        // Compact brightness row
        val briRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = cardDrawable(SURFACE, RX_DIM, 10f)
            setPadding(d(12), d(8), d(12), d(8))
            gravity = Gravity.CENTER_VERTICAL
        }
        tvBigBrightness = TextView(this).apply {
            text = "---"; textSize = 38f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(c(RX)); minWidth = d(72); gravity = Gravity.CENTER
        }
        briRow.addView(tvBigBrightness, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        briRow.addView(Space(this).apply { minimumWidth = d(10) })
        val briRight = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
        }
        briRight.addView(TextView(this).apply {
            text = "BRIGHTNESS"; textSize = 8f; typeface = Typeface.MONOSPACE
            setTextColor(c(HINT)); letterSpacing = 0.16f
        }, lp(bm = d(4)))
        pbBrightness = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 255; progress = 0
            val tintList = android.content.res.ColorStateList.valueOf(c(RX))
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                progressTintList = tintList
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(c(RX_DIM))
            } else {
                progressDrawable?.setColorFilter(c(RX), android.graphics.PorterDuff.Mode.SRC_IN)
            }
        }
        briRight.addView(pbBrightness, lp())
        briRow.addView(briRight, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        layoutRx.addView(briRow, lp(bm = d(8)))

        val metRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tvThreshold = metricBadge("THR", "--", TX)
        tvFps       = metricBadge("FPS", "--", RX)
        metRow.addView(tvThreshold, lpW(1f, d(8))); metRow.addView(tvFps, lpW(1f))
        layoutRx.addView(metRow, lp(bm = d(8)))

        tvState = TextView(this).apply {
            text = "◌  IDLE — AWAITING SIGNAL"
            textSize = 10f; typeface = Typeface.MONOSPACE; setTextColor(c(HINT))
            background = cardDrawable(SURFACE, DIVIDER, 8f)
            setPadding(d(14), d(10), d(14), d(10))
            gravity = Gravity.CENTER; letterSpacing = 0.07f
        }
        layoutRx.addView(tvState, lp(bm = d(8)))

        layoutRx.addView(sectionLabel("DECODED MESSAGE"), lp(bm = d(8)))
        tvDecoded = TextView(this).apply {
            text = "—"; textSize = 32f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(c(RX)); background = cardDrawable(SURFACE, RX_DIM, 12f)
            setPadding(d(16), d(20), d(16), d(20))
            gravity = Gravity.CENTER; minHeight = d(80)
        }
        layoutRx.addView(tvDecoded, lp(bm = d(10)))

        val actRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnCalibrate = Button(this).apply {
            text = "◎  CALIBRATE"; textSize = 12f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(c(RX)); background = cardDrawable(RX_DIM, RX, 10f)
            setPadding(d(8), d(14), d(8), d(14))
        }
        btnClear = Button(this).apply {
            text = "↺  RESET"; textSize = 12f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(c(LABEL)); background = cardDrawable(SURFACE, LABEL, 10f)
            setPadding(d(8), d(14), d(8), d(14))
        }
        actRow.addView(btnCalibrate, lpW(1f, d(10))); actRow.addView(btnClear, lpW(1f))
        layoutRx.addView(actRow, lp())

        return layoutRx
    }

    // ═════════════════════════════════════════════════════════════════════
    // LISTENERS
    // ═════════════════════════════════════════════════════════════════════

    private fun setupListeners() {
        btnTx.setOnClickListener { showTxMode() }
        btnRx.setOnClickListener { showRxMode() }
        btnSend.setOnClickListener { startTx() }
        btnClear.setOnClickListener {
            cancelReadyCountdown(); decoder.reset()
            tvDecoded.text = "—"; tvDecoded.setTextColor(c(RX))
            tvDecoded.background = cardDrawable(SURFACE, RX_DIM, 12f)
            tvState.text = "◌  IDLE — AWAITING SIGNAL"
            tvState.setTextColor(c(HINT))
            tvState.background = cardDrawable(SURFACE, DIVIDER, 8f)
            startReadyCountdown(CAM_READY_DELAY_MS)
        }
        btnCalibrate.setOnClickListener {
            decoder.calibrate()
            Toast.makeText(this, "Calibrated — aim at flash source.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showTxMode() {
        isTxMode = true
        (layoutTx.parent as? ScrollView)?.visibility = View.VISIBLE
        layoutRx.visibility = View.GONE
        btnTx.setTextColor(c(TX));    btnTx.alpha = 1f
        btnRx.setTextColor(c(LABEL)); btnRx.alpha = 0.50f
        tabIndicator.setBackgroundColor(c(TX))
        cancelReadyCountdown(); closeCam()
        if (cmdButtons.isNotEmpty()) updateCmdSelection()
    }

    private fun showRxMode() {
        isTxMode = false
        (layoutTx.parent as? ScrollView)?.visibility = View.GONE
        layoutRx.visibility = View.VISIBLE
        btnRx.setTextColor(c(RX));    btnRx.alpha = 1f
        btnTx.setTextColor(c(LABEL)); btnTx.alpha = 0.50f
        tabIndicator.setBackgroundColor(c(RX))
        if (hasPerm()) waitForSurfaceThenOpen()
        else ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), PERM)
    }

    private fun waitForSurfaceThenOpen() {
        val holder = surfaceView.holder
        if (holder.surface != null && holder.surface.isValid) { openCamera(); return }
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) { holder.removeCallback(this); openCamera() }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) { closeCam() }
        })
    }

    // ═════════════════════════════════════════════════════════════════════
    // VOLUME-BUTTON HANDS-FREE CONTROL
    // ═════════════════════════════════════════════════════════════════════

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (!isTxMode ||
            (code != KeyEvent.KEYCODE_VOLUME_DOWN && code != KeyEvent.KEYCODE_VOLUME_UP))
            return super.dispatchKeyEvent(event)

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    val armedCode = QUICK_CMDS[selectedCmdIndex].second
                    volHoldRunnable = Runnable {
                        volHoldRunnable = null
                        if (!isTransmitting.get()) { etMsg.setText(armedCode); startTx(); vibrateHold() }
                    }
                    mainHandler.postDelayed(volHoldRunnable!!, 1500L)
                }
            }
            KeyEvent.ACTION_UP -> {
                if (volHoldRunnable != null) {
                    mainHandler.removeCallbacks(volHoldRunnable!!)
                    volHoldRunnable = null
                    stepCmd(if (code == KeyEvent.KEYCODE_VOLUME_DOWN) 1 else -1)
                }
            }
        }
        return true
    }

    private fun updateCmdSelection() {
        cmdButtons.forEachIndexed { idx, btn ->
            val accent = QUICK_CMDS[idx].third
            if (idx == selectedCmdIndex) {
                // Armed: solid accent fill, dark text — stands out as "ready to fire"
                btn.background = cardDrawable(accent, accent, 10f)
                btn.setTextColor(c(BG))
                btn.textSize = 12.5f
            } else {
                // Idle: dark fill + coloured border — visible but not dominant
                btn.background = cardDrawable(blendDark(accent, 0.22f), accent, 10f)
                btn.setTextColor(c(accent))
                btn.textSize = 12f
            }
        }
    }

    private fun vibrateShort() {
        val vib = getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            vib.vibrate(android.os.VibrationEffect.createOneShot(40, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        else @Suppress("DEPRECATION") vib.vibrate(40)
    }

    private fun vibrateHold() {
        val vib = getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator ?: return
        val pattern = longArrayOf(0L, 70L, 60L, 70L)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            vib.vibrate(android.os.VibrationEffect.createWaveform(pattern, -1))
        else @Suppress("DEPRECATION") vib.vibrate(pattern, -1)
    }

    // ═════════════════════════════════════════════════════════════════════
    // CAMERA-READY COUNTDOWN
    // ═════════════════════════════════════════════════════════════════════

    private fun startReadyCountdown(totalMs: Long) {
        cancelReadyCountdown(); decoder.markNotReady()
        val startTime = System.currentTimeMillis()
        val tick = object : Runnable {
            override fun run() {
                val elapsed   = System.currentTimeMillis() - startTime
                val remaining = ((totalMs - elapsed) / 1000.0).coerceAtLeast(0.0)
                tvState.text = "⏳  WARMING UP  %.1fs".format(remaining)
                tvState.setTextColor(c(AMBER))
                tvState.background = cardDrawable(SURFACE, "#3D2200", 8f)
                if (elapsed < totalMs) mainHandler.postDelayed(this, 200L)
            }
        }
        countdownRunnable = tick; mainHandler.post(tick)
        val ready = Runnable {
            decoder.markReady()
            tvState.text = "●  READY — BEGIN TRANSMITTING"
            tvState.setTextColor(c(RX))
            tvState.background = cardDrawable(SURFACE, RX_DIM, 8f)
            Log.d(TAG, "★ Camera ready — decoder armed")
        }
        camReadyRunnable = ready; mainHandler.postDelayed(ready, totalMs)
    }

    private fun cancelReadyCountdown() {
        camReadyRunnable?.let  { mainHandler.removeCallbacks(it) }
        countdownRunnable?.let { mainHandler.removeCallbacks(it) }
        camReadyRunnable = null; countdownRunnable = null
    }

    // ═════════════════════════════════════════════════════════════════════
    // TRANSMITTER
    // ═════════════════════════════════════════════════════════════════════

    private fun startTx() {
        if (isTransmitting.get()) return
        // Guard against submission to a shut-down executor (e.g. called after onDestroy)
        if (txExecutor.isShutdown) return
        val msg = etMsg.text.toString()
        if (msg.isEmpty()) { Toast.makeText(this, "Enter a message", Toast.LENGTH_SHORT).show(); return }
        if (torchCamId == null) { Toast.makeText(this, "No torch", Toast.LENGTH_SHORT).show(); return }

        val bytes = msg.toByteArray(Charsets.UTF_8)
        val bits  = bytes.flatMap { b -> FourB5B.encodeByte(b.toInt() and 0xFF) }
        val estMs = START_MS + bits.size * BIT_MS + STOP_MS

        isTransmitting.set(true)
        btnSend.isEnabled = false; btnSend.text = "◎  TRANSMITTING…"
        pbTx.max = bits.size; pbTx.progress = 0; pbTx.visibility = View.VISIBLE
        tvTxStatus.text = "◎  ${bits.size} BITS  ·  ~${estMs}ms"
        tvTxStatus.setTextColor(c(AMBER))
        Log.d(TAG, "TX '$msg' → ${bits.size} bits  est=${estMs}ms")

        txExecutor.execute {
            try {
                val txStart = System.currentTimeMillis()
                torch(true); sleepUntil(txStart + START_MS); torch(false)
                val dataStart = txStart + START_MS
                bits.forEachIndexed { i, bit ->
                    sleepUntil(dataStart + i * BIT_MS); torch(bit == 1)
                    mainHandler.post { pbTx.progress = i + 1; tvTxProgress.text = "BIT ${i + 1} / ${bits.size}" }
                }
                val stopStart = dataStart + bits.size * BIT_MS
                sleepUntil(stopStart); torch(false); sleepUntil(stopStart + STOP_MS)
                mainHandler.post {
                    tvTxStatus.text = "●  SENT  ·  ${bits.size} BITS"; tvTxStatus.setTextColor(c(RX))
                    tvTxProgress.text = ""; btnSend.isEnabled = true; btnSend.text = "⚡   TRANSMIT"
                    pbTx.visibility = View.GONE
                }
            } catch (e: Exception) {
                mainHandler.post {
                    tvTxStatus.text = "✕  ${e.message}"; tvTxStatus.setTextColor(c(RED))
                    btnSend.isEnabled = true; btnSend.text = "⚡   TRANSMIT"; pbTx.visibility = View.GONE
                }
            } finally { isTransmitting.set(false); stopTorch() }
        }
    }

    // Re-interrupt the thread so the executor can honour shutdown during a long TX
    private fun sleepUntil(targetMs: Long) {
        val rem = targetMs - System.currentTimeMillis()
        if (rem > 0) try { Thread.sleep(rem) }
        catch (e: InterruptedException) { Thread.currentThread().interrupt() }
    }

    private fun torch(on: Boolean) {
        if (isTorchOn == on) return
        try {
            camManager?.setTorchMode(torchCamId ?: return, on)
            isTorchOn = on
        } catch (e: CameraAccessException) {
            Log.e(TAG, "torch: $e")
        }
    }

    private fun stopTorch() {
        try { torchCamId?.let { camManager?.setTorchMode(it, false) } }
        catch (_: CameraAccessException) {}
        finally { isTorchOn = false }
    }

    // ═════════════════════════════════════════════════════════════════════
    // RECEIVER — Camera2
    // ═════════════════════════════════════════════════════════════════════

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        // Prevent double-open if called twice before the first callback fires
        if (cameraDevice != null) return
        val id = rxCamId ?: run { Log.e(TAG, "No back cam"); return }
        closeCam(); cancelReadyCountdown(); decoder.reset()

        imageReader = android.media.ImageReader.newInstance(
            320, 240, android.graphics.ImageFormat.YUV_420_888, 4)
        imageReader!!.setOnImageAvailableListener({ reader ->
            val img = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = img.planes[0]; val buf = plane.buffer; buf.rewind()
                val rs = plane.rowStride; val ps = plane.pixelStride
                val w = img.width; val h = img.height
                val x0 = w*3/10; val y0 = h*3/10; val x1 = w*7/10; val y1 = h*7/10
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

                if (result != null) {
                    Log.d(TAG, "DECODED: $result")
                    val expanded   = CODE_TO_LABEL[result]
                    val displayTxt = if (expanded != null) "[$result]  $expanded" else result
                    val accent     = QUICK_CMDS.firstOrNull { it.second == result }?.third ?: RX
                    mainHandler.post {
                        tvDecoded.text = displayTxt; tvDecoded.setTextColor(c(accent))
                        tvDecoded.background = cardDrawable(SURFACE, blendDark(accent, 0.15f), 12f)
                        tvState.text = "●  DECODED  ·  RESETTING IN 4s"
                        tvState.setTextColor(c(RX))
                        tvState.background = cardDrawable(SURFACE, RX_DIM, 8f)
                    }
                    mainHandler.postDelayed({
                        decoder.reset(); decoder.markReady()
                        tvDecoded.text = "—"; tvDecoded.setTextColor(c(RX))
                        tvDecoded.background = cardDrawable(SURFACE, RX_DIM, 12f)
                        tvState.text = "●  READY — BEGIN TRANSMITTING"
                        tvState.setTextColor(c(RX))
                        tvState.background = cardDrawable(SURFACE, RX_DIM, 8f)
                    }, 4000L)
                }

                if (decoder.totalFrames % 2L == 0L) {
                    val bInt = b.toInt()
                    val briColor = when {
                        bInt > 180 -> TX            // neon yellow — very bright
                        bInt > 110 -> "#AADD00"     // slightly dimmer yellow-green
                        bInt > 60  -> AMBER         // amber mid-range
                        else       -> RED            // red — dark / no signal
                    }
                    val snapState     = decoder.state
                    val snapThreshold = decoder.threshold
                    val snapFps       = decoder.fps()
                    mainHandler.post {
                        tvBigBrightness.text = "%3d".format(bInt)
                        tvBigBrightness.setTextColor(c(briColor))
                        pbBrightness.progress = bInt
                        tvThreshold.text = "THR\n${snapThreshold.toInt()}"
                        tvFps.text       = "FPS\n$snapFps"
                        if (decoder.isReady && snapState != SimpleDecoder.State.DONE) {
                            tvState.text = "◈  ${decoder.stateLabel()}"
                            tvState.setTextColor(c(HINT))
                            tvState.background = cardDrawable(SURFACE, DIVIDER, 8f)
                        }
                    }
                }
            } finally { img.close() }
        }, cameraHandler)

        try {
            camManager!!.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    cameraDevice = cam; createSession(cam)
                    mainHandler.post { startReadyCountdown(CAM_READY_DELAY_MS) }
                }
                override fun onDisconnected(cam: CameraDevice) { cam.close() }
                override fun onError(cam: CameraDevice, err: Int) { cam.close(); Log.e(TAG, "Cam err $err") }
            }, cameraHandler)
        } catch (e: CameraAccessException) { Log.e(TAG, "openCamera: $e") }
    }

    private fun createSession(cam: CameraDevice) {
        val ps = surfaceView.holder.surface
        if (ps == null || !ps.isValid) { Log.e(TAG, "preview surface invalid"); return }
        cam.createCaptureSession(listOf(ps, imageReader!!.surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    captureSession = s; startTwoPhaseCapture(cam, s)
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    Log.e(TAG, "Session config failed")
                }
            }, cameraHandler)
    }

    private fun startTwoPhaseCapture(cam: CameraDevice, session: CameraCaptureSession) {
        val chars     = camManager!!.getCameraCharacteristics(rxCamId!!)
        val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        val bestFps   = fpsRanges?.maxByOrNull { it.upper } ?: Range(15, 30)
        try {
            val u = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surfaceView.holder.surface); addTarget(imageReader!!.surface)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AE_LOCK, false)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, bestFps)
                set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                set<Int>(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            }
            session.setRepeatingRequest(u.build(), null, cameraHandler)
        } catch (e: CameraAccessException) { Log.e(TAG, "Phase 1: $e"); startUnlockedCapture(cam, session); return }
        cameraHandler.postDelayed({
            try {
                val l = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(surfaceView.holder.surface); addTarget(imageReader!!.surface)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    set(CaptureRequest.CONTROL_AE_LOCK, true)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    val mf = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
                    set(CaptureRequest.LENS_FOCUS_DISTANCE, mf * 0.1f)
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, bestFps)
                    set<Int>(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
                    set<Int>(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_FAST)
                    set<Int>(CaptureRequest.STATISTICS_FACE_DETECT_MODE,
                        CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF)
                    set<Int>(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                    set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                    set(CaptureRequest.CONTROL_AWB_LOCK, true)
                }
                session.setRepeatingRequest(l.build(), null, cameraHandler)
                Log.d(TAG, "Phase 2: AE locked FPS=$bestFps")
            } catch (e: CameraAccessException) { Log.e(TAG, "Phase 2: $e"); startUnlockedCapture(cam, session) }
        }, 1000L)
    }

    private fun startUnlockedCapture(cam: CameraDevice, session: CameraCaptureSession) {
        val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(surfaceView.holder.surface); addTarget(imageReader!!.surface)
        }
        session.setRepeatingRequest(req.build(), null, cameraHandler)
        Log.d(TAG, "Fallback: unlocked capture")
    }

    private fun closeCam() {
        try { captureSession?.close() } catch (_: Exception) {}
        try { cameraDevice?.close()   } catch (_: Exception) {}
        try { imageReader?.close()    } catch (_: Exception) {}
        captureSession = null; cameraDevice = null; imageReader = null
    }

    // ═════════════════════════════════════════════════════════════════════
    // UI FACTORY HELPERS
    // ═════════════════════════════════════════════════════════════════════

    private fun tabBtn(text: String, activeColor: String): Button =
        Button(this).apply {
            this.text = text; textSize = 13f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(c(activeColor)); setBackgroundColor(c(SURFACE))
            setPadding(d(8), d(16), d(8), d(16)); gravity = Gravity.CENTER
        }

    private fun sectionLabel(text: String): TextView =
        TextView(this).apply {
            this.text = text; textSize = 9f; typeface = Typeface.MONOSPACE
            setTextColor(c(HINT)); letterSpacing = 0.22f
        }

    private fun metricBadge(label: String, value: String, accent: String): TextView =
        TextView(this).apply {
            text = "$label\n$value"; textSize = 13f; typeface = Typeface.MONOSPACE
            setTextColor(c(accent)); background = cardDrawable(SURFACE, accent, 10f)
            setPadding(d(12), d(10), d(12), d(10)); gravity = Gravity.CENTER
        }

    private fun cardDrawable(bgHex: String, borderHex: String, cornerDp: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = d(cornerDp.toInt()).toFloat()
            setColor(c(bgHex)); setStroke(d(1), c(borderHex))
        }

    private fun ovalDrawable(hex: String): GradientDrawable =
        GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c(hex)) }

    private fun blendDark(hex: String, amount: Float): String {
        val v = Color.parseColor(hex); val dark = Color.parseColor(BG)
        val r = (Color.red(v)   * amount + Color.red(dark)   * (1 - amount)).toInt().coerceIn(0, 255)
        val g = (Color.green(v) * amount + Color.green(dark) * (1 - amount)).toInt().coerceIn(0, 255)
        val b = (Color.blue(v)  * amount + Color.blue(dark)  * (1 - amount)).toInt().coerceIn(0, 255)
        return "#%02X%02X%02X".format(r, g, b)
    }

    private fun hasPerm() = ContextCompat.checkSelfPermission(this,
        Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
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
    private fun d(v: Int)      = (v * resources.displayMetrics.density).toInt()
    private fun lp(height: Int = LinearLayout.LayoutParams.WRAP_CONTENT, bm: Int = 0) =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height)
            .also { it.bottomMargin = bm }
    private fun lpW(w: Float, rm: Int = 0) =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w)
            .also { it.rightMargin = rm }
}


// ════════════════════════════════════════════════════════════════════════════
//  NotifActionReceiver
// ════════════════════════════════════════════════════════════════════════════

class NotifActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.startActivity(
            Intent(context, MainActivity::class.java).apply {
                action = intent.action
                flags  = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(UFlashNotifService.EXTRA_CODE,  intent.getStringExtra(UFlashNotifService.EXTRA_CODE))
                putExtra(UFlashNotifService.EXTRA_LABEL, intent.getStringExtra(UFlashNotifService.EXTRA_LABEL))
            }
        )
    }
}


// ════════════════════════════════════════════════════════════════════════════
// SIMPLE DECODER
// ════════════════════════════════════════════════════════════════════════════

class SimpleDecoder {

    enum class State { IDLE, WAIT_FOR_LOW, RECEIVING, DONE }

    companion object {
        // FIX: moved timing constants here so they are true compile-time constants,
        // not per-instance fields allocated on every SimpleDecoder instantiation.
        private const val START_TRIGGER_MS  = 480L
        private const val STOP_TRIGGER_MS   = 650L
        private const val MIN_RUN_MS        = 80L
        private const val MAX_RX_TIMEOUT_MS = 60_000L  // FIX: named, was magic number 60_000L
        private const val CALIB_OFFSET      = 30.0
    }

    var state       = State.IDLE; private set
    var totalFrames = 0L
    var isReady     = false; private set
    fun markReady()    { isReady = true;  Log.d("UFlash", "Decoder armed") }
    fun markNotReady() { isReady = false; Log.d("UFlash", "Decoder disarmed") }

    private val win = ArrayDeque<Double>(200)
    var threshold   = 128.0; private set
    var swing       = 0.0;   private set
    private var calibBaseline = -1.0
    private var lastMs  = 0L
    private var avgFrMs = 33.0
    private var runIsHigh  = false
    private var runStartMs = 0L

    private data class Edge(val ms: Long, val isHigh: Boolean)
    private val edges = mutableListOf<Edge>()
    private var t0Ms  = 0L

    fun calibrate() {
        if (win.size >= 10) {
            calibBaseline = win.sorted()[win.size / 2]
            Log.d("UFlash", "Calibrated baseline=$calibBaseline thr=${calibBaseline + CALIB_OFFSET}")
        }
    }

    fun reset() {
        state = State.IDLE; isReady = false
        edges.clear(); t0Ms = 0L
        win.clear(); threshold = 128.0; swing = 0.0
        runIsHigh = false; runStartMs = 0L; lastMs = 0; avgFrMs = 33.0
    }

    fun fps() = "%.1f".format(1000.0 / avgFrMs)

    fun stateLabel(): String {
        val runDur = if (runStartMs > 0 && lastMs > 0) lastMs - runStartMs else 0L
        return when (state) {
            State.IDLE         -> "IDLE  thr=${threshold.toInt()} swing=${swing.toInt()}"
            State.WAIT_FOR_LOW -> "WAIT_LOW  highDur=${runDur}ms"
            State.RECEIVING    -> "RX ${edges.size}edges  ${fps()}fps  lowRun=${if (!runIsHigh) runDur else 0}ms/$STOP_TRIGGER_MS"
            State.DONE         -> "DONE — auto-resetting…"
        }
    }

    fun push(brightness: Double, nowMs: Long): String? {
        totalFrames++
        if (lastMs > 0) avgFrMs = avgFrMs * 0.90 +
                (nowMs - lastMs).toDouble().coerceIn(5.0, 500.0) * 0.10
        lastMs = nowMs
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
        if (!isReady) return null
        val isHigh = brightness > threshold
        if (runStartMs == 0L) {
            runIsHigh = isHigh; runStartMs = nowMs
        } else if (isHigh != runIsHigh) {
            val runDur = nowMs - runStartMs
            when (state) {
                State.RECEIVING -> {
                    if (runDur >= MIN_RUN_MS) {
                        edges.add(Edge(nowMs, isHigh))
                        Log.d("UFlash", "edge @${nowMs-t0Ms}ms  ${if(isHigh)"HIGH" else "LOW"}  dur=${runDur}ms")
                    } else {
                        Log.d("UFlash", "NOISE @${nowMs-t0Ms}ms  dur=${runDur}ms"); return null
                    }
                }
                else -> {}
            }
            runIsHigh = isHigh; runStartMs = nowMs
        }
        val runDurMs = nowMs - runStartMs
        when (state) {
            State.IDLE -> {
                if (isHigh && runDurMs >= START_TRIGGER_MS && (swing >= 12.0 || calibBaseline >= 0)) {
                    state = State.WAIT_FOR_LOW
                    Log.d("UFlash", "★ START thr=${threshold.toInt()} swing=${swing.toInt()}")
                }
            }
            State.WAIT_FOR_LOW -> {
                if (!isHigh) {
                    state = State.RECEIVING; t0Ms = nowMs
                    edges.clear(); edges.add(Edge(nowMs, false))
                    Log.d("UFlash", "★ Data t0=$t0Ms")
                }
            }
            State.RECEIVING -> {
                if (!isHigh && runDurMs >= STOP_TRIGGER_MS) {
                    state = State.DONE
                    Log.d("UFlash", "★ STOP  ${edges.size}edges  lowRun=${runDurMs}ms")
                    return decode(runStartMs + BIT_MS)
                }
                if ((nowMs - t0Ms) > MAX_RX_TIMEOUT_MS) { state = State.DONE; return decode(nowMs) }
            }
            State.DONE -> {}
        }
        return null
    }

    private fun decode(stopMs: Long): String {
        if (edges.size < 2) return "(too few edges: ${edges.size})"
        val BIT = BIT_MS.toDouble(); val bits = mutableListOf<Int>()
        for (i in 0 until edges.size - 1) {
            val runMs = edges[i+1].ms - edges[i].ms
            if (runMs < MIN_RUN_MS) continue
            val nBits = ((runMs + BIT/2) / BIT).toInt().coerceAtLeast(1)
            repeat(nBits) { bits.add(if (edges[i].isHigh) 1 else 0) }
            Log.d("UFlash", "run $i: ${if(edges[i].isHigh)"HIGH" else "LOW"} ${runMs}ms → $nBits bits")
        }
        if (edges.isNotEmpty()) {
            val lastRunMs = stopMs - edges.last().ms
            if (lastRunMs >= MIN_RUN_MS) {
                val nBits = ((lastRunMs + BIT/2) / BIT).toInt().coerceAtLeast(0)
                repeat(nBits) { bits.add(if (edges.last().isHigh) 1 else 0) }
                Log.d("UFlash", "last run ${lastRunMs}ms → $nBits bits")
            }
        }
        Log.d("UFlash", "bits(${bits.size}): ${bits.joinToString("")}")
        if (bits.size < 10) return "RAW:${bits.joinToString("")} (need ≥10 for 4B5B)"
        val sb = StringBuilder(); var i = 0; var bad = 0
        while (i + 9 < bits.size) {
            val byte = FourB5B.decodeTenBits(bits.subList(i, i+10))
            if (byte < 0) { bad++; sb.append("?") }
            else sb.append(if (byte in 32..126) byte.toChar() else "[$byte]")
            i += 10
        }
        val leftover = bits.size - i
        if (leftover > 0) Log.d("UFlash", "leftover $leftover bits")
        if (bad > 0)      Log.d("UFlash", "$bad bad 4B5B symbol(s)")
        return sb.toString().ifEmpty { "RAW:${bits.joinToString("")}" }
    }
}


// ════════════════════════════════════════════════════════════════════════════
// 4B5B CODEC
// ════════════════════════════════════════════════════════════════════════════

object FourB5B {
    private val ENC = intArrayOf(
        0b11110, 0b01001, 0b10100, 0b10101,
        0b01010, 0b01011, 0b01110, 0b01111,
        0b10010, 0b10011, 0b10110, 0b10111,
        0b11010, 0b11011, 0b11100, 0b11101
    )
    // FIX: use apply instead of also { d -> } to avoid shadowing outer scope
    private val DEC = IntArray(32) { -1 }.apply {
        ENC.forEachIndexed { n, code -> this[code] = n }
    }
    fun encodeByte(byte: Int): List<Int> =
        nibbleToBits(ENC[(byte shr 4) and 0xF]) + nibbleToBits(ENC[byte and 0xF])
    fun decodeTenBits(bits: List<Int>): Int {
        if (bits.size < 10) return -1
        val hi = DEC[bitsToInt(bits.subList(0, 5))]
        val lo = DEC[bitsToInt(bits.subList(5, 10))]
        return if (hi < 0 || lo < 0) -1 else (hi shl 4) or lo
    }
    private fun nibbleToBits(code: Int): List<Int> = (4 downTo 0).map { (code shr it) and 1 }
    private fun bitsToInt(bits: List<Int>): Int = bits.fold(0) { acc, b -> (acc shl 1) or b }
}
