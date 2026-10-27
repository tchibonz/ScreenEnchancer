package com.example.screenenhancer

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

/**
 * Video player that runs every frame through the GPU enhancement shader
 * (adaptive sharpening + contrast + saturation) before it reaches the screen.
 */
class MainActivity : Activity() {

    private companion object {
        const val REQ_VIDEO = 2001
        const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
        const val WRAP = FrameLayout.LayoutParams.WRAP_CONTENT
    }

    private val main = Handler(Looper.getMainLooper())

    private lateinit var root: FrameLayout
    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: VideoRenderer
    private lateinit var side: SidePanel
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    private lateinit var panel: LinearLayout
    private lateinit var hint: TextView
    private lateinit var playBtn: Button
    private lateinit var seek: SeekBar
    private lateinit var timeText: TextView

    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var videoW = 0
    private var videoH = 0
    private var prepared = false
    private var pendingUri: Uri? = null
    private var userSeeking = false
    private var resumeAfterSurface = false

    private val ticker = object : Runnable {
        override fun run() {
            val mp = player
            if (mp != null && prepared && !userSeeking) {
                try {
                    seek.progress = mp.currentPosition
                    timeText.text = fmt(mp.currentPosition) + " / " + fmt(mp.duration)
                } catch (_: IllegalStateException) {
                }
            }
            main.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.load(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)

        glView = GLSurfaceView(this)
        glView.setEGLContextClientVersion(3)
        renderer = VideoRenderer(
            this, glView,
            { s -> main.post { onSurface(s) } },
            { t -> main.post { if (::side.isInitialized) side.setStatus(t) } }
        )
        glView.setRenderer(renderer)

        // Feed the phone's thermal state to the renderer so it can back off before the phone gets hot.
        val pm = getSystemService(PowerManager::class.java)
        renderer.thermalStatus = pm.currentThermalStatus
        val tl = PowerManager.OnThermalStatusChangedListener { status -> renderer.thermalStatus = status }
        thermalListener = tl
        pm.addThermalStatusListener(mainExecutor, tl)
        glView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        glView.setOnClickListener { togglePanel() }
        root.addView(glView, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER))

        hint = TextView(this).apply {
            text = "Tap \"Open video\" below"
            setTextColor(Color.LTGRAY)
            textSize = 16f
        }
        root.addView(hint, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))

        buildPanel()
        side = SidePanel(this, root, true) { glView.requestRender() }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fit() }
    }

    private fun buildPanel() {
        val d = resources.displayMetrics.density
        val pad = (10 * d).toInt()

        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xB0000000.toInt())
            setPadding(pad, pad, pad, pad)
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val openBtn = Button(this).apply {
            text = "Open video"
            setOnClickListener { pickVideo() }
        }
        val browserBtn = Button(this).apply {
            text = "Browser"
            setOnClickListener { startActivity(Intent(this@MainActivity, BrowserActivity::class.java)) }
        }
        val settingsBtn = Button(this).apply {
            text = "Ayarlar"
            setOnClickListener { side.toggle() }
        }
        playBtn = Button(this).apply {
            text = "Play"
            setOnClickListener { togglePlay() }
        }
        seek = SeekBar(this)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) timeText.text = fmt(p)
            }

            override fun onStartTrackingTouch(s: SeekBar) {
                userSeeking = true
            }

            override fun onStopTrackingTouch(s: SeekBar) {
                userSeeking = false
                if (prepared) {
                    try {
                        player?.seekTo(s.progress)
                    } catch (_: IllegalStateException) {
                    }
                }
            }
        })
        timeText = TextView(this).apply {
            text = "0:00"
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(pad, 0, 0, 0)
        }
        row.addView(openBtn)
        row.addView(browserBtn)
        row.addView(settingsBtn)
        row.addView(playBtn)
        row.addView(seek, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(timeText)
        panel.addView(row)

        root.addView(panel, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
    }

    private fun togglePanel() {
        panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun pickVideo() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
        }
        startActivityForResult(i, REQ_VIDEO)
    }

    @Deprecated("Framework Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VIDEO && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            pendingUri = uri
            if (surface != null) startVideo(uri)
        }
    }

    /** Called on the main thread whenever the GL side hands us a (new) video surface. */
    private fun onSurface(s: Surface) {
        surface = s
        val mp = player
        if (mp != null) {
            try {
                mp.setSurface(s)
                if (resumeAfterSurface && prepared) mp.start()
            } catch (_: IllegalStateException) {
            }
            resumeAfterSurface = false
            updatePlayBtn()
        } else {
            pendingUri?.let { startVideo(it) }
        }
    }

    private fun startVideo(uri: Uri) {
        releasePlayer()
        hint.visibility = View.GONE
        val mp = MediaPlayer()
        player = mp
        prepared = false
        try {
            mp.setSurface(surface)
            mp.setDataSource(this, uri)
            mp.setOnVideoSizeChangedListener { _, w, h ->
                if (w > 0 && h > 0) {
                    videoW = w
                    videoH = h
                    renderer.setBufferSize(w, h)
                    fit()
                }
            }
            mp.setOnPreparedListener {
                prepared = true
                seek.max = it.duration
                it.start()
                updatePlayBtn()
            }
            mp.setOnCompletionListener { updatePlayBtn() }
            mp.setOnErrorListener { _, what, extra ->
                Toast.makeText(this, "Cannot play this video ($what/$extra)", Toast.LENGTH_LONG).show()
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot open video: ${e.message}", Toast.LENGTH_LONG).show()
            releasePlayer()
        }
    }

    private fun togglePlay() {
        val mp = player ?: return
        if (!prepared) return
        try {
            if (mp.isPlaying) mp.pause() else mp.start()
        } catch (_: IllegalStateException) {
        }
        updatePlayBtn()
    }

    private fun updatePlayBtn() {
        val playing = try {
            player?.isPlaying == true
        } catch (_: IllegalStateException) {
            false
        }
        playBtn.text = if (playing) "Pause" else "Play"
    }

    private fun releasePlayer() {
        prepared = false
        try {
            player?.release()
        } catch (_: Exception) {
        }
        player = null
        videoW = 0
        videoH = 0
    }

    /** Letterbox the GL view to the video's aspect ratio. */
    private fun fit() {
        val cw = root.width
        val ch = root.height
        val lp = glView.layoutParams as FrameLayout.LayoutParams
        val w: Int
        val h: Int
        if (videoW > 0 && videoH > 0 && cw > 0 && ch > 0) {
            val s = minOf(cw.toFloat() / videoW, ch.toFloat() / videoH)
            w = (videoW * s).toInt()
            h = (videoH * s).toInt()
        } else {
            w = MATCH
            h = MATCH
        }
        if (lp.width != w || lp.height != h) {
            lp.width = w
            lp.height = h
            lp.gravity = Gravity.CENTER
            glView.layoutParams = lp
        }
    }

    @Deprecated("Framework back handling")
    override fun onBackPressed() {
        if (side.isOpen) side.close() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun immersive() {
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onResume() {
        super.onResume()
        immersive()
        glView.onResume()
        main.removeCallbacks(ticker)
        main.post(ticker)
    }

    override fun onPause() {
        main.removeCallbacks(ticker)
        try {
            val mp = player
            if (mp != null && prepared) {
                resumeAfterSurface = mp.isPlaying
                mp.pause()
            }
        } catch (_: IllegalStateException) {
        }
        glView.onPause()
        Prefs.save(this)
        super.onPause()
    }

    override fun onDestroy() {
        thermalListener?.let { getSystemService(PowerManager::class.java).removeThermalStatusListener(it) }
        thermalListener = null
        releasePlayer()
        super.onDestroy()
    }

    private fun fmt(ms: Int): String {
        val s = ms / 1000
        return String.format("%d:%02d", s / 60, s % 60)
    }
}
