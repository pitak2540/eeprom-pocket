package com.james.scan

import android.app.Application
import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.android.OpenCVLoader
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val ok = try { OpenCVLoader.initLocal() } catch (e: Throwable) { false }
        if (!ok) {
            try { System.loadLibrary("opencv_java4") } catch (e: Throwable) { Log.e("JamesScan", "OpenCV load failed", e) }
        }
    }
}

class Page(
    var src: String,
    var quad: FloatArray?,
    var filter: String = Filters.ENHANCE,
    var rotation: Int = 0
) {
    /** gallery JPG this page was auto-saved to (content:// uri, or a file path below Android 10) */
    var savedUri: String? = null
    /** render key at the time of that save; differs when the page was edited afterwards */
    var savedKey: String? = null

    fun key() = "$src|${quad?.joinToString(",")}|$rotation"
    fun renderKey() = key() + "|" + filter

    fun toJson() = JSONObject().apply {
        put("src", src)
        quad?.let { q -> put("quad", JSONArray().apply { q.forEach { put(it.toDouble()) } }) }
        put("filter", filter)
        put("rotation", rotation)
        savedUri?.let { put("savedUri", it) }
        savedKey?.let { put("savedKey", it) }
    }

    companion object {
        fun fromJson(o: JSONObject): Page {
            val qa = o.optJSONArray("quad")
            val q = if (qa != null && qa.length() == 8) FloatArray(8) { qa.getDouble(it).toFloat() } else null
            return Page(o.getString("src"), q, o.optString("filter", Filters.ENHANCE), o.optInt("rotation", 0)).apply {
                savedUri = if (o.has("savedUri")) o.getString("savedUri") else null
                savedKey = if (o.has("savedKey")) o.getString("savedKey") else null
            }
        }
    }
}

class Doc(val id: String, var title: String, val created: Long, val pages: MutableList<Page>)

object DocStore {
    private const val TAG_DEFAULT_FILTER = "default_filter"

    fun root(ctx: Context) = File(ctx.filesDir, "docs").apply { mkdirs() }
    fun dir(ctx: Context, id: String) = File(root(ctx), id).apply { mkdirs() }

    fun create(ctx: Context): Doc {
        val now = System.currentTimeMillis()
        val title = "JAMES Scan " + SimpleDateFormat("dd-MM-yyyy HH.mm", Locale.US).format(Date(now))
        val d = Doc(now.toString(), title, now, mutableListOf())
        save(ctx, d)
        return d
    }

    fun newImageFile(ctx: Context, doc: Doc): File = File(dir(ctx, doc.id), "src_${System.nanoTime()}.jpg")

    fun save(ctx: Context, doc: Doc) {
        val d = dir(ctx, doc.id)
        val o = JSONObject().apply {
            put("title", doc.title)
            put("created", doc.created)
            put("pages", JSONArray().apply { doc.pages.forEach { put(it.toJson()) } })
        }
        File(d, "doc.json").writeText(o.toString())
        File(d, "thumb.jpg").delete()
        // remove source images no longer referenced
        val used = doc.pages.map { it.src }.toSet()
        d.listFiles()?.filter { it.name.startsWith("src_") && it.name !in used }?.forEach { it.delete() }
    }

    fun load(ctx: Context, id: String): Doc? {
        val f = File(File(root(ctx), id), "doc.json")
        if (!f.exists()) return null
        return try {
            val o = JSONObject(f.readText())
            val arr = o.getJSONArray("pages")
            Doc(id, o.getString("title"), o.getLong("created"),
                MutableList(arr.length()) { Page.fromJson(arr.getJSONObject(it)) })
        } catch (e: Exception) { null }
    }

    fun list(ctx: Context): List<Doc> =
        root(ctx).listFiles()?.mapNotNull { load(ctx, it.name) }
            ?.filter { it.pages.isNotEmpty() }
            ?.sortedByDescending { it.created } ?: emptyList()

    fun delete(ctx: Context, id: String) {
        File(root(ctx), id).deleteRecursively()
    }

    /** remove empty docs left behind by cancelled scans */
    fun cleanup(ctx: Context) {
        root(ctx).listFiles()?.forEach { f ->
            val d = load(ctx, f.name)
            if (d == null || d.pages.isEmpty()) f.deleteRecursively()
        }
    }

    fun defaultFilter(ctx: Context): String =
        ctx.getSharedPreferences("prefs", 0).getString(TAG_DEFAULT_FILTER, Filters.ENHANCE) ?: Filters.ENHANCE

    fun setDefaultFilter(ctx: Context, f: String) {
        ctx.getSharedPreferences("prefs", 0).edit().putString(TAG_DEFAULT_FILTER, f).apply()
    }
}
