package com.jingjing.remote

import android.Manifest
import android.app.Dialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    companion object {
        /** 设备网格列数（2x2）。 */
        private const val GRID_COLS = 2

        /** 网格一屏放得下的设备数；超过就变成「更多」长条。 */
        private const val GRID_MAX = 4

        /** 插件里设备凭据的空闲过期时间：30 天。 */
        private const val IDLE_EXPIRE_MS = 30L * 24 * 60 * 60 * 1000

        /** 自动清理的宽限期：过期后再留 3 天，避免边界误差把还能用的记录删掉。 */
        private const val PRUNE_GRACE_MS = 3L * 24 * 60 * 60 * 1000
    }

    private lateinit var historyContainer: LinearLayout
    private lateinit var tvEmpty: TextView

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val content = result.contents
        if (content.isNullOrBlank()) return@registerForActivityResult
        val url = normalizeUrl(content)
        if (url == null) {
            toast(getString(R.string.invalid_url))
            return@registerForActivityResult
        }
        openConnection(url)
    }

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 让渐变底色铺到状态栏/导航栏后面，内容再用 insets 让开，全版本表现一致。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        AppCtx.init(this)
        setContentView(R.layout.activity_main)

        // 背景图铺满全屏（含状态栏），只给内容加 insets，避免图片被裁掉一圈。
        val content = findViewById<View>(R.id.contentScroll)
        ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }
        applyBackgroundBlur()

        historyContainer = findViewById(R.id.historyContainer)
        tvEmpty = findViewById(R.id.tvEmpty)

        findViewById<View>(R.id.btnScan).setOnClickListener {
            scanLauncher.launch(
                ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt(getString(R.string.scan_hint))
                    .setBeepEnabled(false)
                    .setOrientationLocked(true)
            )
        }

        findViewById<View>(R.id.btnManual).setOnClickListener { v -> showManualDialog(v) }

        askNotificationPermission()
    }

    /** 主页背景图：Android 12+ 用 RenderEffect 做真实高斯模糊（毛玻璃）。 */
    private fun applyBackgroundBlur() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            val radius = 3f * resources.displayMetrics.density
            findViewById<ImageView>(R.id.bgImage).setRenderEffect(
                RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
            )
        } catch (t: Throwable) {
            // 个别设备不支持，忽略即可（还有半透明玻璃罩兜底）
        }
    }

    override fun onResume() {
        super.onResume()
        migrateStoredUrls()
        autoPruneStale()
        renderDevices()
    }

    /** 自动清理：服务器端凭据早已过期的记录（空闲 30 天 + 3 天宽限）直接删掉。 */
    private fun autoPruneStale() {
        val removed = ConnectionStore.pruneStale(this, IDLE_EXPIRE_MS + PRUNE_GRACE_MS)
        if (removed > 0) showNotice(getString(R.string.prune_notice, removed))
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // ---------- 地址处理 ----------

    /** 从扫码/粘贴的文本里抽出可用地址。 */
    private fun normalizeUrl(raw: String): String? {
        var s = raw.trim().trim('"', '\'', ' ')
        if (s.isEmpty()) return null
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) {
            // 有些二维码只放了 host:port/path，补一个 http
            if (s.contains(".") || s.contains(":")) s = "http://$s" else return null
        }
        return try {
            val uri = Uri.parse(s)
            if (uri.host.isNullOrBlank()) null else s
        } catch (e: Exception) {
            null
        }
    }

    private fun displayName(url: String): String {
        return try {
            val uri = Uri.parse(url)
            val host = uri.host ?: return url
            val port = if (uri.port > 0) ":${uri.port}" else ""
            "$host$port"
        } catch (e: Exception) {
            url
        }
    }

    private fun openConnection(url: String) {
        // 一次性配对链接不能当「设备」存：存它对应的长期入口（站点根地址），
        // 这样首页立刻就能按 30 天规则算剩余天数。页面本身仍用原始链接打开。
        val durable = ConnectionStore.durableUrl(url)
        val existing = ConnectionStore.list(this).firstOrNull { it.url == durable }
        ConnectionStore.save(this, durable, existing?.name ?: defaultName())
        startActivity(
            Intent(this, WebActivity::class.java).putExtra(WebActivity.EXTRA_URL, url)
        )
        renderDevices()
    }

    /**
     * 老版本会把一次性的配对链接直接当设备存下来（那种链接用过就废）。
     * 这里统一迁移成站点根地址——它才是这台电脑长期有效的入口。
     */
    private fun migrateStoredUrls() {
        val items = ConnectionStore.list(this)
        items.filter { it.url.contains("pair", true) }.forEach { entry ->
            val durable = ConnectionStore.durableUrl(entry.url)
            if (durable == entry.url) return@forEach
            val keepName = items.firstOrNull { it.url == durable }?.name ?: entry.name
            ConnectionStore.delete(this, entry.url)
            ConnectionStore.saveKeepingTime(this, durable, keepName, entry.time)
        }
    }

    private fun defaultName(): String {
        val base = getString(R.string.name_default)
        val names = ConnectionStore.list(this).map { it.name }
        if (base !in names) return base
        var i = 2
        while ("$base $i" in names) i++
        return "$base $i"
    }

    // ---------- 设备网格 ----------

    private fun renderDevices() {
        val items = ConnectionStore.list(this)
        historyContainer.removeAllViews()
        tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE

        val showMore = items.size > GRID_MAX
        val visible = if (showMore) items.take(GRID_MAX) else items

        // 自己拼 2 列网格：GridLayout 的 columnWeight 在部分 ROM 上不生效，
        // 用「一行一个 LinearLayout + weight」最稳。
        visible.chunked(GRID_COLS).forEach { rowItems ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            rowItems.forEach { entry ->
                val tile = LayoutInflater.from(this).inflate(R.layout.item_connection, row, false)
                val lp = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
                lp.setMargins(dp(5), dp(5), dp(5), dp(5))
                tile.layoutParams = lp
                tile.findViewById<TextView>(R.id.tvTitle).text = entry.name
                tile.setOnClickListener { showDeviceDetail(entry, tile) }
                row.addView(tile)
            }
            // 补空位，保证最后一行不满时格子宽度和上面一致
            repeat(GRID_COLS - rowItems.size) {
                row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            }
            historyContainer.addView(row)
        }

        if (showMore) {
            val more = LayoutInflater.from(this)
                .inflate(R.layout.item_more_button, historyContainer, false)
            more.setOnClickListener { showAllDevices(more) }
            historyContainer.addView(more)
        }
    }

    /** 剩余有效期：按插件「30 天空闲失效」估算，纯本地推算。 */
    private fun remainingText(entry: ConnectionStore.Entry): String {
        if (entry.url.contains("pair=", true)) return getString(R.string.detail_onetime)
        val left = IDLE_EXPIRE_MS - (System.currentTimeMillis() - entry.time)
        if (left <= 0) return getString(R.string.detail_expired)
        val days = (left / (24L * 60 * 60 * 1000)).toInt()
        return getString(R.string.detail_days, maxOf(1, days))
    }

    private fun showDeviceDetail(entry: ConnectionStore.Entry, origin: View? = null) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_connection_detail, null)
        view.findViewById<TextView>(R.id.tvName).text = entry.name
        view.findViewById<TextView>(R.id.tvHost).text = displayName(entry.url)
        view.findViewById<TextView>(R.id.tvRemain).text = remainingText(entry)
        view.findViewById<TextView>(R.id.tvLast).text =
            SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(entry.time))

        val dialog = glassDialog(view, origin)
        view.findViewById<View>(R.id.btnConnect).setOnClickListener {
            dialog.dismiss()
            openConnection(entry.url)
        }
        view.findViewById<View>(R.id.btnRename).setOnClickListener {
            dialog.dismiss()
            showRenameDialog(entry)
        }
        view.findViewById<View>(R.id.btnDelete).setOnClickListener {
            dialog.dismiss()
            ConnectionStore.delete(this, entry.url)
            renderDevices()
            toast(getString(R.string.deleted))
        }
        dialog.show()
    }

    private fun showAllDevices(origin: View?) {
        val items = ConnectionStore.list(this)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_device_list, null)
        val container = view.findViewById<LinearLayout>(R.id.listContainer)
        val scroll = view.findViewById<ScrollView>(R.id.svDevices)

        val dialog = glassDialog(view, origin)

        items.forEach { entry ->
            val row = LayoutInflater.from(this)
                .inflate(R.layout.item_device_row, container, false)
            row.findViewById<TextView>(R.id.tvTitle).text = entry.name
            row.findViewById<TextView>(R.id.tvSub).text = displayName(entry.url)
            row.setOnClickListener {
                dialog.dismiss()
                showDeviceDetail(entry, null)
            }
            container.addView(row)
        }

        // 高度自适应：按行高算，最多约 5 行，再多就滚动
        val rowH = dp(62)
        val maxH = dp(320)
        scroll.layoutParams = scroll.layoutParams.apply {
            height = min(items.size * rowH, maxH)
        }

        view.findViewById<View>(R.id.btnClose).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    /** 统一的玻璃风格提示窗（自动清理等一次性通知）。 */
    private fun showNotice(message: String) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_notice, null)
        view.findViewById<TextView>(R.id.tvNotice).text = message
        val dialog = glassDialog(view)
        view.findViewById<View>(R.id.btnOk).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showRenameDialog(entry: ConnectionStore.Entry) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_rename, null)
        val et = view.findViewById<EditText>(R.id.etName)
        et.setText(entry.name)
        et.setSelection(et.text.length)

        val dialog = glassDialog(view)
        // 取消后回到上一级（设备详情），而不是直接回主页
        view.findViewById<View>(R.id.btnCancel).setOnClickListener {
            dialog.dismiss()
            showDeviceDetail(entry)
        }
        view.findViewById<View>(R.id.btnSave).setOnClickListener {
            val name = et.text.toString().trim().ifEmpty { getString(R.string.name_unnamed) }
            ConnectionStore.rename(this, entry.url, name)
            dialog.dismiss()
            renderDevices()
        }
        dialog.show()
        et.requestFocus()
    }

    // ---------- 输入地址弹窗（毛玻璃） ----------

    private fun showManualDialog(origin: View?) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_input_url, null)
        val et = view.findViewById<EditText>(R.id.etUrl)
        val tvError = view.findViewById<TextView>(R.id.tvError)
        val btnConnect = view.findViewById<View>(R.id.btnConnect)
        val btnPaste = view.findViewById<View>(R.id.btnPaste)
        val btnCancel = view.findViewById<View>(R.id.btnCancel)

        et.setText(lastUrl().orEmpty())
        et.setSelection(et.text.length)

        val dialog = glassDialog(view, origin)

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnPaste.setOnClickListener {
            val text = readClipboard()
            if (text.isEmpty()) {
                showFieldError(tvError, getString(R.string.clipboard_empty))
            } else {
                et.setText(text)
                et.setSelection(et.text.length)
                tvError.visibility = View.GONE
            }
        }

        btnConnect.setOnClickListener {
            val url = normalizeUrl(et.text.toString())
            if (url == null) {
                showFieldError(tvError, getString(R.string.invalid_url))
            } else {
                dialog.dismiss()
                openConnection(url)
            }
        }

        et.setOnEditorActionListener { _, _, _ ->
            btnConnect.performClick()
            true
        }

        dialog.show()
        et.requestFocus()
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        )
    }

    // ---------- 通用工具 ----------

    private fun glassDialog(view: View, origin: View? = null): Dialog {
        val dialog = Dialog(this, R.style.Theme_OJingJing_GlassDialog)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(view)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setDimAmount(0.42f)
        }
        // 先缩到点击位置，弹出后再放大到正常大小
        view.alpha = 0f
        view.scaleX = 0.84f
        view.scaleY = 0.84f
        dialog.setOnShowListener {
            applyGlassBlur(dialog)
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            view.post { playOpenAnimation(view, origin) }
        }
        return dialog
    }

    /** 从被点击的控件位置放大展开，而不是从屏幕中央淡入。 */
    private fun playOpenAnimation(content: View, origin: View?) {
        if (origin != null && origin.width > 0 && origin.height > 0) {
            val o = IntArray(2)
            val c = IntArray(2)
            origin.getLocationOnScreen(o)
            content.getLocationOnScreen(c)
            content.pivotX = (o[0] + origin.width / 2f) - c[0]
            content.pivotY = (o[1] + origin.height / 2f) - c[1]
        } else {
            content.pivotX = content.width / 2f
            content.pivotY = content.height / 2f
        }
        content.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(190L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    /** 系统支持跨窗口模糊时给弹窗背景加真实毛玻璃，否则退化为半透明玻璃观感。 */
    private fun applyGlassBlur(dialog: Dialog) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        try {
            val radius = (30 * resources.displayMetrics.density).toInt()
            dialog.window?.setBackgroundBlurRadius(radius)
        } catch (t: Throwable) {
            // 部分 ROM 关闭了跨窗口模糊，忽略即可
        }
    }

    private fun readClipboard(): String {
        return try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
        } catch (e: Exception) {
            ""
        }
    }

    private fun showFieldError(tv: TextView, msg: String) {
        tv.text = msg
        tv.visibility = View.VISIBLE
    }

    private fun lastUrl(): String? = ConnectionStore.list(this).firstOrNull()?.url

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
