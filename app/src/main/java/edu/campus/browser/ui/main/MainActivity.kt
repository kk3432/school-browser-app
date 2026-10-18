package edu.campus.browser.ui.main

import android.annotation.SuppressLint
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import edu.campus.browser.R
import edu.campus.browser.applaunch.AppLauncher
import edu.campus.browser.applaunch.BookmarkShortcut
import edu.campus.browser.applaunch.LaunchResult
import edu.campus.browser.capture.FrontCameraCapture
import edu.campus.browser.config.AppConfig
import edu.campus.browser.config.ConfigRepository
import edu.campus.browser.databinding.ActivityMainBinding
import edu.campus.browser.databinding.DialogListBinding
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

/**
 * 主界面（受管浏览器外壳）。
 * v0.9.0 界面改造（APP界面优化规划.md P0/P1）：冷启动加载层、全局状态条、错误页分类文案与 URL、
 * 拦截页原生覆盖层、原生书签/应用对话框、隐藏入口圆点进度、导航禁用态、kiosk 禁下拉刷新。
 * 白/黑名单判定、签名校验、拍照与轮询的业务语义保持不变。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repo: ConfigRepository
    private lateinit var scanner: QrScanner
    private lateinit var permissionManager: PermissionManager
    private var cameraCapture: FrontCameraCapture? = null
    private var config: AppConfig? = null
    private var pollJob: Job? = null

    /** 桌面快捷方式带进来的 URL（v0.8.0）。 */
    private var pendingShortcutUrl: String? = null

    /** v0.8.2：启动阶段已提前 loadUrl 的地址，进入浏览器时不再重复加载。 */
    private var preloadedUrl: String? = null

    // v0.8.2：规则匹配器按配置实例缓存，配置刷新时才重建。
    private var matcherCacheConfig: AppConfig? = null
    private var matcherCache: UrlRuleMatcher? = null

    private fun rulesFor(cfg: AppConfig): UrlRuleMatcher {
        val cached = matcherCache
        if (cached != null && matcherCacheConfig === cfg) return cached
        val built = UrlRuleMatcher(cfg)
        matcherCacheConfig = cfg
        matcherCache = built
        return built
    }

    private var unlockTaps = 0
    private var lastTapAt = 0L
    private var unlockDotsHideJob: Job? = null
    private var statusHideJob: Job? = null
    private var progressJob: Job? = null

    /** 加载层是否仍在显示（首帧 + 启动门控都完成后才淡出）。 */
    private var startupLoading = true
    private var firstPageFinished = false
    private var browserEntered = false
    private var lastRetryAt = 0L

    /** 中转 WebView 的延迟销毁队列（见 onCreateWindow）。 */
    private val probeReaper = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = ConfigRepository.get(this)
        pendingShortcutUrl = BookmarkShortcut.urlFromIntent(intent)
        config = repo.getCachedConfig()
        if (config == null) {
            // 首次启动且没有任何缓存配置：强制进入设置页，无法进入浏览器
            startActivity(Intent(this, SetupActivity::class.java))
            @Suppress("DEPRECATION")
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 冷启动第一帧就显示加载层，避免 WebView 首帧前的白屏（P1-1）
        binding.loadingOverlay.isVisible = true
        binding.loadingOverlay.alpha = 1f
        startupLoading = true
        setLoadingStatus(R.string.loading_portal)

        permissionManager = PermissionManager(this)
        configureWebView()
        scanner = QrScanner(this)
        setupToolbar()
        setupHiddenUnlock()
        applyImmersive()
        applyAddressBarMode()
        applyScreenshotPolicy()
        refreshSwipePolicy()
        updateNavState(config!!.homeUrl)

        binding.btnStatusClose.setOnClickListener { hideStatus() }
        binding.btnErrorHome.setOnClickListener { goHome() }
        binding.blockedOverlay.btnBlockedHome.setOnClickListener { goHome() }
        binding.blockedOverlay.btnBlockedReload.setOnClickListener { reloadPage() }

        binding.swipeRefresh.setOnRefreshListener {
            binding.errorOverlay.isVisible = false
            binding.blockedOverlay.root.isVisible = false
            binding.webView.reload()
        }
        binding.btnRetry.setOnClickListener { retryPage() }

        // 启动序列：立刻用缓存配置加载首页；并行补传遗留照片 + 拉最新配置；拍照门控不阻塞页面。
        val entryUrl = pendingShortcutUrl
        pendingShortcutUrl = null
        val startUrl = entryUrl ?: config!!.homeUrl
        preloadedUrl = startUrl
        // loadUrl 由宿主发起时不触发 shouldOverrideUrlLoading，首页也必须主动过白/黑名单
        navigateTo(startUrl)

        lifecycleScope.launch {
            launch(Dispatchers.IO) { repo.flushPendingPhotos() }
            if (startupLoading) setLoadingStatus(R.string.loading_config)
            val fresh = withTimeoutOrNull(STARTUP_CONFIG_TIMEOUT_MS) {
                withContext(Dispatchers.IO) { repo.refresh() }
            }
            if (fresh != null) {
                config = fresh
                applyAddressBarMode()
                applyScreenshotPolicy()
                refreshSwipePolicy()
            }
            if (entryUrl != null) {
                showStatus(getString(R.string.status_shortcut))
                enterBrowser()
            } else {
                runStartupGate()
            }
        }
    }

    /** 桌面快捷方式复用已存在的实例时（launchMode=singleTask）走这里。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val url = BookmarkShortcut.urlFromIntent(intent) ?: return
        if (config == null) {
            pendingShortcutUrl = url
            return
        }
        navigateTo(url)
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
            allowFileAccess = false
            allowContentAccess = false
            useWideViewPort = true
            loadWithOverviewMode = true
            layoutAlgorithm = WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING
            textZoom = 100
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        }

        web.webViewClient = ManagedWebViewClient(
            configProvider = { config },
            onBlocked = { url -> runOnUiThread { showBlockedPage(url) } },
            onMainFrameError = { url, code, desc ->
                runOnUiThread { showErrorOverlay(url, code, desc) }
            },
            onHttpError = { status, _ ->
                runOnUiThread { showStatus(getString(R.string.error_http, status)) }
            },
            onPageStarted = { url ->
                runOnUiThread {
                    binding.errorOverlay.isVisible = false
                    binding.blockedOverlay.root.isVisible = false
                    ViewportInjector.reset()
                    updateNavState(url)
                    scheduleProgress()
                }
            },
            onPageFinished = { url ->
                runOnUiThread {
                    firstPageFinished = true
                    binding.swipeRefresh.isRefreshing = false
                    progressJob?.cancel()
                    binding.progress.isVisible = false
                    updateNavState(url)
                    ViewportInjector.injectIfMissing(binding.webView, url)
                    maybeHideLoading()
                }
            },
            onRenderGone = { runOnUiThread { recoverFromRenderGone() } },
            onNotify = { message -> runOnUiThread { showStatus(message) } }
        )

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (binding.progress.progress != newProgress) {
                    binding.progress.progress = newProgress
                }
            }

            /** target=_blank / window.open：在当前 WebView 直接打开，仍走白名单。 */
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                val parent = view ?: return false
                val probe = WebView(parent.context)
                probe.webViewClient = object : android.webkit.WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        v: WebView?,
                        request: android.webkit.WebResourceRequest?
                    ): Boolean {
                        val target = request?.url?.toString() ?: return true
                        navigateTo(target)
                        v?.let { disposeProbe(it) }
                        return true
                    }
                }
                (resultMsg?.obj as? WebView.WebViewTransport)?.webView = probe
                resultMsg?.sendToTarget()
                return true
            }
        }

        web.setDownloadListener { _, _, _, _, _ ->
            showStatus(getString(R.string.status_download_blocked))
        }
        web.setOnLongClickListener { true }
    }

    // ---------- 加载层 / 状态条 / 进度条 ----------

    private fun setLoadingStatus(@StringRes textRes: Int) {
        binding.tvLoadingStatus.setText(textRes)
    }

    /** 首帧与启动门控都完成后，加载层淡出。 */
    private fun maybeHideLoading() {
        if (!startupLoading) return
        if (!firstPageFinished || !browserEntered) return
        hideLoading()
    }

    private fun hideLoading() {
        if (!startupLoading) return
        startupLoading = false
        val view = binding.loadingOverlay
        if (!view.isVisible) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ValueAnimator.areAnimatorsEnabled()) {
            view.isVisible = false
            return
        }
        view.animate()
            .alpha(0f)
            .setDuration(160L)
            .withEndAction {
                view.isVisible = false
                view.alpha = 1f
            }
            .start()
    }

    /** 全局状态条：3 秒后自动淡出，只保留最新一条。 */
    private fun showStatus(message: String) {
        val bar = binding.statusBar
        binding.tvStatus.text = message
        statusHideJob?.cancel()
        if (!bar.isVisible) {
            bar.alpha = 0f
            bar.isVisible = true
            bar.animate().alpha(1f).setDuration(160L).start()
        }
        statusHideJob = lifecycleScope.launch {
            delay(STATUS_AUTO_HIDE_MS)
            hideStatus()
        }
    }

    private fun hideStatus() {
        statusHideJob?.cancel()
        val bar = binding.statusBar
        if (!bar.isVisible) return
        bar.animate()
            .alpha(0f)
            .setDuration(160L)
            .withEndAction {
                bar.isVisible = false
                bar.alpha = 1f
            }
            .start()
    }

    /** 进度条延迟 300ms 出现，避免快速页面闪烁。 */
    private fun scheduleProgress() {
        progressJob?.cancel()
        binding.progress.isVisible = false
        if (startupLoading) return
        progressJob = lifecycleScope.launch {
            delay(PROGRESS_DELAY_MS)
            binding.progress.isVisible = true
        }
    }

    // ---------- 工具栏 ----------

    private fun setupToolbar() {
        binding.btnBack.setOnClickListener {
            if (binding.webView.canGoBack()) binding.webView.goBack()
        }
        binding.btnForward.setOnClickListener {
            if (binding.webView.canGoForward()) binding.webView.goForward()
        }
        binding.btnRefresh.setOnClickListener { reloadPage() }
        binding.btnHome.setOnClickListener {
            val home = config?.homeUrl ?: return@setOnClickListener
            val current = binding.webView.url.orEmpty()
            if (current == home) showStatus(getString(R.string.status_home_already)) else navigateTo(home)
        }
        binding.btnBookmarks.setOnClickListener { showBookmarksDialog() }
        binding.btnScan.setOnClickListener { startScan() }
        binding.btnApps.setOnClickListener { showAppsDialog() }

        // 受管模式下地址栏只读：点击给一次轻提示（P1-5）
        binding.addressBar.setOnClickListener { view ->
            if (AdminSession.bypassRules) return@setOnClickListener
            showStatus(getString(R.string.address_readonly_tip))
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }

        binding.etAddress.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                navigateFromAddressBar()
                true
            } else false
        }
    }

    /**
     * 销毁 onCreateWindow 用的中转 WebView：
     * 它只是把 window.open 的目标网址转交给主 WebView 的载体，不做渲染，用完即弃。
     * 延迟到本轮消息循环之后再销毁，避免在 shouldOverrideUrlLoading 回调栈里同步 destroy。
     */
    private fun disposeProbe(probe: WebView) {
        probe.stopLoading()
        probeReaper.post {
            runCatching { probe.destroy() }
        }
    }

    private fun updateNavState(url: String?) {
        val current = url?.takeIf { it.startsWith("http", ignoreCase = true) }
            ?: binding.webView.url?.takeIf { it.startsWith("http", ignoreCase = true) }
        if (current != null) binding.etAddress.setText(current)
        binding.btnBack.isEnabled = binding.webView.canGoBack()
        binding.btnForward.isEnabled = binding.webView.canGoForward()
    }

    /** 受管模式地址栏只读；临时无管控模式下可输入。同时同步下拉刷新与地址栏图标。 */
    private fun applyAddressBarMode() {
        val editable = AdminSession.bypassRules
        binding.etAddress.apply {
            isFocusable = editable
            isFocusableInTouchMode = editable
            isLongClickable = editable
            isCursorVisible = editable
            hint = getString(
                if (editable) R.string.address_hint_editable else R.string.address_hint_readonly
            )
            if (!editable) clearFocus()
        }
        binding.ivAddressIcon.setImageResource(
            if (editable) R.drawable.ic_public else R.drawable.ic_lock
        )
        refreshSwipePolicy()
    }

    /** kiosk 模式下禁用下拉刷新（管理员临时无管控时仍可下拉），刷新按钮始终可用。 */
    private fun refreshSwipePolicy() {
        val kiosk = config?.kiosk == true
        binding.swipeRefresh.isEnabled = !kiosk || AdminSession.bypassRules
    }

    private fun navigateFromAddressBar() {
        var input = binding.etAddress.text?.toString()?.trim().orEmpty()
        if (input.isBlank()) return
        if (!input.startsWith("http://", ignoreCase = true) &&
            !input.startsWith("https://", ignoreCase = true)
        ) {
            input = "http://" + input
        }
        navigateTo(input)
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.etAddress.windowToken, 0)
        binding.etAddress.clearFocus()
    }

    /** 统一的导航入口：受管模式下先过白/黑名单。 */
    private fun navigateTo(url: String) {
        val current = config ?: return
        if (!AdminSession.bypassRules && !rulesFor(current).isAllowed(url)) {
            showBlockedPage(url)
        } else {
            binding.webView.loadUrl(url)
        }
    }

    /** 重试（防抖 1 秒，P0-6）。 */
    private fun retryPage() {
        val now = System.currentTimeMillis()
        if (now - lastRetryAt < 1000L) return
        lastRetryAt = now
        binding.errorOverlay.isVisible = false
        binding.blockedOverlay.root.isVisible = false
        binding.webView.reload()
    }

    private fun reloadPage() {
        binding.errorOverlay.isVisible = false
        binding.blockedOverlay.root.isVisible = false
        binding.webView.reload()
    }

    private fun goHome() {
        binding.errorOverlay.isVisible = false
        binding.blockedOverlay.root.isVisible = false
        val home = config?.homeUrl ?: return
        navigateTo(home)
    }

    // ---------- 书签 / 应用（P1-2：原生列表 + 空态） ----------

    private fun showBookmarksDialog() {
        val bms = config?.bookmarks.orEmpty()
        val layout = DialogListBinding.inflate(layoutInflater)
        layout.dialogTitle.text = getString(R.string.bookmarks_title)
        layout.dialogSubtitle.isVisible = bms.isNotEmpty()
        layout.dialogSubtitle.text = getString(R.string.bookmarks_subtitle_long_press)

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(layout.root)
            .setNegativeButton(R.string.close, null)
            .create()

        if (bms.isEmpty()) {
            showEmptyState(
                layout = layout,
                iconRes = R.drawable.ic_bookmark,
                textRes = R.string.bookmarks_empty,
                hintRes = R.string.bookmarks_empty_hint
            )
            dialog.show()
            return
        }

        bms.forEach { bookmark ->
            val row = buildListRow(
                text = bookmark.title.ifBlank { bookmark.url },
                iconRes = if (isCampusPage(bookmark.url)) R.drawable.ic_school else null
            )
            row.setOnClickListener {
                dialog.dismiss()
                navigateTo(bookmark.url)
            }
            row.setOnLongClickListener {
                showBookmarkActions(bookmark)
                true
            }
            layout.dialogListContainer.addView(row)
        }
        dialog.show()
    }

    /** 判断是否服务端内置 HTML 页（/h/xxx），用于在书签列表里做标记。 */
    private fun isCampusPage(url: String): Boolean = url.contains("/h/")

    private fun buildListRow(text: String, iconRes: Int?): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = resources.getDimensionPixelSize(R.dimen.list_row_min)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            val outValue = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            setBackgroundResource(outValue.resourceId)
            isClickable = true
            isFocusable = true
            setPadding(dp(8), 0, dp(8), 0)
        }
        if (iconRes != null) {
            val icon = ImageView(this)
            icon.setImageResource(iconRes)
            icon.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.primary)
            )
            icon.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            val lp = LinearLayout.LayoutParams(dp(18), dp(18))
            lp.marginEnd = dp(8)
            row.addView(icon, lp)
        }
        val label = TextView(this)
        label.text = text
        label.maxLines = 2
        label.ellipsize = android.text.TextUtils.TruncateAt.END
        label.setTextColor(ContextCompat.getColor(this, R.color.on_surface))
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_body))
        row.addView(
            label,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        return row
    }

    private fun showEmptyState(
        layout: DialogListBinding,
        iconRes: Int,
        @StringRes textRes: Int,
        @StringRes hintRes: Int
    ) {
        layout.dialogEmpty.isVisible = true
        layout.dialogEmptyIcon.setImageResource(iconRes)
        layout.dialogEmptyIcon.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, R.color.text_disabled)
        )
        layout.dialogEmptyText.setText(textRes)
        layout.dialogEmptyHint.setText(hintRes)
    }

    /** 书签长按菜单：打开 / 添加到桌面。 */
    private fun showBookmarkActions(bookmark: edu.campus.browser.config.Bookmark) {
        val label = bookmark.title.ifBlank { bookmark.url }
        MaterialAlertDialogBuilder(this)
            .setTitle(label)
            .setMessage(bookmark.url)
            .setItems(arrayOf(getString(R.string.open), getString(R.string.add_to_home))) { _, which ->
                when (which) {
                    0 -> navigateTo(bookmark.url)
                    1 -> addBookmarkToHome(bookmark)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 把书签钉到桌面（v0.8.0）：成功与否都走状态条提示，不同 ROM 行为差异较大。 */
    private fun addBookmarkToHome(bookmark: edu.campus.browser.config.Bookmark) {
        BookmarkShortcut.pin(this, bookmark.title, bookmark.url) { _, message ->
            showStatus(message)
        }
    }

    // ---------- 扫码 / 唤醒其他应用 ----------

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
            else -> showStatus(getString(R.string.scan_invalid))
        }
    }

    private fun launchAppUrl(url: String) {
        val cfg = config ?: return
        when (AppLauncher.launch(this, cfg, url)) {
            LaunchResult.LAUNCHED -> Unit
            LaunchResult.NOT_INSTALLED -> showStatus(getString(R.string.app_not_installed))
            LaunchResult.NOT_WHITELISTED -> showStatus(getString(R.string.app_not_allowed))
        }
    }

    private fun showAppsDialog() {
        val apps = config?.allowedApps.orEmpty()
        val layout = DialogListBinding.inflate(layoutInflater)
        layout.dialogTitle.text = getString(R.string.apps_title)

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(layout.root)
            .setNegativeButton(R.string.close, null)
            .create()

        if (apps.isEmpty()) {
            showEmptyState(
                layout = layout,
                iconRes = R.drawable.ic_apps,
                textRes = R.string.apps_empty,
                hintRes = R.string.apps_empty_hint
            )
            dialog.show()
            return
        }

        apps.forEach { app ->
            val row = buildListRow(text = app.label.ifBlank { app.packageName }, iconRes = null)
            row.setOnClickListener {
                dialog.dismiss()
                launchAppUrl("app://" + app.packageName)
            }
            layout.dialogListContainer.addView(row)
        }
        dialog.show()
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
        if (pollJob?.isActive == true) return
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
                    showStatus(getString(R.string.status_config_updated, newConfig.version))
                }
            }
        }
    }

    /**
     * 隐藏的管理员入口：工具栏最左侧 Logo，3 秒内连点 5 次。
     * v0.9.0：第 2~5 次点击在 Logo 下方显示圆点进度（规划 5.4）。
     */
    private fun setupHiddenUnlock() {
        binding.unlockHotspot.setOnClickListener {
            val cfg = config
            // 服务端关闭隐藏入口后：不再静默，用状态条说明
            if (cfg?.hiddenEntryEnabled != true) {
                showStatus(getString(R.string.status_hidden_disabled))
                return@setOnClickListener
            }
            val now = System.currentTimeMillis()
            if (now - lastTapAt > UNLOCK_WINDOW_MS) unlockTaps = 0
            lastTapAt = now
            unlockTaps++
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            if (unlockTaps >= 5) {
                unlockTaps = 0
                hideUnlockDots()
                it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                AdminUnlockDialog.show(this, cfg) { action ->
                    when (action) {
                        AdminUnlockDialog.Action.CHANGE_SERVER -> {
                            startActivity(Intent(this, SetupActivity::class.java))
                            @Suppress("DEPRECATION")
                            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                        }
                        AdminUnlockDialog.Action.TEMP_UNLOCK -> {
                            AdminSession.bypassRules = true
                            applyAddressBarMode()
                            showStatus(getString(R.string.status_temp_unlock))
                        }
                    }
                }
            } else if (unlockTaps >= 2) {
                showUnlockDots(unlockTaps)
            }
        }
    }

    private fun showUnlockDots(done: Int) {
        val dots = binding.unlockDots
        dots.isVisible = true
        for (i in 0 until dots.childCount) {
            val child = dots.getChildAt(i)
            val color = if (i < done) R.color.primary else R.color.outline
            ViewCompat.setBackgroundTintList(
                child,
                ColorStateList.valueOf(ContextCompat.getColor(this, color))
            )
        }
        unlockDotsHideJob?.cancel()
        unlockDotsHideJob = lifecycleScope.launch {
            delay(UNLOCK_DOTS_HIDE_MS)
            hideUnlockDots()
        }
    }

    private fun hideUnlockDots() {
        unlockDotsHideJob?.cancel()
        binding.unlockDots.isVisible = false
    }

    // ---------- 拦截页 / 错误页 ----------

    /** 访问受限：原生覆盖层替代 WebView 内联 HTML（P1-4）。 */
    private fun showBlockedPage(url: String) {
        binding.blockedOverlay.tvBlockedUrl.text = url
        binding.blockedOverlay.root.isVisible = true
        binding.swipeRefresh.isRefreshing = false
        progressJob?.cancel()
        binding.progress.isVisible = false
        hideLoading()
    }

    private fun showErrorOverlay(url: String?, code: Int, description: String?) {
        val spec = classifyError(code)
        binding.ivErrorIcon.setImageResource(spec.iconRes)
        binding.ivErrorIcon.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, spec.tintRes)
        )
        binding.tvErrorTitle.setText(spec.titleRes)
        binding.tvErrorMessage.setText(spec.messageRes)
        binding.tvErrorUrl.isVisible = !url.isNullOrBlank()
        binding.tvErrorUrl.text = url.orEmpty()
        if (code != 0) {
            binding.tvErrorTechnical.isVisible = true
            binding.tvErrorTechnical.text =
                getString(R.string.error_technical, code, description.orEmpty())
        } else {
            binding.tvErrorTechnical.isVisible = false
        }
        binding.swipeRefresh.isRefreshing = false
        progressJob?.cancel()
        binding.progress.isVisible = false
        hideLoading()
        binding.errorOverlay.isVisible = true
        // 无障碍：错误层弹出时把焦点移进来并播报标题（P1-7）
        binding.errorOverlay.requestFocus()
        binding.errorOverlay.announceForAccessibility(getString(spec.titleRes))
    }

    private data class ErrorSpec(
        @StringRes val titleRes: Int,
        @StringRes val messageRes: Int,
        val iconRes: Int,
        val tintRes: Int
    )

    private fun classifyError(code: Int): ErrorSpec = when (code) {
        android.webkit.WebViewClient.ERROR_TIMEOUT -> ErrorSpec(
            R.string.error_timeout, R.string.error_hint, R.drawable.ic_wifi_off, R.color.error
        )
        android.webkit.WebViewClient.ERROR_HOST_LOOKUP,
        android.webkit.WebViewClient.ERROR_CONNECT,
        android.webkit.WebViewClient.ERROR_IO -> ErrorSpec(
            R.string.error_network, R.string.error_hint, R.drawable.ic_wifi_off, R.color.error
        )
        android.webkit.WebViewClient.ERROR_FAILED_SSL_HANDSHAKE -> ErrorSpec(
            R.string.error_ssl, R.string.error_hint, R.drawable.ic_lock, R.color.warning
        )
        else -> ErrorSpec(
            R.string.error_unknown, R.string.error_hint, R.drawable.ic_warning, R.color.error
        )
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
        // 受管浏览器：不允许返回键退出；先关闭覆盖层，再依次回退历史
        if (binding.blockedOverlay.root.isVisible) {
            binding.blockedOverlay.root.isVisible = false
            goHome()
            showStatus(getString(R.string.status_blocked_exit))
            return
        }
        if (binding.errorOverlay.isVisible) {
            goHome()
            return
        }
        if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        }
    }

    // ---------- 启动门控：拍照要求与权限 ----------

    /**
     * 依据最新配置决定是否执行"启动前置拍照"。
     * 服务端 require_startup_photo=true：先说明用途 → 申请相机权限 → 被拒则阻断进入（不可取消）。
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
                getString(R.string.permission_title),
                getString(R.string.permission_rationale),
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
            .setTitle(getString(R.string.permission_blocked_title))
            .setMessage(getString(R.string.permission_blocked_message))
            .setCancelable(false)
            .setPositiveButton(getString(R.string.permission_retry)) { _, _ -> requestCameraPermission() }
            .setNegativeButton(getString(R.string.exit)) { _, _ -> finish() }
            .show()
    }

    /** 前置拍照并上传：无前置则上报跳过（不阻断）；拍照/上传失败缓存待下次补传（不阻断）。 */
    private fun doStartupPhoto() {
        // 加载层文案切到「正在核验设备…」，绝不暴露拍照事实（规划 5.7）
        if (startupLoading) {
            setLoadingStatus(R.string.loading_device)
            binding.tvLoadingHint.isVisible = true
        }
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
        val shortcut = pendingShortcutUrl
        pendingShortcutUrl = null
        val target = shortcut ?: cfg.homeUrl
        // v0.8.2：启动时已提前加载过同一地址，这里不再重复 loadUrl（避免二次刷新闪烁）
        if (preloadedUrl != target) {
            navigateTo(target)
        }
        preloadedUrl = null
        browserEntered = true
        maybeHideLoading()
        startPolling()
    }

    /** 渲染进程被杀（OOM）后的恢复：重建成 Activity，走正常启动序列重新加载页面（v0.8.2）。 */
    private fun recoverFromRenderGone() {
        showStatus(getString(R.string.status_render_recovered))
        runCatching {
            binding.webView.stopLoading()
            (binding.webView.parent as? ViewGroup)?.removeView(binding.webView)
            binding.webView.destroy()
        }
        recreate()
    }

    override fun onPause() {
        super.onPause()
        if (config == null) return
        // v0.8.2：进入后台就停掉配置轮询与 WebView 定时器/JS，回前台再恢复
        pollJob?.cancel()
        runCatching {
            binding.webView.onPause()
            binding.webView.pauseTimers()
        }
    }

    override fun onResume() {
        super.onResume()
        if (config == null) return
        runCatching {
            binding.webView.resumeTimers()
            binding.webView.onResume()
        }
        if (pollJob?.isActive != true) startPolling()
    }

    override fun onDestroy() {
        pollJob?.cancel()
        cameraCapture?.close()
        runCatching {
            binding.webView.stopLoading()
            (binding.webView.parent as? ViewGroup)?.removeView(binding.webView)
            binding.webView.destroy()
        }
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        /** 启动时先拉最新配置的等待上限；服务器不可达时回退本地缓存，避免卡住启动。 */
        private const val STARTUP_CONFIG_TIMEOUT_MS = 5000L

        /** 状态条自动隐藏时间。 */
        private const val STATUS_AUTO_HIDE_MS = 3000L

        /** 进度条延迟出现，避免快速页面闪烁。 */
        private const val PROGRESS_DELAY_MS = 300L

        /** 隐藏入口连点窗口。 */
        private const val UNLOCK_WINDOW_MS = 3000L

        /** 隐藏入口圆点进度自动隐藏时间。 */
        private const val UNLOCK_DOTS_HIDE_MS = 3000L
    }
}
