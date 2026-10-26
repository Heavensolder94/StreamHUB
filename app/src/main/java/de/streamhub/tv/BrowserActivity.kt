package de.streamhub.tv

import android.app.Activity
import android.content.ActivityNotFoundException
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
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

/**
 * Branded, in-app Android TV web source viewer. Third-party pages keep their own
 * content, authentication and playback restrictions. Remote focus is enhanced
 * for ordinary page links/buttons, not cross-origin iframe players.
 */
class BrowserActivity : Activity() {
    private val sites by lazy { SiteStore(this) }
    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var title: TextView
    private lateinit var address: TextView
    private lateinit var tvModeButton: TextView
    private lateinit var focusHint: TextView
    private lateinit var favoriteButton: TextView
    private lateinit var toolbar: LinearLayout
    private lateinit var navigation: LinearLayout
    private lateinit var progress: ProgressBar
    private var activeSite: Site? = null
    private var tvMode = true
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private fun dp(n: Int) = (resources.displayMetrics.density * n + .5f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        val initialUrl = intent.getStringExtra("url").orEmpty()
        if (!validAddress(initialUrl)) {
            Toast.makeText(this, "Nur gültige HTTPS-Webseiten werden unterstützt.",
                Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val byId = intent.getStringExtra("site_id").orEmpty()
        activeSite = sites.sites().firstOrNull { it.id == byId && byId.isNotBlank() }
            ?: sites.sites().firstOrNull { it.url == initialUrl }
        root = FrameLayout(this).apply { setBackgroundColor(Ui.BG) }
        val main = Ui.vertical(this)
        root.addView(main, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        toolbar = Ui.horizontal(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(21), dp(7), dp(17), dp(7))
            setBackgroundColor(0xFF101B2C.toInt())
        }
        val branding = Ui.vertical(this).apply { setPadding(0, 0, dp(8), 0) }
        branding.addView(Ui.text(this, "▶  STREAMHUB  /  WEBQUELLE", 12f, Ui.FOCUS, true),
            Ui.lp(-1, dp(26)))
        title = Ui.text(this, "", 23f, Ui.TEXT, true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        branding.addView(title, Ui.lp(-1, dp(40)))
        toolbar.addView(branding, LinearLayout.LayoutParams(0, dp(70), 1f))

        favoriteButton = Ui.button(this, "♡ Merken", true) { toggleFavorite() }
        toolbar.addView(favoriteButton,
            Ui.lp(dp(123), dp(49)).apply { rightMargin = dp(9) })
        toolbar.addView(Ui.button(this, "☷ Quellen", true) { chooseSource() },
            Ui.lp(dp(139), dp(49)).apply { rightMargin = dp(9) })
        toolbar.addView(Ui.button(this, "✕ Schließen", true) { finish() },
            Ui.lp(dp(145), dp(49)))
        main.addView(toolbar, Ui.lp(-1, dp(84)))

        navigation = Ui.horizontal(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(21), dp(5), dp(17), dp(5))
            setBackgroundColor(0xFF0D1523.toInt())
        }
        fun action(name: String, width: Int, block: () -> Unit) {
            navigation.addView(Ui.button(this, name, true, block),
                Ui.lp(dp(width), dp(44)).apply { rightMargin = dp(8) })
        }
        action("‹ Zurück", 109) { if (web.canGoBack()) web.goBack() else finish() }
        action("Vorwärts ›", 116) { if (web.canGoForward()) web.goForward() }
        action("↻ Neu laden", 132) { web.reload() }
        action("⌂ Startseite", 133) { activeSite?.url?.let { web.loadUrl(it) } }
        tvModeButton = Ui.button(this, "TV-Modus: AN", true) { toggleTvMode() }
        navigation.addView(tvModeButton,
            Ui.lp(dp(159), dp(44)).apply { rightMargin = dp(8) })
        action("↗ Extern", 119) {
            openExternal(web.url ?: initialUrl)
        }
        focusHint = Ui.text(this, "↑↓←→ Element wählen  ·  OK öffnen", 12f, Ui.FOCUS)
        navigation.addView(focusHint, LinearLayout.LayoutParams(0, dp(46), 1f))
        main.addView(navigation, Ui.lp(-1, dp(57)))

        sourceBar = Ui.horizontal(this).apply {
            setPadding(dp(22), 0, dp(20), 0)
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF121E30.toInt())
        }
        address = Ui.text(this, "", 12f, Ui.SOFT).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        sourceBar.addView(address, LinearLayout.LayoutParams(0, dp(34), 1f))
        main.addView(sourceBar, Ui.lp(-1, dp(34)))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            isIndeterminate = false
            progressTintList = android.content.res.ColorStateList.valueOf(Ui.FOCUS)
        }
        main.addView(progress, Ui.lp(-1, dp(3)))

        try {
            web = WebView(this)
        } catch (_: Throwable) {
            Toast.makeText(this, "Keine Android-WebView auf diesem Gerät verfügbar.",
                Toast.LENGTH_LONG).show()
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
            builtInZoomControls = false
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
                showAddress(url)
                focusHint.text = "Seite wird geladen …"
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progress.visibility = View.GONE
                showAddress(url)
                if (tvMode) setupTvFocus()
                else focusHint.text = "TV-Modus aus · Website normal bedienen"
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, value: Int) {
                progress.progress = value
                if (value >= 100) progress.visibility = View.GONE
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
                navigation.visibility = View.GONE
                progress.visibility = View.GONE
                sourceBar.visibility = View.GONE
                (view.parent as? ViewGroup)?.removeView(view)
                root.addView(view, FrameLayout.LayoutParams(-1, -1))
                window.decorView.systemUiVisibility =
                    View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                view.requestFocus()
            }

            override fun onHideCustomView() {
                removeFullscreen()
            }
        }
        main.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        updateSourceChrome()
        web.loadUrl(initialUrl)
        web.requestFocus()
    }

    private lateinit var sourceBar: LinearLayout

    private fun validAddress(value: String): Boolean {
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return false
        return uri.scheme == "https" && !uri.host.isNullOrBlank() &&
            uri.encodedAuthority?.contains("@") != true
    }

    private fun showAddress(url: String?) {
        val displayHost = runCatching { Uri.parse(url).host }.getOrNull()
            ?: "Unbekannte Webseite"
        val type = if (activeSite?.sourceKind == SourceKind.OFFIZIELLER_DIENST)
            "OFFIZIELLER DIENST" else "EXTERNE WEBSEITE"
        address.text = "◉  " + displayHost.take(100) + "      ·      " + type +
            "      ·      Webseiteninhalte werden unverändert geladen"
    }

    private fun updateSourceChrome() {
        val site = activeSite
        title.text = site?.name ?: intent.getStringExtra("name").orEmpty()
            .ifBlank { "Webquelle" }
        favoriteButton.text = if (site?.favorite == true) "♥ Gemerkt" else "♡ Merken"
        favoriteButton.isEnabled = site != null
        favoriteButton.alpha = if (site != null) 1f else .5f
        showAddress(if (::web.isInitialized) web.url ?: site?.url else site?.url)
    }

    private fun toggleFavorite() {
        val site = activeSite ?: return
        sites.toggleFavorite(site.id)
        activeSite = sites.sites().firstOrNull { it.id == site.id }
        updateSourceChrome()
    }

    private fun chooseSource() {
        val all = sites.sites().filter { validAddress(it.url) }
            .sortedWith(compareByDescending<Site> { it.favorite }
                .thenBy { it.section.ordinal }.thenBy { it.name })
        if (all.isEmpty()) return
        AlertDialog.Builder(this).setTitle("Quelle wechseln · In StreamHUB bleiben")
            .setItems(all.map {
                (if (it.favorite) "♥ " else "") + it.name +
                    "   ·   " + it.section.label
            }.toTypedArray()) { _, index ->
                val selected = all[index]
                activeSite = selected
                sites.addVisit(selected.name, selected.url)
                updateSourceChrome()
                web.loadUrl(selected.url)
                web.requestFocus()
            }
            .setNegativeButton("Abbrechen", null).show()
    }

    private fun toggleTvMode() {
        tvMode = !tvMode
        tvModeButton.text = if (tvMode) "TV-Modus: AN" else "TV-Modus: AUS"
        if (tvMode) setupTvFocus()
        else {
            web.evaluateJavascript("if(window.__streamhubTV){window.__streamhubTV.clear();}", null)
            focusHint.text = "TV-Modus aus · Normale WebView-Steuerung"
        }
        web.requestFocus()
    }

    private fun setupTvFocus() {
        if (!::web.isInitialized) return
        web.evaluateJavascript(TV_NAVIGATION_SCRIPT) {
            if (!tvMode || isDestroyed || isFinishing) return@evaluateJavascript
            moveRemote("init")
        }
    }

    private fun moveRemote(direction: String) {
        if (!::web.isInitialized) return
        web.evaluateJavascript(
            "window.__streamhubTV ? window.__streamhubTV.move('" + direction + "') : ''"
        ) { raw ->
            if (isDestroyed || isFinishing || !tvMode) return@evaluateJavascript
            val chosen = raw?.trim('"')?.replace("\\n", " ")?.take(65).orEmpty()
            if (chosen == "__TOOLBAR__") {
                tvModeButton.requestFocus()
                focusHint.text = "Native TV-Leiste ausgewählt"
                return@evaluateJavascript
            }
            focusHint.text = if (chosen.isBlank() || chosen == "null")
                "↑↓←→ Webseitenlinks wählen · OK öffnen"
            else "◉ " + chosen
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (::web.isInitialized && tvMode && fullscreenView == null &&
            event.action == KeyEvent.ACTION_DOWN && web.hasFocus()) {
            val direction = when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> "up"
                KeyEvent.KEYCODE_DPAD_DOWN -> "down"
                KeyEvent.KEYCODE_DPAD_LEFT -> "left"
                KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> "activate"
                else -> null
            }
            if (event.keyCode == KeyEvent.KEYCODE_MENU) {
                tvModeButton.requestFocus()
                return true
            }
            if (direction != null) {
                if (direction == "activate") {
                    web.evaluateJavascript(
                        "window.__streamhubTV ? window.__streamhubTV.activate() : ''"
                    ) { value ->
                        if (value == "\"edit\"") {
                            // Let Android's keyboard/IME handle editable webpage controls.
                            tvMode = false
                            tvModeButton.text = "TV-Modus: AUS"
                            focusHint.text = "Textfeld aktiv · TV-Modus danach wieder einschalten"
                        }
                    }
                } else moveRemote(direction)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun removeFullscreen() {
        val view = fullscreenView ?: return
        root.removeView(view)
        fullscreenView = null
        fullscreenCallback = null
        web.visibility = View.VISIBLE
        toolbar.visibility = View.VISIBLE
        navigation.visibility = View.VISIBLE
        sourceBar.visibility = View.VISIBLE
        window.decorView.systemUiVisibility = 0
        if (tvMode) setupTvFocus()
        web.requestFocus()
    }

    private fun openExternal(url: String) {
        if (!url.startsWith("https://") && !url.startsWith("http://")) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Kein externer Browser installiert.",
                Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Android TV back button")
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

    companion object {
        /**
         * Local focus helper only. No collection, extraction, token access, or
         * cross-origin iframe navigation. Native toolbar always remains accessible.
         */
        private val TV_NAVIGATION_SCRIPT = """
            (function() {
              if (window.__streamhubTV) return 'ok';
              var selected = null;
              var previousOutline = '';
              var previousShadow = '';
              function choices() {
                var nodes = document.querySelectorAll(
                  'a[href],button,[role="button"],input,select,textarea,[tabindex]:not([tabindex="-1"])'
                );
                return Array.prototype.slice.call(nodes, 0, 600).filter(function(node) {
                  var rect = node.getBoundingClientRect();
                  var style = window.getComputedStyle(node);
                  return rect.width > 8 && rect.height > 8 &&
                    style.visibility !== 'hidden' && style.display !== 'none' &&
                    rect.bottom > 0 && rect.top < window.innerHeight &&
                    rect.right > 0 && rect.left < window.innerWidth;
                });
              }
              function clear() {
                if (selected) {
                  selected.style.outline = previousOutline;
                  selected.style.boxShadow = previousShadow;
                }
                selected = null;
              }
              function highlight(node) {
                clear();
                if (!node) return '';
                selected = node;
                previousOutline = node.style.outline;
                previousShadow = node.style.boxShadow;
                node.style.outline = '4px solid #58D9FF';
                node.style.boxShadow = '0 0 0 3px #FFFFFF, 0 0 24px #0DA7FF';
                var tag = node.tagName.toLowerCase();
                // Do not pop up Android's software keyboard while just browsing.
                if (node.focus && tag !== 'input' && tag !== 'textarea' &&
                    tag !== 'select' && !node.isContentEditable) {
                  node.focus({preventScroll:true});
                }
                node.scrollIntoView({block:'nearest',inline:'nearest'});
                return (node.innerText || node.getAttribute('aria-label') ||
                  node.getAttribute('title') || node.value || node.tagName || ''
                ).trim().slice(0,65);
              }
              function move(direction) {
                var list = choices();
                if (!list.length) {
                  window.scrollBy(0, direction === 'up' ? -280 : 280);
                  return '';
                }
                if (!selected || list.indexOf(selected) < 0 || direction === 'init')
                  return highlight(list[0]);
                var origin = selected.getBoundingClientRect();
                var ox = (origin.left + origin.right) / 2;
                var oy = (origin.top + origin.bottom) / 2;
                var best = null;
                var score = Infinity;
                list.forEach(function(node) {
                  if (node === selected) return;
                  var r = node.getBoundingClientRect();
                  var dx = (r.left + r.right) / 2 - ox;
                  var dy = (r.top + r.bottom) / 2 - oy;
                  var along = 0, across = 0;
                  if (direction === 'right') { along = dx; across = Math.abs(dy); }
                  else if (direction === 'left') { along = -dx; across = Math.abs(dy); }
                  else if (direction === 'down') { along = dy; across = Math.abs(dx); }
                  else { along = -dy; across = Math.abs(dx); }
                  if (along <= 7) return;
                  var rating = along + 2.1 * across;
                  if (rating < score) { score = rating; best = node; }
                });
                if (best) return highlight(best);
                if (direction === 'down' || direction === 'up') {
                  if (direction === 'up' && window.scrollY <= 1) {
                    clear();
                    return '__TOOLBAR__';
                  }
                  window.scrollBy(0, direction === 'down' ? 310 : -310);
                  clear();
                  return '';
                }
                return highlight(selected);
              }
              function activate() {
                if (!selected) return '';
                var type = selected.tagName.toLowerCase();
                if (type === 'input' || type === 'textarea' || type === 'select' ||
                    selected.isContentEditable) {
                  selected.click();
                  selected.focus();
                  return 'edit';
                }
                selected.click();
                return 'open';
              }
              window.__streamhubTV = {move:move,activate:activate,clear:clear};
              return 'ready';
            })();
        """.trimIndent()
    }
}
