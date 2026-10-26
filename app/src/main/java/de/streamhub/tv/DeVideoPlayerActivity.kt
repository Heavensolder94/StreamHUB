package de.streamhub.tv

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import org.json.JSONTokener

/** Public iframe with commands/events via postMessage; no JavascriptInterface or stream extraction. */
class DeVideoPlayerActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var main: LinearLayout
    private lateinit var web: WebView
    private lateinit var status: TextView
    private var fullscreen: View? = null
    private var callback: WebChromeClient.CustomViewCallback? = null
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("streamhub_local", MODE_PRIVATE) }
    private var active = false
    private var current = 0.0
    private var duration = 0.0
    private var url = ""
    private var lastSaved = 0
    private fun dp(n: Int) = (resources.displayMetrics.density * n + .5f).toInt()
    private val poller = object : Runnable {
        override fun run() {
            if (!active || !::web.isInitialized) return
            command("getCurrentTime")
            command("getDuration")
            command("getPaused")
            readState()
            handler.postDelayed(this, 4000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        val input = intent.getStringExtra("url").orEmpty()
        val uri = Uri.parse(input)
        val id = "(tt[0-9]{5,12}|[1-9][0-9]{0,11})"
        val route = Regex("^/(movie|serial)/" + id + "$|^/custom/movie/" + id +
            "$|^/custom/serial/" + id + "/[1-9][0-9]{0,2}/[1-9][0-9]{0,2}$")
        if (uri.scheme != "https" || uri.host != "devideosrc.co" ||
            !route.matches(uri.path.orEmpty())) {
            Toast.makeText(this, "Ungültige DeVideoSRC-Adresse.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        url = input
        current = prefs.getInt(progressKey(), 0).toDouble()
        root = FrameLayout(this).apply { setBackgroundColor(Ui.BG) }
        main = Ui.vertical(this)
        root.addView(main, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        val toolbar = Ui.horizontal(this).apply {
            setBackgroundColor(0xFF111B2B.toInt())
            setPadding(dp(9), dp(4), dp(9), dp(4))
        }
        main.addView(toolbar, Ui.lp(-1, dp(60)))
        fun button(label: String, onClick: () -> Unit) {
            toolbar.addView(Ui.button(this, label, true, onClick),
                Ui.lp(-2, dp(46)).apply { rightMargin = dp(7) })
        }
        button("⌂ Zurück") { finish() }
        button("▶") { command("play") }
        button("Ⅱ") { command("pause") }
        button("−10s") { seek((current - 10).coerceAtLeast(0.0)) }
        button("+30s") { seek(current + 30) }
        button("Springen") { showSeekDialog() }
        button("Fortsetzen") { seek(prefs.getInt(progressKey(), 0).toDouble()) }
        button("Quelle") { command("getSource") }
        val title = Ui.text(this, intent.getStringExtra("name") ?: "DeVideoSRC", 14f, Ui.TEXT, true)
        title.maxLines = 1
        title.ellipsize = TextUtils.TruncateAt.END
        toolbar.addView(title, LinearLayout.LayoutParams(0, dp(45), 1f))
        status = Ui.text(this, "Player lädt …", 12f, Ui.MUTED)
        status.setPadding(dp(12), 0, dp(12), 0)
        main.addView(status, Ui.lp(-1, dp(31)))
        web = try { WebView(this) } catch (_: Throwable) {
            Toast.makeText(this, "Keine Android-WebView verfügbar.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        web.setBackgroundColor(Ui.BG)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView?, req: WebResourceRequest?): Boolean =
                req?.isForMainFrame == true
            override fun onPageStarted(v: WebView?, address: String?, icon: Bitmap?) {
                status.text = "Player lädt …"
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View?, cb: CustomViewCallback?) {
                if (view == null || cb == null) return
                if (fullscreen != null) { cb.onCustomViewHidden(); return }
                fullscreen = view
                callback = cb
                (view.parent as? ViewGroup)?.removeView(view)
                root.addView(view, FrameLayout.LayoutParams(-1, -1))
                main.visibility = View.GONE
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                view.requestFocus()
            }
            override fun onHideCustomView() { exitFullscreen() }
        }
        main.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        val html = """
          <!doctype html><html lang="de"><head>
          <meta name="viewport" content="width=device-width,initial-scale=1"/>
          <style>html,body{margin:0;width:100%;height:100%;background:#080b12}
          iframe{width:100%;height:100%;border:0;display:block}</style></head><body>
          <iframe id="player" src="__PLAYER_URL__"
            allow="autoplay;fullscreen;picture-in-picture" allowfullscreen></iframe>
          <script>
          (function(){
            const frame=document.getElementById('player'), origin='https://devideosrc.co';
            const state={event:'loading',time:0,duration:0,source:'',error:''};
            window.streamHubState=state;
            const number=function(v){return typeof v==='number' && isFinite(v) && v>=0?v:null;};
            window.addEventListener('message',function(e) {
              if(e.origin!==origin || e.source!==frame.contentWindow) return;
              let d=e.data;
              if(typeof d==='string'){try{d=JSON.parse(d);}catch(_){return;}}
              if(!d || typeof d.type!=='string' || !d.type.startsWith('meinecloud:'))return;
              state.event=d.type.slice(11);
              const p=(d.data && typeof d.data==='object')?d.data:d;
              const t=number(p.currentTime)??number(p.time)??number(p.seconds)??number(d.currentTime);
              const duration=number(p.duration)??number(d.duration);
              if(t!==null)state.time=t;
              if(duration!==null)state.duration=duration;
              if(state.event==='error')state.error=String(d.message||p.message||'Wiedergabefehler').slice(0,120);
              if(state.event==='sourceused')state.source=String(d.source||p.source||'').slice(0,100);
              if(state.event==='response'){
                const cmd=String(d.command||d.method||p.command||p.method||'');
                const v=d.result??d.value??p.result??p.value;
                if(cmd==='getCurrentTime'&&number(v)!==null)state.time=v;
                if(cmd==='getDuration'&&number(v)!==null)state.duration=v;
                if(cmd==='getSource'&&v!==undefined)state.source=String(v).slice(0,100);
              }
            });
            window.streamHubCommand=function(name,args){
              if(!['play','pause','seek','getCurrentTime','getDuration','getPaused','getSource'].includes(name))return;
              frame.contentWindow.postMessage({type:'meinecloud:command',command:name,args:args||[]},origin);
            };
          })();
          </script></body></html>
        """.trimIndent().replace("__PLAYER_URL__", TextUtils.htmlEncode(url))
        web.loadDataWithBaseURL("https://appassets.androidplatform.net/", html, "text/html", "UTF-8", null)
        active = true
        handler.postDelayed(poller, 2500L)
    }

    private fun progressKey(): String = "devideosrc_progress_" + url.substringBefore("?").hashCode()
    private fun command(name: String, args: String = "[]") {
        if (::web.isInitialized && active)
            web.evaluateJavascript("window.streamHubCommand&&window.streamHubCommand(" +
                JSONObject.quote(name) + "," + args + ")", null)
    }
    private fun seek(seconds: Double) {
        val n = if(duration>0)seconds.coerceIn(0.0,duration) else seconds.coerceAtLeast(0.0)
        command("seek", "[" + n + "]")
        status.text = "Springe zu " + format(n)
    }
    private fun showSeekDialog() {
        val input = EditText(this).apply {
            hint = "Sekunden, z. B. 300"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        AlertDialog.Builder(this).setTitle("Springen (Sekunden)")
            .setView(input).setNegativeButton("Abbrechen", null)
            .setPositiveButton("Springen") { _, _ ->
                input.text.toString().toDoubleOrNull()?.let { seek(it) }
            }.show()
    }
    private fun format(seconds: Double): String {
        val n=seconds.toLong().coerceAtLeast(0)
        return "%02d:%02d:%02d".format(java.util.Locale.ROOT,n/3600,n/60%60,n%60)
    }
    private fun readState() {
        web.evaluateJavascript("window.streamHubState&&JSON.stringify(window.streamHubState)") { answer ->
            if(!active || isFinishing)return@evaluateJavascript
            val state=runCatching{JSONObject(JSONTokener(answer).nextValue() as String)}.getOrNull()
                ?:return@evaluateJavascript
            val t=state.optDouble("time",0.0)
            if(t.isFinite() && t>=0 && t<864000){
                current=t
                if(t>2 && t.toInt()!=lastSaved){
                    lastSaved=t.toInt()
                    prefs.edit().putInt(progressKey(),lastSaved).apply()
                }
            }
            val d=state.optDouble("duration",0.0)
            if(d.isFinite() && d>=0)duration=d
            val error=state.optString("error")
            status.text=if(error.isNotBlank())"Playerfehler: "+error
                else format(current)+" / "+(if(duration>0)format(duration)else "--:--")+
                    " · "+state.optString("event")+
                    (if(state.optString("source").isBlank())"" else " · "+state.optString("source").take(32))
        }
    }
    private fun exitFullscreen() {
        val view=fullscreen?:return
        root.removeView(view)
        fullscreen=null
        callback=null
        main.visibility=View.VISIBLE
        window.decorView.systemUiVisibility=0
    }
    @Deprecated("Android TV back handling")
    override fun onBackPressed(){
        if(fullscreen!=null){
            callback?.onCustomViewHidden()
            if(fullscreen!=null)exitFullscreen()
        }else super.onBackPressed()
    }
    override fun onPause(){
        active=false
        handler.removeCallbacks(poller)
        super.onPause()
    }
    override fun onResume(){
        super.onResume()
        if(::web.isInitialized && !active){
            active=true
            handler.postDelayed(poller,1000L)
        }
    }
    override fun onDestroy(){
        active=false
        handler.removeCallbacks(poller)
        if(::web.isInitialized){
            web.stopLoading()
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        }
        super.onDestroy()
    }
}
