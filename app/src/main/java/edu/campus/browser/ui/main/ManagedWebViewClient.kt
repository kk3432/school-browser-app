package edu.campus.browser.ui.main

import android.graphics.Bitmap
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import edu.campus.browser.applaunch.AppLauncher
import edu.campus.browser.applaunch.LaunchResult
import edu.campus.browser.config.AppConfig
import edu.campus.browser.net.UrlRuleMatcher
import edu.campus.browser.ui.AdminSession

/**
 * 受管 WebViewClient：所有页面跳转都过白/黑名单。
 */
class ManagedWebViewClient(
    private val configProvider: () -> AppConfig?,
    private val onBlocked: (url: String) -> Unit,
    private val onMainFrameError: () -> Unit,
    private val onPageStarted: (url: String) -> Unit,
    private val onPageFinished: (url: String) -> Unit,
    private val onRenderGone: () -> Unit
) : WebViewClient() {

    // v0.8.2：规则匹配器按配置实例缓存，配置刷新（config 被替换）时才重建，
    // 避免每次页面跳转都重新解析全部白/黑名单规则与书签。
    private var matcherConfig: AppConfig? = null
    private var matcher: UrlRuleMatcher? = null

    private fun rulesFor(cfg: AppConfig): UrlRuleMatcher {
        if (matcher == null || matcherConfig !== cfg) {
            matcher = UrlRuleMatcher(cfg)
            matcherConfig = cfg
        }
        return matcher!!
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val uri = request.url ?: return true
        val url = uri.toString()
        val scheme = uri.scheme ?: return true

        // 非 http/https：about/data 内部协议放行；其余自定义 scheme 仅允许拉起白名单内应用
        if (scheme != "http" && scheme != "https") {
            if (scheme.equals("about", ignoreCase = true) ||
                scheme.equals("data", ignoreCase = true)
            ) return false
            val cfg = configProvider() ?: return true
            when (AppLauncher.launch(view.context, cfg, url)) {
                LaunchResult.LAUNCHED -> Unit
                LaunchResult.NOT_INSTALLED -> Toast.makeText(
                    view.context, "目标应用未安装", Toast.LENGTH_SHORT).show()
                LaunchResult.NOT_WHITELISTED -> Toast.makeText(
                    view.context, "未允许启动该应用", Toast.LENGTH_SHORT).show()
            }
            return true // 自定义 scheme 一律不交给 WebView 加载
        }
        // 管理员临时无管控模式：放行（仅本次运行有效）
        if (AdminSession.bypassRules) return false

        val config = configProvider() ?: return true
        val allowed = rulesFor(config).isAllowed(url)
        return if (allowed) {
            false
        } else {
            onBlocked(url)
            true // 拦截，不加载
        }
    }

    /**
     * 渲染进程被杀（内存压力下的 OOM）时系统默认只留一个死壳 WebView，表现是“页面点不动”。
     * v0.8.2：返回 true 接管，由宿主重建页面。
     */
    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
        onRenderGone()
        return true
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        onPageStarted(url ?: "")
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        onPageFinished(url ?: "")
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?
    ) {
        if (request?.isForMainFrame == true) onMainFrameError()
    }
}
