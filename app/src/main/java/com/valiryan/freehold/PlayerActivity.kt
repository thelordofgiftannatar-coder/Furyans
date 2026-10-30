package com.valiryan.freehold

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

/** Plays files, HLS and DASH (audio too). Remembers the position of every item and resumes it. */
class PlayerActivity : AppCompatActivity() {
    companion object {
        fun start(c: Context, items: List<Media>, index: Int = 0) = c.startActivity(Intent(c, PlayerActivity::class.java)
            .putStringArrayListExtra("urls", ArrayList(items.map { it.url }))
            .putStringArrayListExtra("titles", ArrayList(items.map { it.title }))
            .putExtra("i", index).putExtra("ref", items[index].referer).putExtra("cookie", items[index].cookie))
    }
    private var player: ExoPlayer? = null
    private lateinit var urls: List<String>
    private val h = Handler(Looper.getMainLooper())
    private val saver = object : Runnable { override fun run() { savePos(); h.postDelayed(this, 5000) } }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val v = PlayerView(this); setContentView(v)
        urls = intent.getStringArrayListExtra("urls")!!
        val titles = intent.getStringArrayListExtra("titles")!!
        val start = intent.getIntExtra("i", 0)
        val props = mutableMapOf<String, String>()
        intent.getStringExtra("ref")?.takeIf { it.isNotEmpty() }?.let { props["Referer"] = it }
        intent.getStringExtra("cookie")?.takeIf { it.isNotEmpty() }?.let { props["Cookie"] = it }
        val ds = DefaultDataSource.Factory(this, DefaultHttpDataSource.Factory().setDefaultRequestProperties(props))
        val p = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(ds)).build()
        v.player = p; player = p
        p.setMediaItems(urls.mapIndexed { i, u ->
            MediaItem.Builder().setUri(u).setMediaMetadata(MediaMetadata.Builder().setTitle(titles[i]).build()).build()
        }, start, Store.pos(urls[start]))
        p.addListener(object : Player.Listener {
            override fun onMediaItemTransition(m: MediaItem?, r: Int) {
                val s = Store.pos(urls[p.currentMediaItemIndex]); if (s > 5000) p.seekTo(s)
            }
            override fun onPlaybackStateChanged(st: Int) { if (st == Player.STATE_ENDED) Store.setPos(urls[p.currentMediaItemIndex], 0) }
        })
        p.prepare(); p.playWhenReady = true
    }
    private fun savePos() { player?.let { if (it.playbackState != Player.STATE_ENDED) Store.setPos(urls[it.currentMediaItemIndex], it.currentPosition) } }
    override fun onStart() { super.onStart(); h.post(saver) }
    override fun onStop() { super.onStop(); h.removeCallbacks(saver); savePos(); player?.pause() }
    override fun onDestroy() { super.onDestroy(); player?.release() }
}
