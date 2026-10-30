package com.valiryan.freehold

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

class DownloadWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    companion object {
        private val gate = Semaphore(3)                       // 3 downloads at a time
        private val http = OkHttpClient.Builder().followRedirects(true).build()
        private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36"

        fun enqueue(ctx: Context, m: Media) {
            val id = idOf(m.url)
            Store.upsert("dl", id, JSONObject().put("id", id).put("title", m.title).put("url", m.url).put("kind", m.kind)
                .put("referer", m.referer).put("cookie", m.cookie).put("state", "queued").put("pct", 0))
            start(ctx, id)
        }
        fun start(ctx: Context, id: String) {
            Store.get("dl", id)?.let { Store.upsert("dl", id, it.put("state", "queued")) }
            WorkManager.getInstance(ctx).enqueueUniqueWork(id, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<DownloadWorker>().setInputData(workDataOf("id" to id)).build())
        }
        fun pause(ctx: Context, id: String) {
            WorkManager.getInstance(ctx).cancelUniqueWork(id)
            Store.get("dl", id)?.let { Store.upsert("dl", id, it.put("state", "paused")) }
        }
        fun remove(ctx: Context, id: String) {
            WorkManager.getInstance(ctx).cancelUniqueWork(id)
            File(ctx.cacheDir, "$id.part").delete(); Store.remove("dl", id)
        }
    }

    override suspend fun doWork(): Result = gate.withPermit {
        val id = inputData.getString("id") ?: return@withPermit Result.failure()
        val rec = Store.get("dl", id) ?: return@withPermit Result.failure()
        fun save(state: String, pct: Int, extra: JSONObject.() -> Unit = {}) {
            val o = Store.get("dl", id) ?: return
            Store.upsert("dl", id, o.put("state", state).put("pct", pct).apply(extra))
        }
        var last = 0L
        val title = rec.optString("title")
        val prog = { p: Int ->
            val n = System.currentTimeMillis()
            if (n - last > 1000) { last = n; save("running", p); note(id, title, p) }
        }
        try {
            save("running", rec.optInt("pct"))
            val uri = withContext(Dispatchers.IO) { if (rec.getString("kind") == "hls") hls(rec, id, prog) else file(rec, id, prog) }
            save("done", 100) { put("out", uri.toString()); remove("cookie") }
            note(id, title, 100, true)
            Result.success()
        } catch (e: CancellationException) {
            save("paused", rec.optInt("pct")); cancelNote(id); throw e
        } catch (e: Exception) {
            save("failed", 0) { put("err", e.message ?: "error") }; cancelNote(id); Result.failure()
        }
    }

    private fun req(url: String, rec: JSONObject, from: Long = 0): Request {
        val b = Request.Builder().url(url).header("User-Agent", UA)
        rec.optString("referer").takeIf { it.isNotEmpty() }?.let { b.header("Referer", it) }
        rec.optString("cookie").takeIf { it.isNotEmpty() }?.let { b.header("Cookie", it) }
        if (from > 0) b.header("Range", "bytes=$from-")
        return b.build()
    }
    private fun stopCheck() { if (isStopped) throw CancellationException("stopped") }
    private fun safe(t: String) = t.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80).ifBlank { "video" }

    /** Direct file with resume (HTTP Range). The .part file survives pause / app restarts. */
    private fun file(rec: JSONObject, id: String, prog: (Int) -> Unit): Uri {
        val url = rec.getString("url"); val part = File(applicationContext.cacheDir, "$id.part")
        var have = if (part.exists()) part.length() else 0L
        http.newCall(req(url, rec, have)).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            if (r.code != 206) have = 0
            val cl = r.body!!.contentLength(); val total = if (cl < 0) -1L else cl + have
            val mime = r.header("Content-Type")?.substringBefore(";") ?: "video/mp4"
            r.body!!.byteStream().use { ins ->
                FileOutputStream(part, have > 0).use { outs ->
                    val buf = ByteArray(65536); var n: Int; var done = have
                    while (ins.read(buf).also { n = it } >= 0) {
                        stopCheck(); outs.write(buf, 0, n); done += n
                        if (total > 0) prog((done * 100 / total).toInt())
                    }
                }
            }
            val seg = url.substringBefore('?').substringAfterLast('/')
            val ext = if (seg.contains('.')) seg.substringAfterLast('.').take(5) else "mp4"
            return publish(part, safe(rec.optString("title")).removeSuffix(".$ext") + "." + ext, mime)
        }
    }

    /** HLS: picks the best variant, downloads all segments, joins them (.ts or fragmented .mp4). No encryption, no live. */
    private fun hls(rec: JSONObject, id: String, prog: (Int) -> Unit): Uri {
        fun text(u: String) = http.newCall(req(u, rec)).execute().use { if (!it.isSuccessful) error("HTTP ${it.code}"); it.body!!.string() }
        fun abs(base: String, ref: String) = java.net.URI(base).resolve(ref).toString()
        var url = rec.getString("url"); var pl = text(url)
        if (pl.contains("#EXT-X-STREAM-INF")) {
            val ls = pl.lines(); var best = -1L; var bu = ""
            for (i in ls.indices) if (ls[i].startsWith("#EXT-X-STREAM-INF")) {
                val bw = Regex("BANDWIDTH=(\\d+)").find(ls[i])?.groupValues?.get(1)?.toLong() ?: 0L
                val u = ls.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: continue
                if (bw > best) { best = bw; bu = u }
            }
            url = abs(url, bu); pl = text(url)
        }
        if (Regex("#EXT-X-KEY:.*METHOD=(?!NONE)").containsMatchIn(pl)) error("Stream is encrypted - can't be saved")
        if (!pl.contains("#EXT-X-ENDLIST")) error("Live streams aren't supported")
        val segs = pl.lines().filter { it.isNotBlank() && !it.startsWith("#") }
        val map = Regex("#EXT-X-MAP:URI=\"([^\"]+)\"").find(pl)?.groupValues?.get(1)
        val part = File(applicationContext.cacheDir, "$id.part"); part.delete()
        FileOutputStream(part).use { o ->
            fun get(u: String) = http.newCall(req(abs(url, u), rec)).execute().use {
                if (!it.isSuccessful) error("HTTP ${it.code}"); it.body!!.byteStream().copyTo(o)
            }
            map?.let { get(it) }
            segs.forEachIndexed { i, s -> stopCheck(); get(s); prog((i + 1) * 100 / segs.size) }
        }
        return if (map != null) publish(part, safe(rec.optString("title")) + ".mp4", "video/mp4")
        else publish(part, safe(rec.optString("title")) + ".ts", "video/mp2t")
    }

    private fun publish(part: File, name: String, mime: String): Uri {
        val cv = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/Valiryan")
        }
        val cr = applicationContext.contentResolver
        val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)!!
        cr.openOutputStream(uri)!!.use { o -> part.inputStream().use { it.copyTo(o) } }
        part.delete(); return uri
    }

    private fun nm() = applicationContext.getSystemService(NotificationManager::class.java)
    private fun cancelNote(id: String) = nm().cancel(id.hashCode())
    private fun note(id: String, title: String, pct: Int, done: Boolean = false) {
        nm().createNotificationChannel(NotificationChannel("dl", "Downloads", NotificationManager.IMPORTANCE_LOW))
        val b = Notification.Builder(applicationContext, "dl").setContentTitle(title).setOnlyAlertOnce(true)
        if (done) b.setContentText("Saved to Downloads/Valiryan").setSmallIcon(android.R.drawable.stat_sys_download_done)
        else b.setProgress(100, pct, false).setOngoing(true).setSmallIcon(android.R.drawable.stat_sys_download)
        nm().notify(id.hashCode(), b.build())
    }
}
