package com.example.screenenhancer

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.PowerManager
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.net.URLEncoder
import java.util.Locale

/**
 * Minimal browser. A script (assets/ns_enhancer.js) is injected into every frame at document start;
 * it redraws each <video> through the GPU shader chain (AI upscale or edge-adaptive upscale +
 * sharpening) on a canvas placed over the video. DRM-protected or cross-origin video is left alone.
 */
class BrowserActivity : Activity() {

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    private lateinit var web: WebView
    private lateinit var root: FrameLayout
    private lateinit var content: LinearLayout
    private lateinit var side: SidePanel
    private lateinit var urlBox: EditText
    private lateinit var script: String
    private var docStartScript = false
    private var thermal = 0
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.load(this)
        script = assets.open("ns_enhancer.js").bufferedReader().use { it.readText() }

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)
        root.setOnApplyWindowInsetsListener { v, insets ->
            val i = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime()
            )
            v.setPadding(i.left, i.top, i.right, i.bottom)
            WindowInsets.CONSUMED
        }
        side = SidePanel(this, root, false) { pushParams() }

        // Address bar
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        urlBox = EditText(this).apply {
            setSingleLine()
            hint = "Address or search"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            imeOptions = EditorInfo.IME_ACTION_GO
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_GO ||
                    (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)
                ) {
                    go(text.toString())
                    true
                } else false
            }
        }
        val goBtn = Button(this).apply {
            text = "Go"
            setOnClickListener { go(urlBox.text.toString()) }
        }
        val fxBtn = Button(this).apply {
            text = "Ayarlar"
            setOnClickListener { side.toggle() }
        }
        bar.addView(urlBox, LinearLayout.LayoutParams(0, WRAP, 1f))
        bar.addView(goBtn)
        bar.addView(fxBtn)
        content.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))

        // Web view
        web = WebView(this)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        // Run the enhancer in every frame (including iframes) before the page's own scripts.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, script, setOf("*"))
            docStartScript = true
        }

        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                // Older WebViews cannot inject into iframes; main frame only in that case.
                if (!docStartScript) view?.evaluateJavascript(script, null)
                pushParams()
                if (url != null && !urlBox.hasFocus()) urlBox.setText(url)
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                val t = m.message()
                if (t.startsWith("NS:")) {
                    runOnUiThread { side.setStatus(t.substring(3)) }
                    return true
                }
                return super.onConsoleMessage(m)
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null) return
                customView = view
                customCb = callback
                content.visibility = View.GONE
                root.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
                side.bringToFront()
                window.insetsController?.let {
                    it.hide(WindowInsets.Type.systemBars())
                    it.systemBarsBehavior =
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }

            override fun onHideCustomView() {
                hideCustom()
            }
        }
        content.addView(web, LinearLayout.LayoutParams(MATCH, 0, 1f))

        // Let the page-side governor back off when the phone gets warm.
        val pm = getSystemService(PowerManager::class.java)
        thermal = pm.currentThermalStatus
        val tl = PowerManager.OnThermalStatusChangedListener { status ->
            thermal = status
            pushParams()
        }
        thermalListener = tl
        pm.addThermalStatusListener(mainExecutor, tl)

        web.loadUrl("https://www.google.com")
    }

    private fun hideCustom() {
        customView?.let { root.removeView(it) }
        customView = null
        customCb?.onCustomViewHidden()
        customCb = null
        content.visibility = View.VISIBLE
        window.insetsController?.show(WindowInsets.Type.systemBars())
    }

    private fun go(input: String) {
        val t = input.trim()
        if (t.isEmpty()) return
        val target = when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.contains('.') && !t.contains(' ') -> "https://$t"
            else -> "https://www.google.com/search?q=" + URLEncoder.encode(t, "UTF-8")
        }
        web.loadUrl(target)
        urlBox.clearFocus()
    }

    private fun f(v: Float): String = String.format(Locale.US, "%.3f", v)

    /** Sends the current settings to the page; the script forwards them to all iframes. */
    private fun pushParams() {
        val json = "{sharp:${f(Prefs.sharpness)},dn:${f(Prefs.denoise)},contrast:${f(Prefs.contrast)},sat:${f(Prefs.saturation)}," +
            "ai:${Prefs.aiMode},thermal:$thermal,enable:${Prefs.enabled}}"
        web.evaluateJavascript("window.__nsSet&&window.__nsSet($json)", null)
    }

    @Deprecated("Framework back handling")
    override fun onBackPressed() {
        when {
            side.isOpen -> side.close()
            customView != null -> hideCustom()
            web.canGoBack() -> web.goBack()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    override fun onPause() {
        web.onPause()
        Prefs.save(this)
        super.onPause()
    }

    override fun onDestroy() {
        thermalListener?.let { getSystemService(PowerManager::class.java).removeThermalStatusListener(it) }
        thermalListener = null
        (web.parent as? ViewGroup)?.removeView(web)
        web.destroy()
        super.onDestroy()
    }
}
