package com.valiryan.freehold

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

/** One screen, three modes: "dl" download manager, "diy" playlists by universe, "bm" bookmarks. */
class LibraryActivity : AppCompatActivity() {
    private lateinit var box: LinearLayout
    private lateinit var mode: String
    private var last = ""
    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { draw(); h.postDelayed(this, 1000) } }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        mode = intent.getStringExtra("mode") ?: "dl"
        title = mapOf("dl" to "Downloads", "diy" to "DIY playlists", "bm" to "Bookmarks")[mode]
        val sv = ScrollView(this).apply { setBackgroundColor(0xFF040714.toInt()) }
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(14), dp(10), dp(30)) }
        sv.addView(box); setContentView(sv)
    }
    override fun onResume() { super.onResume(); last = ""; h.post(tick) }
    override fun onPause() { super.onPause(); h.removeCallbacks(tick) }

    private fun tv(t: String, size: Float, alpha: Float = 1f, bg: Boolean = true) = TextView(this).apply {
        text = t; textSize = size; setTextColor(Color.WHITE); this.alpha = alpha; setPadding(dp(14), dp(12), dp(14), dp(12))
        if (bg) background = GradientDrawable().apply { setColor(0x22FFFFFF); cornerRadius = dp(10).toFloat() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
    }
    private fun stateText(o: JSONObject) = when (o.optString("state")) {
        "running" -> "Downloading ${o.optInt("pct")}%"; "done" -> "Done"; "paused" -> "Paused ${o.optInt("pct")}%"
        "failed" -> "Failed: ${o.optString("err")}"; else -> "Queued"
    }

    private fun draw() {
        val items = Store.list(mode); val sig = items.toString(); if (sig == last) return; last = sig
        box.removeAllViews()
        if (items.isEmpty()) box.addView(tv("Nothing here yet.", 15f, .7f, false))
        var fr = ""
        (if (mode == "diy") items.sortedBy { Universes.list.indexOf(it.optString("fr")) } else items.reversed()).forEach { o ->
            if (mode == "diy" && o.optString("fr") != fr) { fr = o.optString("fr"); box.addView(tv(fr.uppercase(), 13f, .8f, false)) }
            val sub = when (mode) { "dl" -> stateText(o); "diy" -> o.optString("kind"); else -> o.optString("url") }
            box.addView(tv(o.optString("title") + "\n" + sub, 14f).apply { setOnClickListener { click(o) } })
        }
    }

    private fun ask(vararg opts: Pair<String, () -> Unit>) =
        AlertDialog.Builder(this).setItems(opts.map { it.first }.toTypedArray()) { _, i -> opts[i].second() }.show()

    private fun play(list: List<JSONObject>, o: JSONObject, urlKey: String) {
        val ms = list.map { Media(it.optString(urlKey), it.optString("kind"), it.optString("title"), it.optString("referer"), "") }
        PlayerActivity.start(this, ms, list.indexOf(o).coerceAtLeast(0))
    }
    private fun toDiy(o: JSONObject, url: String) = ask(*Universes.list.map { u -> u to {
        Store.upsert("diy", o.optString("id"), JSONObject().put("id", o.optString("id")).put("title", o.optString("title"))
            .put("url", url).put("fr", u).put("kind", o.optString("kind")).put("referer", o.optString("referer")))
        last = ""; Unit } }.toTypedArray())

    private fun click(o: JSONObject) {
        val id = o.optString("id")
        when (mode) {
            "dl" -> when (o.optString("state")) {
                "done" -> ask("Play" to { play(listOf(o), o, "out") }, "Add to DIY playlist" to { toDiy(o, o.optString("out")) }, "Remove from list" to { Store.remove("dl", id); last = "" })
                "running", "queued" -> ask("Pause" to { DownloadWorker.pause(this, id) }, "Cancel and remove" to { DownloadWorker.remove(this, id); last = "" })
                else -> ask("Resume / retry" to { DownloadWorker.start(this, id) }, "Remove" to { DownloadWorker.remove(this, id); last = "" })
            }
            "diy" -> ask("Play (keeps going through this universe)" to {
                val same = Store.list("diy").filter { it.optString("fr") == o.optString("fr") }; play(same, o, "url")
            }, "Remove" to { Store.remove("diy", id); last = "" })
            else -> ask("Open" to { setResult(RESULT_OK, Intent().putExtra("open", o.optString("url"))); finish() }, "Remove" to { Store.remove("bm", id); last = "" })
        }
    }
}
