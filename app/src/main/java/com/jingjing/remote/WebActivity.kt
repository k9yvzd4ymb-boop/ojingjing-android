package com.jingjing.remote

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WebActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "extra_url"
        private const val MAX_RETRY = 12
    }

    private lateinit var webView: WebView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var offlinePanel: View
    private lateinit var tvOfflineDesc: android.widget.TextView

    private var targetUrl: String = ""
    private val mainHandler = Handler(Looper.getMainLooper())

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraUri: Uri? = null

    private var retryCount = 0
    private var retryRunnable: Runnable? = null
    private var everLoaded = false
    private var connectivityManager: ConnectivityManager? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val cb = filePathCallback
        filePathCallback = null
        if (cb == null) return@registerForActivityResult

        val d = result.data
        val uris: Array<Uri>? = when {
            result.resultCode != Activity.RESULT_OK -> null
            d != null && d.clipData != null -> {
                val cd = d.clipData!!
                Array(cd.itemCount) { cd.getItemAt(it).uri }
            }
            d != null && d.data != null -> arrayOf(d.data!!)
            cameraUri != null -> arrayOf(cameraUri!!)
            else -> null
        }
        cameraUri = null
        cb.onReceiveValue(uris)
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // 网络恢复，若当前是断线状态就立刻重试
            mainHandler.post {
                if (offlinePanel.visibility == View.VISIBLE) {
                    cancelRetry()
                    retryCount = 0
                    reload()
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppCtx.init(this)
        setContentView(R.layout.activity_web)

        webView = findViewById(R.id.webView)
        progress = findViewById(R.id.progress)
        offlinePanel = findViewById(R.id.offlinePanel)
        tvOfflineDesc = findViewById(R.id.tvOfflineDesc)

        targetUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (targetUrl.isBlank()) {
            finish()
            return
        }

        enterImmersive()
        setupWebView()

        findViewById<View>(R.id.btnRetry).setOnClickListener {
            cancelRetry()
            retryCount = 0
            hideOffline()
            reload()
        }
        findViewById<View>(R.id.btnHome).setOnClickListener { finish() }
        findViewById<View>(R.id.btnForget).setOnClickListener { forgetCurrent() }

        registerNetworkCallback()
        reload()

        if (ConnectionStore.keepAlive) {
            KeepAliveService.start(this, labelFor(targetUrl))
        }
        maybeAskBatteryExemption()
    }

    /** 连不上时的一键清理：把这条设备记录删掉，回首页重新扫码。 */
    private fun forgetCurrent() {
        ConnectionStore.delete(this, ConnectionStore.durableUrl(targetUrl))
        toast(getString(R.string.forgot))
        finish()
    }

    /** 通知栏里显示的名字：优先用设备自定义名，其次域名。 */
    private fun labelFor(url: String): String {
        val durable = ConnectionStore.durableUrl(url)
        ConnectionStore.list(this).firstOrNull { it.url == durable }?.name?.let { return it }
        return Uri.parse(url).host ?: getString(R.string.name_default)
    }

    /** 首次连接时提示关闭电池优化，否则后台会被系统冻结导致掉线。 */
    private fun maybeAskBatteryExemption() {
        val prefs = getSharedPreferences("ojingjing_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("battery_asked", false)) return
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            prefs.edit().putBoolean("battery_asked", true).apply()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("让连接更稳")
            .setMessage("把「哦鲸鲸」加入电池优化白名单，锁屏后也能持续接收电脑上的进度。")
            .setNegativeButton("以后再说") { _, _ ->
                prefs.edit().putBoolean("battery_asked", true).apply()
            }
            .setPositiveButton("去设置") { _, _ ->
                prefs.edit().putBoolean("battery_asked", true).apply()
                try {
                    startActivity(
                        Intent(
                            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:$packageName")
                        )
                    )
                } catch (e: Exception) {
                    toast("请到 系统设置-电池 中手动允许后台运行")
                }
            }
            .show()
    }

    // ---------- 全屏 ----------

    private fun enterImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ---------- WebView 配置 ----------

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val s: WebSettings = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.builtInZoomControls = false
        s.displayZoomControls = false
        s.mediaPlaybackRequiresUserGesture = false
        s.javaScriptCanOpenWindowsAutomatically = true
        s.setSupportMultipleWindows(false)
        s.allowFileAccess = true
        s.allowContentAccess = true
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        webView.isVerticalScrollBarEnabled = true

        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest
            ): Boolean {
                val url = request.url
                val scheme = url.scheme?.lowercase() ?: return false
                // 外部 App 链接交给系统
                if (scheme != "http" && scheme != "https") {
                    return try {
                        startActivity(Intent(Intent.ACTION_VIEW, url))
                        true
                    } catch (e: Exception) {
                        true
                    }
                }
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progress.visibility = View.GONE
                everLoaded = true
                retryCount = 0
                hideOffline()
                touchOrigin(url)
            }

            override fun onReceivedError(
                view: WebView, request: WebResourceRequest, error: WebResourceError
            ) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) handleDisconnect()
            }

            override fun onReceivedHttpError(
                view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                if (request.isForMainFrame && errorResponse.statusCode >= 400) handleDisconnect()
            }

            override fun onReceivedSslError(
                view: WebView?, handler: SslErrorHandler?, error: SslError?
            ) {
                // 不绕过证书错误，避免中间人风险
                handler?.cancel()
                handleDisconnect()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.progress = newProgress
                if (newProgress >= 100) progress.visibility = View.GONE
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@WebActivity.filePathCallback?.onReceiveValue(null)
                this@WebActivity.filePathCallback = filePathCallback ?: return false
                return try {
                    fileChooserLauncher.launch(buildChooserIntent(fileChooserParams))
                    true
                } catch (e: Exception) {
                    this@WebActivity.filePathCallback = null
                    toast("无法打开文件选择器")
                    false
                }
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                request ?: return
                val granted = request.resources.filter { res ->
                    when (res) {
                        PermissionRequest.RESOURCE_VIDEO_CAPTURE ->
                            hasPermission(Manifest.permission.CAMERA)
                        else -> false
                    }
                }.toTypedArray()
                if (granted.isEmpty()) request.deny() else request.grant(granted)
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            try {
                val request = android.app.DownloadManager.Request(Uri.parse(url))
                request.setMimeType(mimeType)
                request.addRequestHeader("User-Agent", userAgent)
                request.addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url))
                request.setNotificationVisibility(
                    android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                request.setDestinationInExternalPublicDir(
                    android.os.Environment.DIRECTORY_DOWNLOADS,
                    URLUtil.guessFileName(url, contentDisposition, mimeType)
                )
                val dm = getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
                dm.enqueue(request)
                toast("已开始下载")
            } catch (e: Exception) {
                toast("下载失败")
            }
        }
    }

    private fun hasPermission(perm: String): Boolean =
        ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

    /** 刷新「上次使用」时间，用于估算链接剩余有效期。 */
    private fun touchOrigin(loadedUrl: String?) {
        val uri = Uri.parse(loadedUrl ?: targetUrl)
        val scheme = uri.scheme ?: return
        val authority = uri.authority ?: return
        ConnectionStore.touch(this, "$scheme://$authority/")
    }

    private fun buildChooserIntent(params: WebChromeClient.FileChooserParams?): Intent {
        val accepts = params?.acceptTypes?.filter { it.isNotBlank() } ?: emptyList()
        val multiple = params?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE

        val content = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = when {
                accepts.isEmpty() -> "*/*"
                accepts.size == 1 -> accepts[0]
                else -> "*/*"
            }
            if (accepts.size > 1) {
                putExtra(Intent.EXTRA_MIME_TYPES, accepts.toTypedArray())
            }
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
        }

        val chooser = Intent(Intent.ACTION_CHOOSER).apply {
            putExtra(Intent.EXTRA_INTENT, content)
            putExtra(Intent.EXTRA_TITLE, "选择要发送的文件")
        }

        val wantsImage = accepts.isEmpty() || accepts.any {
            it.startsWith("image/") || it == "*/*"
        }
        if (wantsImage && hasPermission(Manifest.permission.CAMERA)) {
            try {
                val uri = createCameraUri()
                val capture = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    putExtra(MediaStore.EXTRA_OUTPUT, uri)
                    addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }
                if (capture.resolveActivity(packageManager) != null) {
                    chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(capture))
                    cameraUri = uri
                }
            } catch (e: Exception) {
                cameraUri = null
            }
        }
        return chooser
    }

    private fun createCameraUri(): Uri {
        val dir = File(cacheDir, "capture").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val file = File(dir, "IMG_$stamp.jpg")
        return FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
    }

    // ---------- 连接 / 重连 ----------

    private fun reload() {
        hideOffline()
        progress.visibility = View.VISIBLE
        webView.loadUrl(targetUrl)
    }

    private fun handleDisconnect() {
        progress.visibility = View.GONE
        if (retryCount >= MAX_RETRY) {
            showOffline("已重试 $MAX_RETRY 次仍未连上。请检查电脑是否开机并停留在「连接手机」页面，然后点「立即重试」。")
            return
        }
        retryCount++
        val delay = (2000L + retryCount * 1500L).coerceAtMost(15000L)
        showOffline("连接断开了，${delay / 1000} 秒后自动重连（第 $retryCount/$MAX_RETRY 次）…")
        cancelRetry()
        retryRunnable = Runnable {
            if (!isNetworkAvailable()) {
                showOffline("手机当前没有网络，恢复后会自动重连。")
                retryCount--
                handleDisconnectAgain()
            } else {
                webView.loadUrl(targetUrl)
            }
        }.also { mainHandler.postDelayed(it, delay) }
    }

    private fun handleDisconnectAgain() {
        cancelRetry()
        retryRunnable = Runnable { if (isNetworkAvailable()) { retryCount = 0; reload() } }
            .also { mainHandler.postDelayed(it, 3000) }
    }

    private fun cancelRetry() {
        retryRunnable?.let { mainHandler.removeCallbacks(it) }
        retryRunnable = null
    }

    private fun showOffline(msg: String) {
        tvOfflineDesc.text = msg
        offlinePanel.visibility = View.VISIBLE
    }

    private fun hideOffline() {
        offlinePanel.visibility = View.GONE
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager = cm
        try {
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                networkCallback
            )
        } catch (e: Exception) {
            // 忽略：部分机型限制注册，不影响重连逻辑
        }
    }

    // ---------- 生命周期 ----------

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
        webView.onPause()
        // 后台时若开启了保活则不暂停 WebView 的 JS，尽量维持连接
        if (!ConnectionStore.keepAlive) webView.pauseTimers()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        webView.resumeTimers()
        if (everLoaded && offlinePanel.visibility == View.VISIBLE) {
            cancelRetry()
            retryCount = 0
            reload()
        }
    }

    override fun onDestroy() {
        cancelRetry()
        connectivityManager?.let {
            try {
                it.unregisterNetworkCallback(networkCallback)
            } catch (e: Exception) {
            }
        }
        if (!ConnectionStore.keepAlive) KeepAliveService.stop(this)
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (webView.canGoBack()) {
                webView.goBack()
            } else {
                confirmExit()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun confirmExit() {
        AlertDialog.Builder(this)
            .setTitle("要断开连接吗？")
            .setMessage("DSH 上的任务仍会在电脑上继续运行，随时可以重新连上。")
            .setNegativeButton("继续使用", null)
            .setPositiveButton("断开") { _, _ -> finish() }
            .show()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}