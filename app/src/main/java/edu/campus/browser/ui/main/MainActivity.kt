package edu.campus.browser.ui.main

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import edu.campus.browser.applaunch.AppLauncher
import edu.campus.browser.applaunch.LaunchResult
import edu.campus.browser.capture.FrontCameraCapture
import edu.campus.browser.config.AppConfig
import edu.campus.browser.config.ConfigRepository
import edu.campus.browser.databinding.ActivityMainBinding
import edu.campus.browser.net.UrlRuleMatcher
import edu.campus.browser.scan.QrScanner
import edu.campus.browser.ui.AdminSession
import edu.campus.browser.ui.PermissionManager
import edu.campus.browser.ui.setup.SetupActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repo: ConfigRepository
    private lateinit var scanner: QrScanner
    private lateinit var permissionManager: PermissionManager
    private var cameraCapture: FrontCameraCapture? = null
    private var config: AppConfig? = null
    private var pollJob: Job? = null

    private var unlockTaps = 0
    private var lastTapAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = ConfigRepository.get(this)
        config = repo.getCachedConfig()
        if (config == null) {
            // 首次启动且没有任何缓存配置：强制进入设置页，无法进入浏览器
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        permissionManager = PermissionManager(this)
        configureWebView()
        scanner = QrScanner(this)
        setupToolbar()
        setupHiddenUnlock()
        applyImmersive()
        applyAddressBarMode()
        applyScreenshotPolicy()
        updateNavState(config!!.homeUrl)

        binding.swipeRefresh.setOnRefreshListener {
            binding.errorOverlay.isVisible = false
            binding.webView.reload()
        }
        binding.btnRetry.setOnClickListener {
            binding.errorOverlay.isVisible = false
            binding.webView.reload()
        }

        // v0.5.0 启动序列：补传遗留照片 → 先拉一次最新配置（短超时，失败用缓存）→ 按服务端
        // require_startup_photo 决策权限/拍照 → 最后进入浏览器（拍照与页面加载并行，不阻塞浏览）
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { repo.flushPendingPhotos() }
            val fresh = withTimeoutOrNull(STARTUP_CONFIG_TIMEOUT_MS) {
                withContext(Dispatchers.IO) { repo.refresh() }
            }
            if (fresh != null) {
                config = fresh
                applyAddressBarMode()
                applyScreenshotPolicy()
            }
            runStartupGate()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val web = binding.webView
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            allowFileAccess = false
            allowContentAccess = false
        }

        web.webViewClient = ManagedWebViewClient(
            configProvider = { config },
            onBlocked = { url -> runOnUiThread { showBlockedPage(url) } },
            onMainFrameError = { runOnUiThread { showErrorOverlay() } },
            onPageStarted = { url ->
                runOnUiThread {
                    binding.errorOverlay.isVisible = false
                    updateNavState(url)
                }
            },
            onPageFinished = { url ->
                runOnUiThread {
                    binding.swipeRefresh.isRefreshing = false
                    binding.progress.isVisible = false
                    updateNavState(url)
                }
            }
        )

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.progress.progress = newProgress
                binding.progress.isVisible = newProgress in 1..99
            }
        }

        // 禁止下载
        web.setDownloadListener { _, _, _, _, _ ->
            Toast.makeText(this, "已禁止文件下载", Toast.LENGTH_SHORT).show()
        }
        // 禁止长按菜单（复制/在新窗口打开等）
        web.setOnLongClickListener { true }
    }

    /**
     * 顶部工具栏：返回 / 前进 / 地址栏 / 刷新 / 书签 / 首页。
     */
    private fun setupToolbar() {
        binding.btnBack.setOnClickListener {
            if (binding.webView.canGoBack()) binding.webView.goBack()
        }
        binding.btnForward.setOnClickListener {
            if (binding.webView.canGoForward()) binding.webView.goForward()
        }
        binding.btnRefresh.setOnClickListener {
            binding.errorOverlay.isVisible = false
            binding.webView.reload()
        }
        binding.btnHome.setOnClickListener {
            config?.let { navigateTo(it.homeUrl) }
        }
        binding.btnBookmarks.setOnClickListener { showBookmarksDialog() }
        binding.btnScan.setOnClickListener { startScan() }
        binding.btnApps.setOnClickListener { showAppsDialog() }

        // 地址栏仅在临时无管控模式下可编辑，回车访问
        binding.etAddress.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                navigateFromAddressBar()
                true
            } else false
        }
    }

    private fun updateNavState(url: String?) {
        val current = url?.takeIf { it.startsWith("http", ignoreCase = true) }
            ?: binding.webView.url?.takeIf { it.startsWith("http", ignoreCase = true) }
        if (current != null) binding.etAddress.setText(current)
        binding.btnBack.isEnabled = binding.webView.canGoBack()
        binding.btnForward.isEnabled = binding.webView.canGoForward()
    }

    /** 受管模式地址栏只读；临时无管控模式下可输入。 */
    private fun applyAddressBarMode() {
        val editable = AdminSession.bypassRules
        binding.etAddress.apply {
            isFocusable = editable
            isFocusableInTouchMode = editable
            isLongClickable = editable
            isCursorVisible = editable
            hint = if (editable) "输入网址后回车访问" else "受管模式，地址不可编辑"
            if (!editable) clearFocus()
        }
    }

    private fun navigateFromAddressBar() {
        var input = binding.etAddress.text?.toString()?.trim().orEmpty()
        if (input.isBlank()) return
        if (!input.startsWith("http://", ignoreCase = true) &&
            !input.startsWith("https://", ignoreCase = true)
        ) {
            input = "http://$input"
        }
        navigateTo(input)
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.etAddress.windowToken, 0)
        binding.etAddress.clearFocus()
    }

    /** 统一的导航入口：受管模式下先过白/黑名单。 */
    private fun navigateTo(url: String) {
        val current = config ?: return
        if (!AdminSession.bypassRules && !UrlRuleMatcher(current).isAllowed(url)) {
            showBlockedPage(url)
        } else {
            binding.webView.loadUrl(url)
        }
    }

    private fun showBookmarksDialog() {
        val bms = config?.bookmarks.orEmpty()
        if (bms.isEmpty()) {
            Toast.makeText(this, "服务器未配置快捷书签", Toast.LENGTH_SHORT).show()
            return
        }
        val titles = bms.map { it.title.ifBlank { it.url } }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("快捷书签")
            .setItems(titles) { _, which -> navigateTo(bms[which].url) }
            .setNegativeButton("关闭", null)
            .show()
    }

    // ---------- 扫码 ----------

    private fun startScan() {
        scanner.start { contents ->
            if (contents.isNullOrBlank()) return@start // 取消/拒绝权限，不提示
            handleScanResult(contents.trim())
        }
    }

    /** 扫码结果只接受 http/https 网址与 app:// 拉起，其余一律拒绝。 */
    private fun handleScanResult(content: String) {
        when {
            content.startsWith("http://", ignoreCase = true) ||
                content.startsWith("https://", ignoreCase = true) -> navigateTo(content)
            content.startsWith("app://", ignoreCase = true) -> launchAppUrl(content)
            else -> Toast.makeText(this, "无法识别：仅支持网址或应用二维码", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- 唤醒其他应用 ----------

    private fun launchAppUrl(url: String) {
        val cfg = config ?: return
        when (AppLauncher.launch(this, cfg, url)) {
            LaunchResult.LAUNCHED -> Unit
            LaunchResult.NOT_INSTALLED ->
                Toast.makeText(this, "目标应用未安装", Toast.LENGTH_SHORT).show()
            LaunchResult.NOT_WHITELISTED ->
                Toast.makeText(this, "服务器未允许启动该应用", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAppsDialog() {
        val apps = config?.allowedApps.orEmpty()
        if (apps.isEmpty()) {
            Toast.makeText(this, "服务器未配置可启动的应用", Toast.LENGTH_SHORT).show()
            return
        }
        val titles = apps.map { it.label.ifBlank { it.packageName } }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("应用")
            .setItems(titles) { _, which ->
                launchAppUrl("app://${apps[which].packageName}")
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    // ---------- 截屏限制 ----------

    /**
     * 全局截屏/录屏开关，由服务端 block_screenshot 控制：
     * 开启时加 FLAG_SECURE，系统截屏得到黑屏、录屏黑屏、最近任务缩略图也被遮蔽。
     */
    private fun applyScreenshotPolicy() {
        if (config?.blockScreenshot == true) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun startPolling() {
        val intervalSeconds = (config?.updateIntervalSeconds ?: 300).coerceAtLeast(30)
        pollJob = lifecycleScope.launch {
            while (isActive) {
                delay(intervalSeconds * 1000L)
                val newConfig = withContext(Dispatchers.IO) { repo.refresh() }
                if (newConfig != null) {
                    config = newConfig
                    AdminSession.bypassRules = false
                    applyAddressBarMode()
                    applyScreenshotPolicy()
                    Toast.makeText(
                        this@MainActivity,
                        "配置已更新到 v${newConfig.version}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    /**
     * 隐藏的管理员入口：工具栏最左侧 Logo，3 秒内连点 5 次。
     */
    private fun setupHiddenUnlock() {
        binding.unlockHotspot.setOnClickListener {
            // 服务端关闭隐藏入口后：点击无任何反应、无提示，只能由服务器重新开启
            if (config?.hiddenEntryEnabled != true) return@setOnClickListener
            val now = System.currentTimeMillis()
            if (now - lastTapAt > 3000) unlockTaps = 0
            lastTapAt = now
            if (++unlockTaps >= 5) {
                unlockTaps = 0
                val current = config ?: return@setOnClickListener
                AdminUnlockDialog.show(this, current) { action ->
                    when (action) {
                        AdminUnlockDialog.Action.CHANGE_SERVER ->
                            startActivity(Intent(this, SetupActivity::class.java))
                        AdminUnlockDialog.Action.TEMP_UNLOCK -> {
                            AdminSession.bypassRules = true
                            applyAddressBarMode()
                            Toast.makeText(
                                this,
                                "临时无管控已开启，重启 APP 后自动恢复",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
        }
    }

    private fun showBlockedPage(url: String) {
        val html = """
            <html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">
            <style>
              *{margin:0;padding:0;box-sizing:border-box;
                font-family:-apple-system,'PingFang SC','Microsoft YaHei',sans-serif;}
              body{background:#F4F7FB;display:flex;align-items:center;
                justify-content:center;min-height:100vh;padding:24px;}
              .card{background:#fff;border-radius:20px;width:100%;max-width:520px;
                padding:38px 28px 30px;text-align:center;
                box-shadow:0 10px 34px rgba(27,122,224,.10);}
              .icon{width:66px;height:66px;border-radius:50%;background:#FDE3E3;
                margin:0 auto 20px;position:relative;}
              .icon:before{content:'';position:absolute;left:50%;top:50%;width:30px;height:30px;
                transform:translate(-50%,-50%);border:3.5px solid #D72C2C;border-radius:50%;}
              .icon:after{content:'';position:absolute;left:50%;top:50%;width:34px;height:3.5px;
                background:#D72C2C;transform:translate(-50%,-50%) rotate(45deg);border-radius:2px;}
              h2{color:#17263A;font-size:20px;margin-bottom:10px;}
              p{color:#55667A;font-size:14px;line-height:1.7;}
              .url{margin-top:16px;background:#F4F7FB;border-radius:10px;padding:10px 12px;
                font-size:12px;color:#8A99AC;word-break:break-all;}
              .tip{margin-top:18px;font-size:12px;color:#9AA9BA;letter-spacing:.5px;}
            </style></head>
            <body><div class="card">
              <div class="icon"></div>
              <h2>访问受限</h2>
              <p>该网址不在学校允许访问的范围内。<br>如需访问教学资源，请联系老师加入书签或白名单。</p>
              <div class="url">$url</div>
              <div class="tip">校园浏览器 · 受管模式</div>
            </div></body></html>
        """.trimIndent()
        binding.webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    private fun showErrorOverlay() {
        binding.swipeRefresh.isRefreshing = false
        binding.progress.isVisible = false
        binding.errorOverlay.isVisible = true
    }

    private fun applyImmersive() {
        if (config?.kiosk != true) return
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersive()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        }
        // 受管浏览器：不允许返回键退出
    }

    // ---------- v0.5.0 启动门控：拍照要求与权限 ----------

    /**
     * 依据最新配置决定是否执行"启动前置拍照"。
     * 服务端 require_startup_photo=true：先说明用途 → 申请相机权限 → 被拒则阻断进入（不可取消）；
     * 为 false：直接进入浏览器（权限留给扫码时按需申请）。
     */
    private fun runStartupGate() {
        val cfg = config ?: return
        if (!cfg.requireStartupPhoto) {
            enterBrowser()
            return
        }
        val missing = PermissionManager.missing(this)
        if (missing.isNotEmpty()) {
            PermissionManager.showRationale(
                this,
                "需要相机权限",
                "学校要求使用平板前进行身份拍照确认。照片仅上传到学校服务器，用于考勤管理，不对外发送。",
                onProceed = { requestCameraPermission() },
                onCancel = { showPermissionBlocked() }
            )
        } else {
            doStartupPhoto()
        }
    }

    private fun requestCameraPermission() {
        permissionManager.request { granted ->
            if (granted) doStartupPhoto() else showPermissionBlocked()
        }
    }

    /** 服务端要求拍照但相机未授权：阻断进入浏览器，仅提供重试授权或退出。 */
    private fun showPermissionBlocked() {
        MaterialAlertDialogBuilder(this)
            .setTitle("无法进入浏览器")
            .setMessage("学校要求启动时使用相机拍照确认身份。未授权相机权限将无法使用本浏览器。")
            .setCancelable(false)
            .setPositiveButton("重新授权") { _, _ -> requestCameraPermission() }
            .setNegativeButton("退出") { _, _ -> finish() }
            .show()
    }

    /** 前置拍照并上传：无前置则上报跳过（不阻断）；拍照/上传失败缓存待下次补传（不阻断）。 */
    private fun doStartupPhoto() {
        val capture = FrontCameraCapture(this, this)
        cameraCapture = capture
        lifecycleScope.launch {
            if (!capture.hasFrontCamera()) {
                withContext(Dispatchers.IO) { repo.uploadPhotoSkip("no_front_camera") }
                capture.close()
                enterBrowser()
                return@launch
            }
            val jpeg = capture.captureJpeg()
            if (jpeg != null) {
                val uploaded = withContext(Dispatchers.IO) { repo.uploadPhoto(jpeg) }
                if (!uploaded) repo.savePendingPhoto(jpeg)
            }
            // 拍照失败（相机被占用等）不阻断，下次启动再试
            capture.close()
            enterBrowser()
        }
    }

    private fun enterBrowser() {
        val cfg = config ?: return
        // loadUrl 由宿主发起时不触发 shouldOverrideUrlLoading，首页需主动校验一次
        navigateTo(cfg.homeUrl)
        startPolling()
    }

    override fun onDestroy() {
        pollJob?.cancel()
        cameraCapture?.close()
        runCatching {
            binding.webView.stopLoading()
            binding.webView.destroy()
        }
        super.onDestroy()
    }

    companion object {
        /** 启动时先拉最新配置的等待上限；服务器不可达时回退本地缓存，避免卡住启动。 */
        private const val STARTUP_CONFIG_TIMEOUT_MS = 5000L
    }
}
