package com.example.screenenhancer

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import kotlin.math.abs

/**
 * Slide-in settings drawer (pull the bar at the screen edge). Dark cards, blue accent:
 * master switch, AI mode, and percentage sliders. Shared by the player and the browser.
 */
class SidePanel(
    private val act: Activity,
    host: FrameLayout,
    private val showSplit: Boolean,
    private val onChanged: () -> Unit
) {
    private val d = act.resources.displayMetrics.density
    private val blue = 0xFF3D7BFF.toInt()
    private val sheetBg = 0xE61B2230.toInt()
    private val cardBg = 0xFF232C3B.toInt()
    private val chipOff = 0xFF2E3848.toInt()
    private val sub = 0xFF9AA6B8.toInt()

    private val layer = FrameLayout(act)
    private val scrim = View(act)
    private val sheet = ScrollView(act)
    private val statusView = TextView(act)
    private val chips = ArrayList<TextView>()
    private var sheetW = 0

    var isOpen = false
        private set

    private fun dp(v: Int): Int = (v * d).toInt()

    private fun round(color: Int, r: Int, stroke: Int = 0): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(r).toFloat()
        if (stroke != 0) g.setStroke(dp(1), stroke)
        return g
    }

    init {
        val screenW = act.resources.displayMetrics.widthPixels
        sheetW = minOf(dp(380), (screenW * 0.55f).toInt())

        scrim.setBackgroundColor(0x55000000)
        scrim.visibility = View.GONE
        scrim.setOnClickListener { close() }
        layer.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        sheet.setBackgroundColor(sheetBg)
        sheet.isVerticalScrollBarEnabled = false
        sheet.visibility = View.GONE
        sheet.isClickable = true
        sheet.addView(buildContent(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        layer.addView(
            sheet,
            FrameLayout.LayoutParams(sheetW, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END)
        )

        // The visible pull bar at the right edge (kept in the lower half, away from the camera hole).
        val handle = FrameLayout(act)
        val pill = View(act)
        pill.background = round(0x99FFFFFF.toInt(), 3)
        handle.addView(pill, FrameLayout.LayoutParams(dp(5), dp(64), Gravity.END or Gravity.CENTER_VERTICAL))
        var downX = 0f
        var opened = false
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    opened = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!isOpen && !opened && downX - e.rawX > dp(20)) {
                        opened = true
                        show()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!opened && abs(e.rawX - downX) < dp(10)) toggle()
                }
            }
            true
        }
        val lp = FrameLayout.LayoutParams(dp(40), dp(120), Gravity.END or Gravity.BOTTOM)
        lp.bottomMargin = dp(36)
        layer.addView(handle, lp)

        host.addView(layer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    fun setStatus(t: String) {
        statusView.text = t
    }

    fun bringToFront() {
        layer.bringToFront()
    }

    fun toggle() {
        if (isOpen) close() else show()
    }

    fun show() {
        if (isOpen) return
        isOpen = true
        scrim.visibility = View.VISIBLE
        sheet.visibility = View.VISIBLE
        sheet.translationX = sheetW.toFloat()
        sheet.animate().translationX(0f).setDuration(180).start()
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        scrim.visibility = View.GONE
        sheet.animate().translationX(sheetW.toFloat()).setDuration(160)
            .withEndAction { if (!isOpen) sheet.visibility = View.GONE }
            .start()
        Prefs.save(act)
    }

    // ------------------------------------------------------------------ content

    private fun text(s: String, sp: Float, color: Int): TextView {
        val t = TextView(act)
        t.text = s
        t.textSize = sp
        t.setTextColor(color)
        return t
    }

    private fun buildContent(): LinearLayout {
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(16), dp(16), dp(16))

        col.addView(text("Video Renk ve Görüntü Ayarları", 18f, Color.WHITE))
        val line = View(act)
        line.setBackgroundColor(0xFF2F394A.toInt())
        val llp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        llp.topMargin = dp(10)
        llp.bottomMargin = dp(14)
        col.addView(line, llp)

        col.addView(masterCard(), cardParams())
        col.addView(aiCard(), cardParams())
        col.addView(sliderCard(), cardParams())
        return col
    }

    private fun cardParams(): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        p.bottomMargin = dp(12)
        return p
    }

    private fun masterCard(): LinearLayout {
        val card = LinearLayout(act)
        card.orientation = LinearLayout.HORIZONTAL
        card.gravity = Gravity.CENTER_VERTICAL
        card.setPadding(dp(14), dp(14), dp(14), dp(14))
        card.background = round(cardBg, 14, blue)

        val badge = text("2K", 20f, Color.WHITE)
        badge.gravity = Gravity.CENTER
        badge.background = round(blue, 10)
        card.addView(badge, LinearLayout.LayoutParams(dp(52), dp(52)))

        val mid = LinearLayout(act)
        mid.orientation = LinearLayout.VERTICAL
        mid.addView(text("2K Keskinleştirme", 16f, Color.WHITE))
        mid.addView(text("Görüntü netliğini ve detayları artırır", 12f, sub))
        val mlp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        mlp.leftMargin = dp(12)
        mlp.rightMargin = dp(8)
        card.addView(mid, mlp)

        val sw = Switch(act)
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        sw.trackTintList = ColorStateList(states, intArrayOf(blue, 0xFF4A5568.toInt()))
        sw.thumbTintList = ColorStateList(states, intArrayOf(Color.WHITE, 0xFFCBD5E0.toInt()))
        sw.isChecked = Prefs.enabled
        sw.setOnCheckedChangeListener { _, c ->
            Prefs.enabled = c
            onChanged()
        }
        card.addView(sw)
        return card
    }

    private fun aiCard(): LinearLayout {
        val card = LinearLayout(act)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(14), dp(12), dp(14), dp(12))
        card.background = round(cardBg, 14)
        card.addView(text("Yapay Zeka Modu", 15f, Color.WHITE))

        val row = LinearLayout(act)
        row.orientation = LinearLayout.HORIZONTAL
        val names = arrayOf("Otomatik", "Kapalı", "Küçük", "Tam")
        for (i in names.indices) {
            val c = text(names[i], 12f, Color.WHITE)
            c.gravity = Gravity.CENTER
            c.setPadding(0, dp(9), 0, dp(9))
            c.setOnClickListener {
                Prefs.aiMode = i
                Prefs.save(act)
                refreshChips()
                onChanged()
            }
            chips.add(c)
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.rightMargin = if (i < names.size - 1) dp(6) else 0
            row.addView(c, lp)
        }
        val rlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        rlp.topMargin = dp(10)
        card.addView(row, rlp)
        refreshChips()

        statusView.textSize = 11f
        statusView.setTextColor(sub)
        val ver = try { act.packageManager.getPackageInfo(act.packageName, 0).versionName } catch (e: Exception) { "?" }
        statusView.text = "Sürüm $ver · video bekleniyor"
        val slp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        slp.topMargin = dp(8)
        card.addView(statusView, slp)
        return card
    }

    private fun refreshChips() {
        for (i in chips.indices) {
            chips[i].background = round(if (i == Prefs.aiMode) blue else chipOff, 8)
        }
    }

    private fun sliderCard(): LinearLayout {
        val card = LinearLayout(act)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(14), dp(12), dp(14), dp(8))
        card.background = round(cardBg, 14)
        card.addView(text("Ek Ayarlar", 15f, Color.WHITE))
        addSlider(card, "Keskinlik / Detay", 0f, 1f, Prefs.sharpness) { Prefs.sharpness = it }
        addSlider(card, "Gürültü / Blok Temizleme", 0f, 1f, Prefs.denoise) { Prefs.denoise = it }
        addSlider(card, "Renk Doygunluğu", 0.5f, 1.8f, Prefs.saturation) { Prefs.saturation = it }
        addSlider(card, "Kontrast / Canlılık", 0.8f, 1.4f, Prefs.contrast) { Prefs.contrast = it }
        if (showSplit) addSlider(card, "Önce / Sonra", 0f, 1f, Prefs.wipe) { Prefs.wipe = it }
        return card
    }

    private fun addSlider(
        parent: LinearLayout,
        label: String,
        min: Float,
        max: Float,
        value: Float,
        set: (Float) -> Unit
    ) {
        val head = LinearLayout(act)
        head.orientation = LinearLayout.HORIZONTAL
        val name = text(label, 13f, 0xFFD5DCE8.toInt())
        val pct = text("", 13f, blue)
        head.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(pct)
        val hlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        hlp.topMargin = dp(10)
        parent.addView(head, hlp)

        val sb = SeekBar(act)
        sb.max = 100
        val start = (((value - min) / (max - min)) * 100f).toInt().coerceIn(0, 100)
        sb.progress = start
        sb.progressTintList = ColorStateList.valueOf(blue)
        sb.thumbTintList = ColorStateList.valueOf(Color.WHITE)
        sb.progressBackgroundTintList = ColorStateList.valueOf(0xFF3A4457.toInt())
        pct.text = "%$start"
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                pct.text = "%$p"
                set(min + (max - min) * p / 100f)
                onChanged()
            }

            override fun onStartTrackingTouch(s: SeekBar) {}

            override fun onStopTrackingTouch(s: SeekBar) {
                Prefs.save(act)
            }
        })
        parent.addView(sb)
    }
}
