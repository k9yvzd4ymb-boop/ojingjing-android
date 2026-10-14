package com.jingjing.remote

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.journeyapps.barcodescanner.CaptureManager
import com.journeyapps.barcodescanner.DecoratedBarcodeView

/**
 * 全新高定扫码 Activity：
 * 适配 Android 14/15/16/17，纯自研月曜黑金 HUD 视觉外观，
 * 彻底废弃旧版红激光与生硬取景框，内置硬件级手电筒与流金激光动效。
 */
class CustomScanActivity : AppCompatActivity() {

    private lateinit var captureManager: CaptureManager
    private lateinit var barcodeScannerView: DecoratedBarcodeView
    private var laserAnimator: ObjectAnimator? = null
    private var isTorchOn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_custom_scan)

        // 顶栏沉浸式避让挖孔与状态栏
        val topBar = findViewById<View>(R.id.topBar)
        ViewCompat.setOnApplyWindowInsetsListener(topBar) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(top = bars.top + dp(12))
            insets
        }

        barcodeScannerView = findViewById(R.id.zxing_barcode_scanner)

        // 彻底关闭 ZXing 默认的粗暴取景框与红激光，呈现纯粹自研 UI
        try {
            barcodeScannerView.viewFinder?.visibility = View.GONE
            barcodeScannerView.statusView?.visibility = View.GONE
        } catch (e: Throwable) {
        }

        // 初始化扫码解码内核
        captureManager = CaptureManager(this, barcodeScannerView)
        captureManager.initializeFromIntent(intent, savedInstanceState)
        captureManager.decode()

        setupControls()
        startLaserAnimation()
    }

    private fun setupControls() {
        // 返回按钮
        findViewById<View>(R.id.btnBack).setOnClickListener {
            finish()
        }

        // 手电筒照明
        val btnTorch = findViewById<View>(R.id.btnTorch)
        val tvTorch = findViewById<TextView>(R.id.tvTorch)
        btnTorch.setOnClickListener {
            isTorchOn = !isTorchOn
            if (isTorchOn) {
                barcodeScannerView.setTorchOn()
                tvTorch.text = "关灯"
            } else {
                barcodeScannerView.setTorchOff()
                tvTorch.text = "照明"
            }
        }

        // 手动输入捷径
        findViewById<View>(R.id.btnManualInput).setOnClickListener {
            val intent = Intent().putExtra("OPEN_MANUAL", true)
            setResult(RESULT_CANCELED, intent)
            finish()
        }
    }

    private fun startLaserAnimation() {
        val laserLine = findViewById<View>(R.id.laserLine)
        laserLine.post {
            val travelDistance = dp(252).toFloat()
            laserAnimator = ObjectAnimator.ofFloat(laserLine, "translationY", 0f, travelDistance).apply {
                duration = 2200L
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        captureManager.onResume()
        laserAnimator?.resume()
    }

    override fun onPause() {
        super.onPause()
        captureManager.onPause()
        laserAnimator?.pause()
    }

    override fun onDestroy() {
        laserAnimator?.cancel()
        captureManager.onDestroy()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        captureManager.onSaveInstanceState(outState)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        captureManager.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return barcodeScannerView.onKeyDown(keyCode, event) || super.onKeyDown(keyCode, event)
    }
}
