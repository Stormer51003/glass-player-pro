package com.stormer.glassplayer

import android.app.Presentation
import android.content.*
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.view.*
import android.view.GestureDetector
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.stormer.glassplayer.databinding.ActivityMainBinding
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var displayManager: DisplayManager
    private lateinit var audioManager: AudioManager

    private var player: ExoPlayer? = null
    private var audioFx: AudioEffectsManager? = null
    private var glassPresentation: GlassPresentation? = null
    private var progressRunnable: Runnable? = null

    // Video state
    private var currentZoom = ZoomMode.FIT
    private var currentAspect = AspectRatio.AUTO
    private var currentSbs = SbsMode.OFF

    // Batch A state
    private lateinit var prefs: Prefs
    private var currentUri: String? = null
    private var playbackSpeed = 1.0f
    private var sleepTimerRunnable: Runnable? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private var abPointA = -1L
    private var abPointB = -1L
    private lateinit var sbsButtonMap: List<Pair<Button, SbsMode>>

    // ── Display listener ────────────────────────────────────────────────────
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            val d = displayManager.getDisplay(displayId) ?: return
            if (d.displayId != Display.DEFAULT_DISPLAY) {
                updateGlassStatus(true)
                player?.let { showOnGlass(it, d) }
            }
        }
        override fun onDisplayRemoved(displayId: Int) {
            glassPresentation?.dismiss(); glassPresentation = null
            updateGlassStatus(false)
        }
        override fun onDisplayChanged(displayId: Int) {}
    }

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            runCatching {
                contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            loadVideo(it)
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If the previous run crashed, show the stack trace instead of starting normally
        val crashFile = java.io.File(filesDir, "last_crash.txt")
        if (crashFile.exists()) {
            val crashText = crashFile.readText()
            crashFile.delete()
            val tv = android.widget.TextView(this).apply {
                text = crashText
                setTextColor(0xFFFF5555.toInt())
                setBackgroundColor(0xFF0A1628.toInt())
                setPadding(32, 64, 32, 32)
                textSize = 11f
                setTextIsSelectable(true)
            }
            setContentView(android.widget.ScrollView(this).apply { addView(tv) })
            return
        }

        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            prefs = Prefs(this)

            displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

            setupControls()
            setupAudioTab()
            setupVideoTab()
            setupPlaybackExtras()
            requestPermissions()

            val displays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            updateGlassStatus(displays.isNotEmpty())
            displayManager.registerDisplayListener(displayListener, null)

            intent?.let { handleIntent(it) }
        } catch (e: Exception) {
            val sw = java.io.StringWriter()
            e.printStackTrace(java.io.PrintWriter(sw))
            val tv = android.widget.TextView(this).apply {
                text = "Startup error:\n\n$sw"
                setTextColor(0xFFFF5555.toInt())
                setBackgroundColor(0xFF0A1628.toInt())
                setPadding(32, 64, 32, 32)
                textSize = 11f
                setTextIsSelectable(true)
            }
            setContentView(android.widget.ScrollView(this).apply { addView(tv) })
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIntent(intent) }

    override fun onPause() {
        super.onPause()
        saveCurrentPosition()
    }

    override fun onDestroy() {
        super.onDestroy()
        saveCurrentPosition()
        sleepTimerRunnable?.let { uiHandler.removeCallbacks(it) }
        if (::binding.isInitialized) {
            progressRunnable?.let { binding.seekBar.removeCallbacks(it) }
        }
        if (::displayManager.isInitialized) {
            displayManager.unregisterDisplayListener(displayListener)
        }
        audioFx?.release()
        player?.release()
        glassPresentation?.dismiss()
    }

    // ── Tab switching ─────────────────────────────────────────────────────────
    private fun setupControls() {
        binding.btnPickFile.setOnClickListener { filePicker.launch(arrayOf("video/*")) }
        binding.btnPlayPause.setOnClickListener {
            player?.let { if (it.isPlaying) it.pause() else it.play(); updatePlayPause(it.isPlaying) }
        }
        binding.btnRewind.setOnClickListener { player?.seekTo((player!!.currentPosition - 10_000).coerceAtLeast(0)) }
        binding.btnForward.setOnClickListener { player?.seekTo(player!!.currentPosition + 10_000) }

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) player?.let { it.seekTo(it.duration * p / 1000L) }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        binding.tabAudio.setOnClickListener { showTab(true) }
        binding.tabVideo.setOnClickListener { showTab(false) }
        showTab(true)
        updatePlayPause(false)
        updateGlassStatus(false)
    }

    private fun showTab(audioTab: Boolean) {
        binding.panelAudio.visibility = if (audioTab) View.VISIBLE else View.GONE
        binding.panelVideo.visibility = if (audioTab) View.GONE else View.VISIBLE
        binding.tabAudio.alpha = if (audioTab) 1f else 0.4f
        binding.tabVideo.alpha = if (audioTab) 0.4f else 1f
    }

    // ── Audio tab ─────────────────────────────────────────────────────────────
    private fun setupAudioTab() {
        // Loudness boost
        binding.seekBoost.max = 1000
        binding.seekBoost.progress = 500
        binding.tvBoostVal.text = "500 mB"
        binding.seekBoost.onProgress { v -> binding.tvBoostVal.text = "$v mB"; audioFx?.setBoost(v) }

        // Bass boost
        binding.seekBass.max = 1000
        binding.seekBass.progress = 0
        binding.tvBassVal.text = "0"
        binding.seekBass.onProgress { v -> binding.tvBassVal.text = "$v"; audioFx?.setBass(v) }

        // Virtualizer
        binding.seekVirt.max = 1000
        binding.seekVirt.progress = 500
        binding.tvVirtVal.text = "500"
        binding.seekVirt.onProgress { v -> binding.tvVirtVal.text = "$v"; audioFx?.setVirtualizer(v) }

        // Vocal boost toggle
        binding.btnVocals.setOnClickListener {
            val fx = audioFx ?: return@setOnClickListener
            fx.vocalsBoostActive = !fx.vocalsBoostActive
            fx.applyVocalBoost(fx.vocalsBoostActive)
            binding.btnVocals.alpha = if (fx.vocalsBoostActive) 1f else 0.4f
            binding.btnVocals.text = if (fx.vocalsBoostActive) "🎤 Vocals ON" else "🎤 Vocals OFF"
        }

        // EQ preset spinner
        val presetLabels = EqPreset.entries.map { it.label }
        binding.spinnerEqPreset.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, presetLabels)
        binding.spinnerEqPreset.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val preset = EqPreset.entries[pos]
                audioFx?.applyPreset(preset)
                updateEqSliders()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        // EQ band sliders (-1500 to +1500 mB, slider 0–3000)
        val eqSliders = listOf(binding.seekEq0, binding.seekEq1, binding.seekEq2, binding.seekEq3, binding.seekEq4)
        val eqLabels  = listOf(binding.tvEq0, binding.tvEq1, binding.tvEq2, binding.tvEq3, binding.tvEq4)
        eqSliders.forEachIndexed { i, sb ->
            sb.max = 3000; sb.progress = 1500
            sb.onProgress { v ->
                val level = v - 1500
                eqLabels[i].text = if (level >= 0) "+$level" else "$level"
                audioFx?.setEqBand(i, level)
            }
        }

        // Reverb spinner
        val reverbLabels = ReverbPresets.list.map { it.first }
        binding.spinnerReverb.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, reverbLabels)
        binding.spinnerReverb.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                audioFx?.setReverb(ReverbPresets.list[pos].second)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun updateEqSliders() {
        val fx = audioFx ?: return
        val sliders = listOf(binding.seekEq0, binding.seekEq1, binding.seekEq2, binding.seekEq3, binding.seekEq4)
        val labels  = listOf(binding.tvEq0, binding.tvEq1, binding.tvEq2, binding.tvEq3, binding.tvEq4)
        fx.eqBands.forEachIndexed { i, level ->
            if (i < sliders.size) {
                sliders[i].progress = level + 1500
                labels[i].text = if (level >= 0) "+$level" else "$level"
            }
        }
    }

    // ── Video tab ─────────────────────────────────────────────────────────────
    private fun setupVideoTab() {
        // Zoom mode buttons
        val zoomBtns = listOf(
            binding.btnZoomFit to ZoomMode.FIT,
            binding.btnZoomFill to ZoomMode.FILL,
            binding.btnZoomStretch to ZoomMode.STRETCH
        )
        zoomBtns.forEach { (btn, mode) ->
            btn.setOnClickListener {
                currentZoom = mode
                applyVideoSettings()
                zoomBtns.forEach { (b, _) -> b.alpha = 0.4f }
                btn.alpha = 1f
            }
        }
        binding.btnZoomFit.alpha = 1f

        // Aspect ratio buttons
        val arBtns = listOf(
            binding.btnAuto to AspectRatio.AUTO,
            binding.btn169 to AspectRatio.RATIO_16_9,
            binding.btn43 to AspectRatio.RATIO_4_3,
            binding.btn219 to AspectRatio.RATIO_21_9
        )
        arBtns.forEach { (btn, ar) ->
            btn.setOnClickListener {
                currentAspect = ar
                applyVideoSettings()
                arBtns.forEach { (b, _) -> b.alpha = 0.4f }
                btn.alpha = 1f
            }
        }
        binding.btnAuto.alpha = 1f

        // SBS / OU mode buttons
        sbsButtonMap = listOf(
            binding.btnSbsOff      to SbsMode.OFF,
            binding.btnSbsFull     to SbsMode.SBS_FULL,
            binding.btnSbsFullSwap to SbsMode.SBS_FULL_SWAP,
            binding.btnSbsHalf     to SbsMode.SBS_HALF,
            binding.btnSbsHalfSwap to SbsMode.SBS_HALF_SWAP,
            binding.btnOuFull      to SbsMode.OU_FULL,
            binding.btnOuHalf      to SbsMode.OU_HALF
        )
        sbsButtonMap.forEach { (btn, mode) ->
            btn.setOnClickListener {
                currentSbs = mode
                applyVideoSettings()
                highlightSbsButton(mode)
            }
        }
        highlightSbsButton(SbsMode.OFF)
    }

    private fun highlightSbsButton(active: SbsMode) {
        sbsButtonMap.forEach { (b, m) -> b.alpha = if (m == active) 1f else 0.4f }
    }

    // ── Batch A: speed, sleep timer, A-B repeat, recents ───────────────────────
    private fun setupPlaybackExtras() {
        // Playback speed cycle button: 0.5 → 0.75 → 1.0 → 1.25 → 1.5 → 2.0
        val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        binding.btnSpeed.text = "1.0×"
        binding.btnSpeed.setOnClickListener {
            val idx = speeds.indexOf(playbackSpeed).let { if (it < 0) 2 else it }
            playbackSpeed = speeds[(idx + 1) % speeds.size]
            binding.btnSpeed.text = "${playbackSpeed}×"
            runCatching { player?.setPlaybackSpeed(playbackSpeed) }
        }

        // Sleep timer cycle: Off → 15 → 30 → 45 → 60 min
        val timers = listOf(0, 15, 30, 45, 60)
        var timerIdx = 0
        binding.btnSleep.text = "💤 Off"
        binding.btnSleep.setOnClickListener {
            timerIdx = (timerIdx + 1) % timers.size
            val mins = timers[timerIdx]
            sleepTimerRunnable?.let { uiHandler.removeCallbacks(it) }
            if (mins == 0) {
                binding.btnSleep.text = "💤 Off"
            } else {
                binding.btnSleep.text = "💤 ${mins}m"
                sleepTimerRunnable = Runnable {
                    runCatching { player?.pause() }
                    binding.btnSleep.text = "💤 Off"
                    timerIdx = 0
                }
                uiHandler.postDelayed(sleepTimerRunnable!!, mins * 60_000L)
            }
        }

        // A-B repeat: first tap sets A, second sets B, third clears
        binding.btnAbRepeat.text = "A-B"
        binding.btnAbRepeat.setOnClickListener {
            val p = player ?: return@setOnClickListener
            when {
                abPointA < 0 -> {
                    abPointA = p.currentPosition
                    binding.btnAbRepeat.text = "A●—B"
                }
                abPointB < 0 -> {
                    abPointB = p.currentPosition
                    if (abPointB <= abPointA) { // B before A — swap
                        val t = abPointA; abPointA = abPointB; abPointB = t
                    }
                    binding.btnAbRepeat.text = "A—B●"
                }
                else -> {
                    abPointA = -1L; abPointB = -1L
                    binding.btnAbRepeat.text = "A-B"
                }
            }
        }

        // Recent files button
        binding.btnRecents.setOnClickListener { showRecentsDialog() }
    }

    private fun showRecentsDialog() {
        val recents = runCatching { prefs.getRecents() }.getOrDefault(emptyList())
        if (recents.isEmpty()) {
            Toast.makeText(this, "No recent files yet", Toast.LENGTH_SHORT).show()
            return
        }
        val names = recents.map { it.second }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Recent files")
            .setItems(names) { _, which ->
                val uriStr = recents[which].first
                runCatching { loadVideo(Uri.parse(uriStr)) }
                    .onFailure { Toast.makeText(this, "Couldn't open — file may have moved", Toast.LENGTH_SHORT).show() }
            }
            .setNegativeButton("Clear list") { _, _ -> prefs.clearRecents() }
            .setPositiveButton("Cancel", null)
            .show()
    }

    private fun applyVideoSettings() {
        glassPresentation?.applyVideoSettings(currentZoom, currentAspect, currentSbs)
    }

    // ── Player ────────────────────────────────────────────────────────────────
    private fun handleIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_VIEW) intent.data?.let { loadVideo(it) }
    }

    private fun loadVideo(uri: Uri) {
        // Save position of the outgoing file before tearing down
        saveCurrentPosition()

        audioFx?.release()
        player?.release()
        glassPresentation?.dismiss()
        glassPresentation = null

        val fileName = getFileName(uri)
        binding.tvFileName.text = fileName
        currentUri = uri.toString()

        // Record in recents
        runCatching { prefs.addRecent(uri.toString(), fileName) }

        // Auto-detect 3D layout from filename (only if user hasn't manually set one)
        val detected = ThreeDDetector.detect(fileName)
        if (detected != SbsMode.OFF) {
            currentSbs = detected
            runOnUiThread { highlightSbsButton(detected) }
        }

        val newPlayer = ExoPlayer.Builder(this).build()
        player = newPlayer

        val sessionId = newPlayer.audioSessionId
        if (sessionId != AudioManager.ERROR) {
            audioFx = AudioEffectsManager(sessionId).apply {
                init()
                setBoost(binding.seekBoost.progress)
                setBass(binding.seekBass.progress)
                setVirtualizer(binding.seekVirt.progress)
            }
        }

        newPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = runOnUiThread { updatePlayPause(isPlaying) }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) runOnUiThread { startProgressUpdater() }
            }
        })

        newPlayer.setMediaItem(MediaItem.fromUri(uri))
        newPlayer.prepare()
        newPlayer.setPlaybackSpeed(playbackSpeed)

        // Resume from saved position if we have one (and it's not basically the end)
        val saved = prefs.getPosition(uri.toString())
        if (saved > 3000) newPlayer.seekTo(saved)

        newPlayer.play()

        val displays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        if (displays.isNotEmpty()) showOnGlass(newPlayer, displays[0])
    }

    private fun saveCurrentPosition() {
        if (!::prefs.isInitialized) return
        val p = player ?: return
        val u = currentUri ?: return
        runCatching {
            val pos = p.currentPosition
            if (pos > 0 && p.duration > 0 && pos < p.duration - 5000) prefs.savePosition(u, pos)
            else prefs.savePosition(u, 0L) // finished — clear resume
        }
    }

    private fun showOnGlass(player: ExoPlayer, display: Display) {
        glassPresentation?.dismiss()
        glassPresentation = GlassPresentation(this, display, player).also {
            it.show()
            it.applyVideoSettings(currentZoom, currentAspect, currentSbs)
        }
    }

    // ── Progress ───────────────────────────────────────────────────────────────
    private fun startProgressUpdater() {
        progressRunnable?.let { binding.seekBar.removeCallbacks(it) }
        progressRunnable = object : Runnable {
            override fun run() {
                player?.let { p ->
                    if (p.duration > 0) {
                        binding.seekBar.progress = (p.currentPosition * 1000 / p.duration).toInt()
                        binding.tvPos.text = formatTime(p.currentPosition)
                        binding.tvDur.text = formatTime(p.duration)
                    }
                    // A-B repeat: jump back to A once we pass B
                    if (abPointA in 0 until abPointB && p.currentPosition >= abPointB) {
                        p.seekTo(abPointA)
                    }
                }
                binding.seekBar.postDelayed(this, 500)
            }
        }
        binding.seekBar.post(progressRunnable!!)
    }

    // ── Helpers ────────────────────────────────────────────────────────────────
    private fun updateGlassStatus(connected: Boolean) {
        binding.tvGlassStatus.text = if (connected) "✅ RayNeo connected" else "⚠️ No glasses detected"
    }

    private fun updatePlayPause(playing: Boolean) {
        binding.btnPlayPause.text = if (playing) "⏸" else "▶"
    }

    private fun formatTime(ms: Long): String {
        val h = TimeUnit.MILLISECONDS.toHours(ms)
        val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        val s = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    private fun getFileName(uri: Uri) = try {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
            if (c.moveToFirst() && i >= 0) c.getString(i) else uri.lastPathSegment ?: "Video"
        } ?: uri.lastPathSegment ?: "Video"
    } catch (e: Exception) { uri.lastPathSegment ?: "Video" }

    private fun requestPermissions() {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            android.Manifest.permission.READ_MEDIA_VIDEO
        else android.Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this, arrayOf(perm), 100)
    }
}

// ── SeekBar extension ─────────────────────────────────────────────────────────
fun SeekBar.onProgress(block: (Int) -> Unit) {
    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) = block(p)
        override fun onStartTrackingTouch(sb: SeekBar) {}
        override fun onStopTrackingTouch(sb: SeekBar) {}
    })
}

// ── Glass Presentation ────────────────────────────────────────────────────────
class GlassPresentation(
    context: Context,
    display: Display,
    private val player: ExoPlayer
) : Presentation(context, display) {

    private var playerView: PlayerView? = null
    // For SBS swap: player renders to this offscreen TextureView,
    // and a SwapSurfaceView reads its bitmap and redraws halves swapped.
    private var swapOverlay: SwapSurfaceView? = null
    private var hiddenTexture: TextureView? = null
    private var currentSbs = SbsMode.OFF

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window?.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        setContentView(R.layout.presentation_glass)

        playerView = findViewById<PlayerView>(R.id.glassPlayerView).also {
            it.player = player
            it.useController = false
            it.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        swapOverlay  = findViewById(R.id.swapOverlay)
        hiddenTexture = findViewById(R.id.hiddenTexture)
    }

    fun applyVideoSettings(zoom: ZoomMode, aspect: AspectRatio, sbs: SbsMode) {
        currentSbs = sbs
        val pv = playerView ?: return
        val overlay = swapOverlay ?: return
        val hidden  = hiddenTexture ?: return

        if (sbs.swap) {
            // Route player to hidden TextureView; SwapSurfaceView reads + redraws swapped
            pv.player = null
            pv.visibility = View.INVISIBLE
            overlay.visibility = View.VISIBLE
            player.setVideoTextureView(hidden)
            // Kick off the swap draw loop
            overlay.startSwapping(hidden, sbs == SbsMode.SBS_HALF_SWAP)
        } else {
            overlay.visibility = View.GONE
            overlay.stopSwapping()
            player.clearVideoTextureView(hidden)
            pv.visibility = View.VISIBLE
            // Reattaching the player to the PlayerView restores its managed surface
            pv.player = player

            when (sbs) {
                SbsMode.OFF -> {
                    pv.resizeMode = zoom.resizeMode
                    if (aspect != AspectRatio.AUTO) applyAspect(pv, aspect)
                    else resetSize(pv, zoom)
                }
                SbsMode.SBS_FULL -> pv.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                SbsMode.SBS_HALF -> pv.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
                SbsMode.OU_FULL -> pv.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                SbsMode.OU_HALF -> pv.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT
                else -> {}
            }
        }
    }

    private fun applyAspect(pv: PlayerView, aspect: AspectRatio) {
        pv.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        pv.post {
            val pw = (pv.parent as? View)?.width  ?: pv.width
            val ph = (pv.parent as? View)?.height ?: pv.height
            val r  = aspect.ratio
            val (w, h) = if (pw.toFloat() / ph > r) Pair((ph * r).toInt(), ph) else Pair(pw, (pw / r).toInt())
            pv.layoutParams = pv.layoutParams.also { it.width = w; it.height = h }
        }
    }

    private fun resetSize(pv: PlayerView, zoom: ZoomMode) {
        pv.post {
            pv.layoutParams = pv.layoutParams.also {
                it.width  = ViewGroup.LayoutParams.MATCH_PARENT
                it.height = ViewGroup.LayoutParams.MATCH_PARENT
            }
            pv.resizeMode = zoom.resizeMode
        }
    }
}
// ── SwapSurfaceView — redraws SBS frame with halves swapped ──────────────────
// Runs a Choreographer-driven loop: each vsync it grabs the TextureView bitmap
// and blits Right half → left slot, Left half → right slot onto its own canvas.
class SwapSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null
) : android.view.SurfaceView(context, attrs), android.view.Choreographer.FrameCallback {

    private var source: TextureView? = null
    private var halfWidth: Boolean = false
    private val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
    private val srcLeft  = android.graphics.Rect()
    private val srcRight = android.graphics.Rect()
    private val dstLeft  = android.graphics.RectF()
    private val dstRight = android.graphics.RectF()
    private var running = false

    fun startSwapping(tv: TextureView, halfWidth: Boolean) {
        source = tv
        this.halfWidth = halfWidth
        if (!running) {
            running = true
            android.view.Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun stopSwapping() {
        running = false
        source = null
        android.view.Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val tv = source ?: return
        val bmp = tv.bitmap ?: run {
            android.view.Choreographer.getInstance().postFrameCallback(this)
            return
        }

        val bw = bmp.width
        val bh = bmp.height
        val half = bw / 2

        // Source: right half of encoded frame → draw into left slot on display
        //         left  half of encoded frame → draw into right slot on display
        srcRight.set(half, 0, bw, bh)   // encoded right eye
        srcLeft.set(0, 0, half, bh)      // encoded left eye

        val dw = width.toFloat()
        val dh = height.toFloat()
        dstLeft.set(0f, 0f, dw / 2f, dh)         // display left slot  ← gets encoded right
        dstRight.set(dw / 2f, 0f, dw, dh)         // display right slot ← gets encoded left

        val canvas = holder.lockCanvas() ?: run {
            bmp.recycle()
            android.view.Choreographer.getInstance().postFrameCallback(this)
            return
        }
        try {
            canvas.drawColor(android.graphics.Color.BLACK)
            canvas.drawBitmap(bmp, srcRight, dstLeft, paint)   // swap: right → left
            canvas.drawBitmap(bmp, srcLeft,  dstRight, paint)  // swap: left  → right
        } finally {
            holder.unlockCanvasAndPost(canvas)
            bmp.recycle()
        }
        android.view.Choreographer.getInstance().postFrameCallback(this)
    }
}
