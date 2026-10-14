package com.jingjing.remote

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * 以 JSON 形式把连接的电脑清单存进 SharedPreferences。
 */
object ConnectionStore {

    private const val PREF = "ojingjing_prefs"
    private const val KEY_LIST = "connections"
    private const val KEY_KEEPALIVE = "keep_alive"
    private const val MAX = 20

    data class Entry(val url: String, val name: String, val time: Long)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun list(ctx: Context): List<Entry> {
        val raw = prefs(ctx).getString(KEY_LIST, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Entry(
                    url = o.optString("url"),
                    name = o.optString("name"),
                    time = o.optLong("time")
                )
            }.filter { it.url.isNotEmpty() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun write(ctx: Context, entries: List<Entry>) {
        val arr = JSONArray()
        entries.take(MAX).forEach {
            arr.put(
                JSONObject()
                    .put("url", it.url)
                    .put("name", it.name)
                    .put("time", it.time)
            )
        }
        prefs(ctx).edit().putString(KEY_LIST, arr.toString()).apply()
    }

    /** 新增或更新一条，并置顶。 */
    fun save(ctx: Context, url: String, name: String) {
        val rest = list(ctx).filter { it.url != url }
        write(ctx, listOf(Entry(url, name, System.currentTimeMillis())) + rest)
    }

    /** 只刷新「上次使用」时间，用于计算剩余有效期。 */
    fun touch(ctx: Context, url: String) {
        val items = list(ctx)
        if (items.none { it.url == url }) return
        write(ctx, items.map {
            if (it.url == url) Entry(it.url, it.name, System.currentTimeMillis()) else it
        })
    }

    /** 迁移用：写入一条记录并保留它原来的时间戳（不能用 save，那会把时间刷成现在）。 */
    fun saveKeepingTime(ctx: Context, url: String, name: String, time: Long) {
        val rest = list(ctx).filter { it.url != url }
        write(ctx, listOf(Entry(url, name, time)) + rest)
    }

    /**
     * 清理空闲太久的记录：插件的设备凭据本身就是「空闲 30 天过期」，
     * 过了这个点这条记录一定连不上，留着只会干扰。返回删掉的条数。
     */
    fun pruneStale(ctx: Context, idleLimitMs: Long): Int {
        val items = list(ctx)
        if (items.isEmpty()) return 0
        val now = System.currentTimeMillis()
        val keep = items.filter { now - it.time <= idleLimitMs }
        val removed = items.size - keep.size
        if (removed > 0) write(ctx, keep)
        return removed
    }

    fun rename(ctx: Context, url: String, name: String) {
        write(ctx, list(ctx).map {
            if (it.url == url) Entry(it.url, name, it.time) else it
        })
    }

    fun delete(ctx: Context, url: String) {
        write(ctx, list(ctx).filter { it.url != url })
    }

    var keepAlive: Boolean
        get() = prefs(AppCtx.get()).getBoolean(KEY_KEEPALIVE, true)
        set(value) = prefs(AppCtx.get()).edit().putBoolean(KEY_KEEPALIVE, value).apply()

    /**
     * 扫码/粘贴得到的是**一次性配对链接**（`/pair-accept?pair=…` 或 `/pair?token=…`），
     * 用过即废，不该被当成"一台设备"存下来。
     * 它对应的长期入口是站点根地址，所以统一换算成 `scheme://authority/`。
     * 不是配对链接就原样返回。
     */
    fun durableUrl(url: String): String {
        if (!url.contains("pair", true)) return url
        return try {
            val uri = Uri.parse(url)
            val scheme = uri.scheme ?: return url
            val authority = uri.authority ?: return url
            val root = "$scheme://$authority/"
            if (root == url) url else root
        } catch (e: Exception) {
            url
        }
    }
}

/** 供没有 Context 的场景（如 Service）取应用级 Context。 */
object AppCtx {
    private var app: Context? = null
    fun init(c: Context) {
        app = c.applicationContext
    }

    fun get(): Context = app ?: throw IllegalStateException("AppCtx 未初始化")
}
