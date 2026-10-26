package de.streamhub.tv

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

/** One website at a time. Full-screen only works if the underlying web player supports it. */
class BrowserActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var heading: TextView
    private lateinit var progress: ProgressBar
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    private fun dp(n: Int) = (resources.displayMetrics.density * n + 0.5f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        val url = intent.getStringExtra("url") ?: "https://www.crunchyroll.com/de/"
        if (!url.startsWith("https://")) {
            Toast.makeText(this, "Nur HTTPS-Webseiten werden unterstützt.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        root = FrameLayout(this).apply { setBackgroundColor(Ui.BG) }
        val main = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(main, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        val toolbar = Ui.horizontal(this).apply {
            setPadding(dp(14), dp(5), dp(14), dp(5))
            setBackgroundColor(0xFF0E1825.toInt())
        }
        main.addView(toolbar, LinearLayout.LayoutParams(-1, dp(64)))
        fun action(label: String, handler: () -> Unit) {
            val control = Ui.button(this, label, true, handler)
            toolbar.addView(control, LinearLayout.LayoutParams(-2, dp(45)).apply { rightMargin = dp(9) })
        }
        action("⌂ Start") { finish() }
        action("←") { if (web.canGoBack()) web.goBack() else finish() }
        action("→") { if (web.canGoForward()) web.goForward() }
        action("⟳") { web.reload() }
        action("Extern") { openExternal(web.url ?: url) }

        heading = Ui.text(this, intent.getStringExtra("name") ?: "Webseite", 18f, Ui.MUTED, true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        toolbar.addView(heading, LinearLayout.LayoutParams(0, -1, 1f))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            isIndeterminate = false
        }
        main.addView(progress, LinearLayout.LayoutParams(-1, dp(3)))

        try {
            web = WebView(this)
        } catch (error: Throwable) {
            Toast.makeText(this, "Auf diesem Gerät ist keine nutzbare WebView verfügbar.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        web.setBackgroundColor(Ui.BG)
        web.isFocusable = true
        web.isFocusableInTouchMode = true
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            builtInZoomControls = true
            displayZoomControls = false
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val scheme = request?.url?.scheme ?: return true
                return scheme != "http" && scheme != "https"
            }
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                progress.visibility = View.VISIBLE
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                heading.text = view?.title?.takeIf { it.isNotBlank() } ?: (intent.getStringExtra("name") ?: "Webseite")
                progress.visibility = View.GONE
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.progress = newProgress
                if (newProgress >= 100) progress.visibility = View.GONE
            }
            override fun onShowCustomView(view: View?, callback: WebChromeClient.CustomViewCallback?) {
                if (view == null || callback == null) return
                if (fullscreenView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                fullscreenView = view
                fullscreenCallback = callback
                web.visibility = View.GONE
                toolbar.visibility = View.GONE
                progress.visibility = View.GONE
                (view.parent as? ViewGroup)?.removeView(view)
                root.addView(view, FrameLayout.LayoutParams(-1, -1))
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    )
                view.requestFocus()
            }
            override fun onHideCustomView() {
                removeFullscreen()
            }
        }
        main.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        web.loadUrl(url)
        web.requestFocus()
    }

    private fun removeFullscreen() {
        val view = fullscreenView ?: return
        root.removeView(view)
        fullscreenView = null
        fullscreenCallback = null
        web.visibility = View.VISIBLE
        window.decorView.systemUiVisibility = 0
        web.requestFocus()
        // Recreate the normal toolbar layout if it was hidden for video.
        (web.parent as? LinearLayout)?.let { main ->
            if (main.childCount >= 2) {
                main.getChildAt(0).visibility = View.VISIBLE
            }
        }
    }

    private fun openExternal(url: String) {
        if (!url.startsWith("https://") && !url.startsWith("http://")) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Kein externer Browser installiert.", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Legacy remote-control back handling")
    override fun onBackPressed() {
        when {
            fullscreenView != null -> {
                fullscreenCallback?.onCustomViewHidden()
                if (fullscreenView != null) removeFullscreen()
            }
            ::web.isInitialized && web.canGoBack() -> web.goBack()
            else -> super.onBackPressed()
        }
    }

    override fun onDestroy() {
        if (::web.isInitialized) {
            web.stopLoading()
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        }
        super.onDestroy()
    }
}
