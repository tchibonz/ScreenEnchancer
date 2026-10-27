package com.example.screenenhancer

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Draws decoded video through the GPU.
 *
 * Two paths:
 *  - AI path: FSRCNN (luma only, 2x) runs as a chain of fragment-shader passes
 *    (weights: Saafke/FSRCNN_Tensorflow, Apache-2.0), then the result is merged with
 *    the original chroma.
 *  - Classic path: adaptive sharpening + contrast + saturation.
 *
 * In auto mode the model (full / small / none) follows the video resolution, the measured GPU
 * time per frame and the phone's thermal status, so the GPU stays well below full load.
 */
class VideoRenderer(
    private val ctx: Context,
    private val view: GLSurfaceView,
    private val onSource: (Surface) -> Unit,
    private val onStatus: (String) -> Unit
) : GLSurfaceView.Renderer {

    private class Model(val label: String, val nt: Int, val convFiles: List<String>, val prefix: String)
    private class Progs(val a: Int, val convs: List<Int>, val b: Int, val s: Int)

    // index 0 = no AI, 1 = small, 2 = full
    private val models: Array<Model?> = arrayOf(
        null,
        Model("small", 2, listOf("small_c3"), "small"),
        Model("full", 3, listOf("full_c3", "full_c4", "full_c5", "full_c6"), "full")
    )

    @Volatile var thermalStatus = 0
    @Volatile private var bufW = 1280
    @Volatile private var bufH = 720
    @Volatile private var st: SurfaceTexture? = null
    @Volatile private var source: Surface? = null
    @Volatile private var hasFrame = false
    @Volatile private var frameIntervalMs = 41.7

    private var lastFrameNs = 0L
    private var viewW = 1
    private var viewH = 1
    private val stMatrix = FloatArray(16)
    private val ident = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }

    private var aiSupported = false
    private var texVideo = 0
    private var vbo = 0
    private var fbo = 0
    private var pY = 0
    private var pFinalAi = 0
    private var pEasu = 0
    private var pRcas = 0
    private var texUp = 0
    // optional noise / compression-artifact cleaning stage; later passes then read a plain 2D texture
    private var pDn = 0
    private var pY2 = 0
    private var pFinalAi2 = 0
    private var pEasu2 = 0
    private var pRcas2 = 0
    private var texDn = 0
    private var dnKey = ""
    private var dnBroken = false
    private var useDn = false
    private var upKey = ""
    private val progs = arrayOfNulls<Progs>(3)
    private val broken = BooleanArray(3)

    // render targets
    private var allocKey = ""
    private var texY = 0
    private val feat = IntArray(6)
    private var texB = 0
    private var tex2x = 0

    // governor
    private var cap = 2
    private var cooldownUntil = 0L
    private var cooldownMs = 60_000L
    private var activeModel = 0
    private var frames = 0L
    private var nextMeasure = 0L
    private var lastGpuMs = 0.0
    private var lastStatusNs = 0L

    fun setBufferSize(w: Int, h: Int) {
        bufW = w
        bufH = h
        st?.setDefaultBufferSize(w, h)
    }

    // ---------------------------------------------------------------- GL lifecycle

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        source?.release()
        st?.release()
        hasFrame = false

        // Everything GL was lost with the old context.
        progs.fill(null)
        allocKey = ""
        upKey = ""
        texUp = 0
        pDn = 0; pY2 = 0; pFinalAi2 = 0; pEasu2 = 0; pRcas2 = 0
        texDn = 0; dnKey = ""; dnBroken = false; useDn = false
        texY = 0; texB = 0; tex2x = 0
        feat.fill(0)
        activeModel = 0

        val ver = GLES20.glGetString(GLES20.GL_VERSION) ?: ""
        val exts = GLES20.glGetString(GLES20.GL_EXTENSIONS) ?: ""
        aiSupported = exts.contains("GL_EXT_color_buffer_float") || ver.contains("OpenGL ES 3.2")
        locCache.clear()

        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        texVideo = t[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texVideo)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        // full-screen quad: x, y, u, v
        val quad = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        quad.put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f))
        quad.position(0)
        val b = IntArray(1)
        GLES20.glGenBuffers(1, b, 0)
        vbo = b[0]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, 64, quad, GLES20.GL_STATIC_DRAW)

        val f = IntArray(1)
        GLES20.glGenFramebuffers(1, f, 0)
        fbo = f[0]

        pY = 0
        pFinalAi = 0
        pEasu = 0
        pRcas = 0
        try {
            pEasu = program("final_easu.frag")
            pRcas = program("final_rcas.frag")
            if (aiSupported) {
                pY = program("pass_y.frag")
                pFinalAi = program("final_ai.frag")
            }
        } catch (e: RuntimeException) {
            Log.e("VideoRenderer", "shader setup failed: ${e.message}")
            aiSupported = false
            onStatus("Shader error: " + (e.message ?: "").take(160))
        }

        val s = SurfaceTexture(texVideo)
        s.setDefaultBufferSize(bufW, bufH)
        s.setOnFrameAvailableListener {
            val now = System.nanoTime()
            if (lastFrameNs != 0L) {
                val ms = (now - lastFrameNs) / 1e6
                if (ms in 8.0..100.0) frameIntervalMs = frameIntervalMs * 0.9 + ms * 0.1
            }
            lastFrameNs = now
            hasFrame = true
            view.requestRender()
        }
        st = s
        val surf = Surface(s)
        source = surf
        onSource(surf)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = width
        viewH = height
    }

    fun release() {
        source?.release()
        source = null
        st?.release()
        st = null
    }

    // ---------------------------------------------------------------- drawing

    override fun onDrawFrame(gl: GL10?) {
        val s = st ?: return
        if (!hasFrame) return
        try {
            s.updateTexImage()
        } catch (e: RuntimeException) {
            return
        }
        s.getTransformMatrix(stMatrix)
        frames++
        useDn = Prefs.enabled && Prefs.denoise > 0.02f && !dnBroken && runDenoise()

        val m = desiredModel()
        if (m != activeModel) {
            activeModel = m
            nextMeasure = frames + 15
        }
        var drawn = false
        if (m > 0) {
            try {
                drawAi(m)
                drawn = true
            } catch (e: RuntimeException) {
                Log.e("VideoRenderer", "AI path failed for model $m: ${e.message}")
                broken[m] = true
                activeModel = 0
            }
        }
        if (!drawn) drawClassic()
        reportStatus()
    }

    private fun desiredModel(): Int {
        if (!aiSupported || !Prefs.enabled) return 0
        val px = bufW.toLong() * bufH
        val user = Prefs.aiMode
        var m = when (user) {
            1 -> 0
            2 -> 1
            3 -> 2
            else -> if (px > 1_000_000L) 0 else if (px > 520_000L) 1 else 2
        }
        val now = System.currentTimeMillis()
        if (user == 0) {
            // try to climb back up after a cool-down, but only while the phone is cool
            if (cap < 2 && thermalStatus <= 1 && now > cooldownUntil) {
                cap++
                nextMeasure = frames + 15
            }
            m = minOf(m, cap)
        }
        if (thermalStatus >= 3) m = 0
        else if (thermalStatus >= 2 && user == 0) m = minOf(m, 1)
        while (m > 0 && broken[m]) m--
        return m
    }

    private fun drawAi(m: Int) {
        val md = models[m]!!
        val pr = progs[m] ?: loadProgs(md).also { progs[m] = it }
        ensureTargets(m)

        val measure = frames >= nextMeasure && Prefs.aiMode == 0
        var t0 = 0L
        if (measure) {
            GLES20.glFinish()
            t0 = System.nanoTime()
        }

        val w = bufW
        val h = bufH
        val nt = md.nt
        pass(if (useDn) pY2 else pY, intArrayOf(texY), w, h, true, IntArray(0))
        pass(pr.a, IntArray(nt) { feat[it] }, w, h, false, intArrayOf(texY))
        var src = 0
        var dst = 3
        for (c in pr.convs) {
            pass(c, IntArray(nt) { feat[dst + it] }, w, h, false, IntArray(nt) { feat[src + it] })
            val tmp = src; src = dst; dst = tmp
        }
        pass(pr.b, intArrayOf(texB), w, h, false, IntArray(nt) { feat[src + it] })
        pass(pr.s, intArrayOf(tex2x), w * 2, h * 2, false, intArrayOf(texB))

        // composite to the screen
        val fin = if (useDn) pFinalAi2 else pFinalAi
        GLES20.glUseProgram(fin)
        GLES20.glUniform2f(loc(fin, "uPx"), 1f / viewW, 1f / viewH)
        setColorUniforms(fin)
        pass(fin, IntArray(0), viewW, viewH, true, intArrayOf(tex2x))

        if (measure) {
            GLES20.glFinish()
            val ms = (System.nanoTime() - t0) / 1e6
            lastGpuMs = ms
            nextMeasure = frames + 120
            val budget = frameIntervalMs * 0.35
            if (ms > budget && cap >= m && m > 0) {
                cap = m - 1
                cooldownMs = minOf(cooldownMs * 2, 600_000L)
                cooldownUntil = System.currentTimeMillis() + cooldownMs
                Log.i("VideoRenderer", "GPU ${"%.1f".format(ms)} ms > budget ${"%.1f".format(budget)} ms: stepping down")
            }
        }
    }

    /** Classic path: edge-adaptive upscale into a screen-sized texture, then contrast-limited sharpening. */
    private fun drawClassic() {
        if (pEasu == 0 || pRcas == 0) return
        val easu = if (useDn) pEasu2 else pEasu
        val rcas = if (useDn) pRcas2 else pRcas
        val key = "${viewW}x$viewH"
        if (key != upKey) {
            if (texUp != 0) GLES20.glDeleteTextures(1, intArrayOf(texUp), 0)
            texUp = newTex(viewW, viewH, GLES30.GL_RGBA8, GLES20.GL_NEAREST)
            upKey = key
        }
        val upscale = viewW > bufW * 1.05f || viewH > bufH * 1.05f
        GLES20.glUseProgram(easu)
        GLES20.glUniform2f(loc(easu, "uSize"), bufW.toFloat(), bufH.toFloat())
        GLES20.glUniform1f(loc(easu, "uUp"), if (upscale) 1f else 0f)
        pass(easu, intArrayOf(texUp), viewW, viewH, true, IntArray(0))

        GLES20.glUseProgram(rcas)
        setColorUniforms(rcas)
        pass(rcas, IntArray(0), viewW, viewH, true, intArrayOf(texUp))
    }

    private fun setColorUniforms(p: Int) {
        val on = Prefs.enabled
        GLES20.glUniform1f(loc(p, "uSharp"), if (on) Prefs.sharpness else 0f)
        GLES20.glUniform1f(loc(p, "uContrast"), if (on) Prefs.contrast else 1f)
        GLES20.glUniform1f(loc(p, "uSat"), if (on) Prefs.saturation else 1f)
        GLES20.glUniform1f(loc(p, "uWipe"), if (on) Prefs.wipe else 0f)
    }

    /**
     * One full-screen pass. [targets] empty = the screen. [video] binds the camera/decoder texture
     * on unit 0 (and the 2D [inputs] after it); otherwise [inputs] start at unit 0.
     */
    private fun pass(prog: Int, targets: IntArray, w: Int, h: Int, video: Boolean, inputs: IntArray) {
        if (targets.isEmpty()) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        } else {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
            val bufs = IntArray(targets.size)
            for (i in 0 until 4) {
                val t = if (i < targets.size) targets[i] else 0
                GLES20.glFramebufferTexture2D(
                    GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0 + i, GLES20.GL_TEXTURE_2D, t, 0
                )
                if (i < targets.size) bufs[i] = GLES20.GL_COLOR_ATTACHMENT0 + i
            }
            GLES30.glDrawBuffers(targets.size, bufs, 0)
        }
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(prog)
        GLES20.glUniformMatrix4fv(loc(prog, "uST"), 1, false, if (video && !useDn) stMatrix else ident, 0)

        var unit = 0
        if (video) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            if (useDn) GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texDn)
            else GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texVideo)
            GLES20.glUniform1i(loc(prog, "uVideo"), 0)
            unit = 1
        }
        for ((i, tex) in inputs.withIndex()) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
            for (name in arrayOf("uF$i", if (i == 0) "uY" else "", if (i == 0) "uNet" else "", if (i == 0) "uUp" else "")) {
                if (name.isEmpty()) continue
                val l = GLES20.glGetUniformLocation(prog, name)
                if (l >= 0) GLES20.glUniform1i(l, unit)
            }
            unit++
        }

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 16, 0)
        GLES20.glEnableVertexAttribArray(1)
        GLES20.glVertexAttribPointer(1, 2, GLES20.GL_FLOAT, false, 16, 8)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    /**
     * Cleans noise / compression artifacts from the decoded frame into [texDn]. Returns false (and
     * disables the stage for this surface) if the extra shaders cannot be built.
     */
    private fun runDenoise(): Boolean {
        try {
            if (pDn == 0) {
                pDn = program("dn.frag")
                pEasu2 = program("final_easu.frag", false)
                pRcas2 = program("final_rcas.frag", false)
                if (aiSupported) {
                    pY2 = program("pass_y.frag", false)
                    pFinalAi2 = program("final_ai.frag", false)
                }
            }
            val key = "${bufW}x$bufH"
            if (key != dnKey) {
                if (texDn != 0) GLES20.glDeleteTextures(1, intArrayOf(texDn), 0)
                texDn = newTex(bufW, bufH, GLES30.GL_RGBA8, GLES20.GL_LINEAR)
                dnKey = key
            }
        } catch (e: RuntimeException) {
            Log.e("VideoRenderer", "denoise setup failed: ${e.message}")
            dnBroken = true
            return false
        }
        useDn = false   // this pass reads the decoder texture, not the cleaned one
        GLES20.glUseProgram(pDn)
        GLES20.glUniform2f(loc(pDn, "uSize"), bufW.toFloat(), bufH.toFloat())
        GLES20.glUniform1f(loc(pDn, "uDn"), Prefs.denoise)
        pass(pDn, intArrayOf(texDn), bufW, bufH, true, IntArray(0))
        return true
    }

    // ---------------------------------------------------------------- resources

    private fun ensureTargets(m: Int) {
        val key = "${bufW}x${bufH}x$m"
        if (key == allocKey) return
        freeTargets()
        val nt = models[m]!!.nt
        texY = newTex(bufW, bufH, GLES30.GL_R16F, GLES20.GL_NEAREST)
        for (i in 0 until nt) {
            feat[i] = newTex(bufW, bufH, GLES30.GL_RGBA16F, GLES20.GL_NEAREST)
            feat[3 + i] = newTex(bufW, bufH, GLES30.GL_RGBA16F, GLES20.GL_NEAREST)
        }
        texB = newTex(bufW, bufH, GLES30.GL_RGBA16F, GLES20.GL_NEAREST)
        tex2x = newTex(bufW * 2, bufH * 2, GLES30.GL_R16F, GLES20.GL_LINEAR)
        allocKey = key
    }

    private fun freeTargets() {
        val ids = ArrayList<Int>()
        ids.add(texY); ids.add(texB); ids.add(tex2x)
        for (t in feat) ids.add(t)
        val arr = ids.filter { it != 0 }.toIntArray()
        if (arr.isNotEmpty()) GLES20.glDeleteTextures(arr.size, arr, 0)
        texY = 0; texB = 0; tex2x = 0
        feat.fill(0)
    }

    private fun newTex(w: Int, h: Int, internal: Int, filter: Int): Int {
        val id = IntArray(1)
        GLES20.glGenTextures(1, id, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id[0])
        GLES30.glTexStorage2D(GLES20.GL_TEXTURE_2D, 1, internal, w, h)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return id[0]
    }

    private fun loadProgs(md: Model): Progs {
        val a = program("${md.prefix}_a.frag")
        val convs = md.convFiles.map { program("$it.frag") }
        val b = program("${md.prefix}_b.frag")
        val s = program("${md.prefix}_s.frag")
        return Progs(a, convs, b, s)
    }

    private val locCache = HashMap<Long, Int>()

    private fun loc(prog: Int, name: String): Int {
        val key = prog.toLong() shl 32 or (name.hashCode().toLong() and 0xffffffffL)
        return locCache.getOrPut(key) { GLES20.glGetUniformLocation(prog, name) }
    }

    private fun asset(name: String): String =
        ctx.assets.open("shaders/$name").bufferedReader().use { it.readText() }

    private fun fragSource(name: String, oes: Boolean): String {
        var s = asset(name)
        if (s.contains("//__VIDEO__")) {
            if (oes) {
                s = s.replaceFirst("#version 300 es\n", "#version 300 es\n#extension GL_OES_EGL_image_external_essl3 : require\n")
                s = s.replace("//__VIDEO__", "uniform samplerExternalOES uVideo;")
            } else {
                s = s.replace("//__VIDEO__", "uniform sampler2D uVideo;")
            }
        }
        return s
    }

    private fun program(fragName: String, oes: Boolean = true): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, asset("vert.vert"), "vert.vert")
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fragSource(fragName, oes), fragName)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glBindAttribLocation(p, 0, "aPos")
        GLES20.glBindAttribLocation(p, 1, "aUV")
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(p)
            GLES20.glDeleteProgram(p)
            throw RuntimeException("link $fragName: $log")
        }
        GLES20.glDeleteShader(v)
        GLES20.glDeleteShader(f)
        return p
    }

    private fun compile(type: Int, src: String, what: String): Int {
        val sh = GLES20.glCreateShader(type)
        GLES20.glShaderSource(sh, src)
        GLES20.glCompileShader(sh)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(sh)
            GLES20.glDeleteShader(sh)
            throw RuntimeException("compile $what: $log")
        }
        return sh
    }

    // ---------------------------------------------------------------- status

    private fun reportStatus() {
        val now = System.nanoTime()
        if (now - lastStatusNs < 1_000_000_000L) return
        lastStatusNs = now
        val txt = when {
            !aiSupported -> "AI unavailable on this GPU, using classic filter"
            activeModel == 0 -> "AI off (classic filter) · thermal $thermalStatus"
            else -> "AI ${models[activeModel]!!.label} · ${bufW}x$bufH→${bufW * 2}x${bufH * 2}" +
                (if (lastGpuMs > 0) " · GPU ${"%.1f".format(lastGpuMs)} ms" else "") +
                " · thermal $thermalStatus"
        }
        onStatus(txt)
    }
}
