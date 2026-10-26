package de.streamhub.tv

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import android.net.Uri

/** Native Android TV player. No Kodi or xVAULT runtime is used here. */
@UnstableApi
class LiveTvPlayerActivity : Activity() {
    private var player: ExoPlayer? = null
    private lateinit var view: PlayerView
    private lateinit var nowPlaying: TextView
    private lateinit var status: TextView
    private val catalog by lazy { LiveTvCatalog(this) }
    private var currentChannelId = ""
    private var directUrl = ""
    private var directTitle = ""
    private var directIsLive = false
    private var allowDirectHttp = false
    private var channels: List<LiveChannel> = emptyList()
    private fun dp(n: Int) = (resources.displayMetrics.density * n + 0.5f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        currentChannelId = intent.getStringExtra("channel_id").orEmpty()
        directUrl = intent.getStringExtra("direct_stream_url").orEmpty()
        directTitle = intent.getStringExtra("direct_stream_title").orEmpty()
        directIsLive = intent.getBooleanExtra("direct_stream_is_live", false)
        allowDirectHttp = intent.getBooleanExtra("direct_allow_http", false)
        val root = Ui.vertical(this).apply { setBackgroundColor(Color.BLACK) }
        val header = Ui.horizontal(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(8), dp(16), dp(7))
            setBackgroundColor(0xFF10192A.toInt())
        }
        nowPlaying = Ui.text(this, "📺  LIVE TV", 22f, Ui.TEXT, true)
        header.addView(nowPlaying, LinearLayout.LayoutParams(0, dp(52), 1f))
        header.addView(Ui.button(this, "‹  Sender", true) { switchChannel(-1) },
            Ui.lp(dp(135), dp(49)).apply { rightMargin = dp(8) })
        header.addView(Ui.button(this, "Sender  ›", true) { switchChannel(1) },
            Ui.lp(dp(135), dp(49)).apply { rightMargin = dp(8) })
        header.addView(Ui.button(this, "✕ Zurück", true) { finish() },
            Ui.lp(dp(135), dp(49)))
        root.addView(header, Ui.lp(-1, dp(70)))

        val container = FrameLayout(this)
        view = PlayerView(this).apply {
            setBackgroundColor(Color.BLACK)
            useController = true
            controllerAutoShow = true
            controllerShowTimeoutMs = 6500
        }
        container.addView(view, FrameLayout.LayoutParams(-1, -1))
        root.addView(container, LinearLayout.LayoutParams(-1, 0, 1f))
        status = Ui.text(this, "LIVE · Lade Sender …", 13f, Ui.SOFT).apply {
            setPadding(dp(20), 0, dp(20), 0)
            setBackgroundColor(0xFF10192A.toInt())
        }
        root.addView(status, Ui.lp(-1, dp(41)))
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        channels = catalog.channels()
        if (directUrl.isBlank() && channels.isEmpty()) {
            status.text = "Keine Sender eingerichtet."
            return
        }
        val instance = ExoPlayer.Builder(this).build()
        player = instance
        view.player = instance
        instance.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                status.text = "Stream nicht abspielbar: " + error.errorCodeName +
                    ". Prüfe die Stream-Adresse oder Rechte."
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY)
                    status.text = if (directUrl.isNotBlank()) {
                        if (directIsLive) "● LIVE · MAC IPTV" else "▶ Video on Demand · MAC IPTV"
                    } else "● LIVE · " + (channels.find { it.id == currentChannelId }?.group ?: "TV")
                else if (playbackState == Player.STATE_BUFFERING)
                    status.text = "Verbinde mit Livestream …"
            }
        })
        if (directUrl.isNotBlank()) {
            val acceptable = directUrl.startsWith("https://") ||
                (allowDirectHttp && directUrl.startsWith("http://"))
            if (!acceptable || Uri.parse(directUrl).host.isNullOrBlank()) {
                status.text = "Ungültiger oder nicht freigegebener Stream-Link."
                return
            }
            nowPlaying.text = "▶  " + directTitle.ifBlank { "IPTV Stream" }
            playUri(directUrl)
        } else {
            val stream = channels.find { it.id == currentChannelId } ?: channels.first()
            play(stream)
        }
    }

    private fun play(channel: LiveChannel) {
        if (!LiveTvCatalog.validHttpsUrl(channel.url)) {
            status.text = "Ungültige HTTPS-Stream-Adresse."
            return
        }
        currentChannelId = channel.id
        nowPlaying.text = "●  " + channel.name
        status.text = "Sender wird geladen …"
        playUri(channel.url)
    }

    private fun playUri(url: String) {
        val mediaUri = Uri.parse(url)
        val path = mediaUri.path.orEmpty().lowercase(java.util.Locale.ROOT)
        val item = MediaItem.Builder().setUri(mediaUri).apply {
            if (path.endsWith(".m3u8")) setMimeType(MimeTypes.APPLICATION_M3U8)
            if (path.endsWith(".mpd")) setMimeType(MimeTypes.APPLICATION_MPD)
        }.build()
        player?.apply {
            setMediaItem(item)
            prepare()
            playWhenReady = true
        }
    }

    private fun switchChannel(change: Int) {
        if (directUrl.isNotBlank() || channels.isEmpty()) return
        val idx = channels.indexOfFirst { it.id == currentChannelId }.coerceAtLeast(0)
        val next = (idx + change + channels.size) % channels.size
        play(channels[next])
    }

    override fun onStop() {
        view.player = null
        player?.release()
        player = null
        super.onStop()
    }
}
