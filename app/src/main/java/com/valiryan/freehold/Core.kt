package com.valiryan.freehold

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Media(val url: String, val kind: String, val title: String, val referer: String, val cookie: String)

fun idOf(u: String) = java.security.MessageDigest.getInstance("MD5").digest(u.toByteArray()).joinToString("") { "%02x".format(it) }

object Universes {
    val list = listOf("Masters of the Universe","ThunderCats","Back to the Future","Fallout","Doom","Blade Runner","Ready Player One","The Witcher","The Lord of the Rings","Game of Thrones","Lazarus","Cowboy Bebop","Akira","Kimetsu no Yaiba","Death Note","Star Wars","Star Trek","Chainsaw Man","Bleach","Marvel","Harry Potter","Maze Runner","Rick and Morty","Dune","Castlevania","Nosferatu (2024)","Secret Level","Berserk (1997)","Ghost in the Shell","The Three-Body Problem","Other")
}

/** Small JSON store: "dl" downloads, "diy" playlist, "bm" bookmarks, plus resume positions. */
object Store {
    private lateinit var ctx: Context
    fun init(c: Context) { ctx = c.applicationContext }
    private val sp get() = ctx.getSharedPreferences("valiryan", Context.MODE_PRIVATE)
    private fun arr(k: String) = try { JSONArray(sp.getString(k, "[]")) } catch (e: Exception) { JSONArray() }
    fun list(k: String): List<JSONObject> { val a = arr(k); return (0 until a.length()).map { a.getJSONObject(it) } }
    fun get(k: String, id: String) = list(k).firstOrNull { it.optString("id") == id }
    @Synchronized fun upsert(k: String, id: String, o: JSONObject) {
        val n = JSONArray(); var done = false
        list(k).forEach { if (it.optString("id") == id) { n.put(o); done = true } else n.put(it) }
        if (!done) n.put(o)
        sp.edit().putString(k, n.toString()).apply()
    }
    @Synchronized fun remove(k: String, id: String) {
        val n = JSONArray(); list(k).forEach { if (it.optString("id") != id) n.put(it) }
        sp.edit().putString(k, n.toString()).apply()
    }
    fun pos(url: String) = sp.getLong("pos:$url", 0L)
    fun setPos(url: String, p: Long) { sp.edit().putLong("pos:$url", p).apply() }
}

object Block {
    private val hosts = setOf("doubleclick.net","googlesyndication.com","googleadservices.com","google-analytics.com","googletagmanager.com","adservice.google.com","facebook.net","scorecardresearch.com","outbrain.com","taboola.com","adnxs.com","popads.net","exoclick.com","propellerads.com","amazon-adsystem.com","hotjar.com","mixpanel.com","segment.io","criteo.com","pubmatic.com","rubiconproject.com","adsterra.com","juicyads.com")
    fun blocked(host: String) = hosts.any { host == it || host.endsWith(".$it") }
}

object Sniffer {
    private val ext = Regex("""\.(mp4|m4v|webm|mov|mkv|mp3|m4a|flac|ogg|opus|wav|aac)$""", RegexOption.IGNORE_CASE)
    fun classify(url: String): String? {
        if (!url.startsWith("http")) return null
        val p = url.substringBefore('#').substringBefore('?').lowercase()
        return when {
            p.endsWith(".m3u8") -> "hls"
            p.endsWith(".mpd") -> "dash"
            ext.containsMatchIn(p) -> "file"
            else -> null
        }
    }
    fun nameOf(url: String): String = try {
        java.net.URLDecoder.decode(url.substringBefore('?').substringAfterLast('/'), "UTF-8").ifEmpty { "video" }
    } catch (e: Exception) { "video" }

    /** Injected after each page load: reports <video>/<audio> sources and resource URLs, flags DRM/blob streams. */
    val JS = """(function(){if(window.__vf)return;window.__vf=1;
function rep(u,h){try{u=new URL(u,location.href).href;if(u.indexOf('http')===0)Valiryan.found(u,h||'')}catch(e){}}
function scan(){document.querySelectorAll('video,audio').forEach(function(m){
if(m.currentSrc){if(m.currentSrc.indexOf('blob:')===0)Valiryan.drm();else rep(m.currentSrc,'el')}
m.querySelectorAll('source').forEach(function(s){rep(s.src,'el')})});
performance.getEntriesByType('resource').forEach(function(r){rep(r.name,'')})}
setInterval(scan,2500);scan();
var o=navigator.requestMediaKeySystemAccess;
if(o){navigator.requestMediaKeySystemAccess=function(){Valiryan.drm();return o.apply(this,arguments)}}
})();"""
}
