package com.valiryan.freehold

import android.Manifest
import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.Collections

class Tab(val web: WebView, val priv: Boolean) {
    val media: MutableMap<String, Media> = Collections.synchronizedMap(LinkedHashMap())
    @Volatile var page = ""
    @Volatile var prot = false
    var title = "New tab"
    fun add(m: Media) { synchronized(media) { if (!media.containsKey(m.url)) media[m.url] = m } }
}

class MainActivity : AppCompatActivity() {
    private val tabs = mutableListOf<Tab>()
    private var cur: Tab? = null
    private lateinit var root: LinearLayout
    private lateinit var holder: FrameLayout
    private lateinit var urlBar: EditText
    private lateinit var badge: Button
    private lateinit var prog: ProgressBar
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private val libLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringExtra("open")?.let { load(it) }
    }
    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { u -> u?.let { importBookmarks(it) } }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Store.init(this)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF040714.toInt()) }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(30), dp(6), dp(4)) }
        urlBar = EditText(this).apply {
            hint = "Search or enter address"; setSingleLine(); setTextColor(Color.WHITE); setHintTextColor(0x88FFFFFF.toInt())
            imeOptions = EditorInfo.IME_ACTION_GO; setOnEditorActionListener { _, _, _ -> load(text.toString()); true }
        }
        top.addView(urlBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        badge = Button(this).apply { text = "⬇0"; setOnClickListener { showMedia() } }
        top.addView(badge)
        top.addView(Button(this).apply { text = "▢"; setOnClickListener { showTabs() } })
        top.addView(Button(this).apply { text = "⋮"; setOnClickListener { showMenu(it) } })
        prog = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.GONE }
        holder = FrameLayout(this)
        root.addView(top); root.addView(prog, LinearLayout.LayoutParams(-1, dp(3))); root.addView(holder, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        newTab(false, intent?.data?.toString() ?: "https://duckduckgo.com")
    }

    // ---------- tabs ----------
    @SuppressLint("SetJavaScriptEnabled")
    private fun newTab(priv: Boolean, start: String? = null) {
        val w = WebView(this); val t = Tab(w, priv)
        if (priv && WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            ProfileStore.getInstance().getOrCreateProfile("private"); WebViewCompat.setProfile(w, "private")
        }
        w.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true; mediaPlaybackRequiresUserGesture = true
            if (priv) { cacheMode = WebSettings.LOAD_NO_CACHE }
        }
        w.addJavascriptInterface(Bridge(t), "Valiryan")
        w.setDownloadListener { u, _, cd, mime, _ ->
            DownloadWorker.enqueue(this, Media(u, "file", URLUtil.guessFileName(u, cd, mime), t.page, cookieFor(t, u))); toast("Download queued")
        }
        w.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
                if (Block.blocked(r.url.host ?: "")) return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                val u = r.url.toString()
                Sniffer.classify(u)?.let { k ->
                    t.add(Media(u, k, Sniffer.nameOf(u), r.requestHeaders["Referer"] ?: t.page, "")); runOnUiThread { refreshBadge() }
                }
                return null
            }
            override fun onPageStarted(v: WebView, u: String, f: android.graphics.Bitmap?) {
                t.page = u; t.media.clear(); t.prot = false; if (t === cur) { urlBar.setText(u); refreshBadge() }
            }
            override fun onPageFinished(v: WebView, u: String) { v.evaluateJavascript(Sniffer.JS, null); t.title = v.title ?: u }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(v: WebView, p: Int) { if (t === cur) { prog.progress = p; prog.visibility = if (p in 1..99) View.VISIBLE else View.GONE } }
            override fun onShowCustomView(v: View, cb: CustomViewCallback) { setContentView(v) }
            override fun onHideCustomView() { setContentView(root) }
        }
        tabs.add(t); switchTo(t); if (start != null) w.loadUrl(start)
    }
    private fun switchTo(t: Tab) {
        cur = t; holder.removeAllViews(); holder.addView(t.web, FrameLayout.LayoutParams(-1, -1))
        if (t.priv) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        urlBar.setText(t.page); root.setBackgroundColor(if (t.priv) 0xFF1B0A2E.toInt() else 0xFF040714.toInt()); refreshBadge()
    }
    private fun closeTab(t: Tab) {
        tabs.remove(t); holder.removeView(t.web); t.web.destroy()
        if (t.priv && tabs.none { it.priv }) wipePrivate()
        if (tabs.isEmpty()) newTab(false, "https://duckduckgo.com") else switchTo(tabs.last())
    }
    private fun wipePrivate() = try {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) ProfileStore.getInstance().deleteProfile("private")
        else { CookieManager.getInstance().removeAllCookies(null); WebStorage.getInstance().deleteAllData() }
    } catch (e: Exception) { }
    private fun showTabs() {
        val names = tabs.map { (if (it.priv) "🕶 " else "") + it.title }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Tabs").setItems(names) { _, i -> switchTo(tabs[i]) }
            .setNeutralButton("Close this tab") { _, _ -> cur?.let { closeTab(it) } }.show()
    }

    // ---------- address / menu ----------
    private fun load(s: String) {
        val t = s.trim(); if (t.isEmpty()) return
        val u = when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.contains(".") && !t.contains(" ") -> "https://$t"
            else -> "https://duckduckgo.com/?q=" + Uri.encode(t)
        }
        cur?.web?.loadUrl(u)
    }
    private fun showMenu(v: View) = PopupMenu(this, v).apply {
        listOf("New tab", "New private tab", "Add bookmark", "Bookmarks", "Import bookmarks", "Downloads", "DIY playlists").forEachIndexed { i, s -> menu.add(0, i, i, s) }
        setOnMenuItemClickListener {
            when (it.itemId) {
                0 -> newTab(false, "https://duckduckgo.com"); 1 -> newTab(true, "https://duckduckgo.com")
                2 -> cur?.let { t -> val u = t.page; if (u.isNotEmpty()) { Store.upsert("bm", idOf(u), JSONObject().put("id", idOf(u)).put("title", t.title).put("url", u)); toast("Bookmarked") } }
                3 -> libLauncher.launch(android.content.Intent(this@MainActivity, LibraryActivity::class.java).putExtra("mode", "bm"))
                4 -> importLauncher.launch(arrayOf("text/html", "text/*", "*/*"))
                5 -> startActivity(android.content.Intent(this@MainActivity, LibraryActivity::class.java).putExtra("mode", "dl"))
                6 -> startActivity(android.content.Intent(this@MainActivity, LibraryActivity::class.java).putExtra("mode", "diy"))
            }; true
        }
    }.show()

    private fun importBookmarks(u: Uri) {
        val html = contentResolver.openInputStream(u)!!.bufferedReader().readText(); var n = 0
        Regex("""<A[^>]+HREF="([^"]+)"[^>]*>(.*?)</A>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).findAll(html).forEach {
            val href = it.groupValues[1]
            if (href.startsWith("http")) {
                Store.upsert("bm", idOf(href), JSONObject().put("id", idOf(href)).put("url", href)
                    .put("title", it.groupValues[2].replace(Regex("<[^>]+>"), "").trim().ifEmpty { href })); n++
            }
        }
        toast("$n bookmarks imported")
    }

    // ---------- media detection ----------
    inner class Bridge(private val t: Tab) {
        @JavascriptInterface fun found(u: String, hint: String) {
            val k = Sniffer.classify(u) ?: (if (hint == "el") "file" else null) ?: return
            t.add(Media(u, k, Sniffer.nameOf(u), t.page, "")); runOnUiThread { refreshBadge() }
        }
        @JavascriptInterface fun drm() { t.prot = true; runOnUiThread { refreshBadge() } }
    }
    private fun refreshBadge() { badge.text = "⬇" + (cur?.media?.size ?: 0) + if (cur?.prot == true) "🔒" else "" }
    private fun cookieFor(t: Tab, u: String): String = try {
        if (t.priv && WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE))
            ProfileStore.getInstance().getProfile("private")?.cookieManager?.getCookie(u) ?: ""
        else CookieManager.getInstance().getCookie(u) ?: ""
    } catch (e: Exception) { "" }

    private fun showMedia() {
        val t = cur ?: return; val items = synchronized(t.media) { t.media.values.toList() }
        if (items.isEmpty()) {
            AlertDialog.Builder(this).setMessage(if (t.prot) "This page uses protected (DRM / blob) streaming. It can't be saved."
                else "No media detected yet. Start the video, then check again.").setPositiveButton("OK", null).show(); return
        }
        val labels = items.map { "[${it.kind.uppercase()}] ${it.title}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle(if (t.prot) "Media found (page also has protected streams)" else "Media found")
            .setItems(labels) { _, i -> act(items[i].copy(cookie = cookieFor(t, items[i].url))) }.show()
    }
    private fun act(m: Media) {
        AlertDialog.Builder(this).setTitle(m.title).setItems(arrayOf("Download", "Play", "Add to DIY playlist")) { _, w ->
            when (w) {
                0 -> if (m.kind == "dash") toast("DASH download isn't supported yet - use Play") else { DownloadWorker.enqueue(this, m); toast("Queued - see Downloads") }
                1 -> PlayerActivity.start(this, listOf(m))
                2 -> AlertDialog.Builder(this).setTitle("Which universe?").setItems(Universes.list.toTypedArray()) { _, i ->
                    val id = idOf(m.url)
                    Store.upsert("diy", id, JSONObject().put("id", id).put("title", m.title).put("url", m.url).put("fr", Universes.list[i]).put("kind", m.kind).put("referer", m.referer))
                    toast("Added")
                }.show()
            }
        }.show()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        val t = cur
        if (t != null && t.web.canGoBack()) t.web.goBack() else if (tabs.size > 1 && t != null) closeTab(t) else super.onBackPressed()
    }
}
