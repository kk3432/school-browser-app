package edu.campus.browser.ui.main

import android.graphics.Bitmap
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import edu.campus.browser.R
import edu.campus.browser.applaunch.AppLauncher
import edu.campus.browser.applaunch.LaunchResult
import edu.campus.browser.config.AppConfig
import edu.campus.browser.net.UrlRuleMatcher
import edu.campus.browser.ui.AdminSession

/**
 * 受管 WebViewClient：所有页面跳转都过白/黑名单。
 *
 * v0.9.0：
 * - 顶层页面错误按 errorCode 分类上报（原文案统一为「页面加载失败」）；
 * - 新增 onReceivedHttpError（4xx/5xx 只提示，不拦截页面）；
 * - 拉起应用失败的提示改走状态条回调 [onNotify]，不再直接弹 Toast。
 */
class ManagedWebViewClient(
    private val configProvider: () -> AppConfig?,
    private val onBlocked: (url: String) -> Unit,
    private val onMainFrameError: (url: String?, errorCode: Int, description: String?) -> Unit,
    private val onHttpError: (statusCode: Int, url: String?) -> Unit,
    private val onPageStarted: (url: String) -> Unit,
    private val onPageFinished: (url: String) -> Unit,
    private val onRenderGone: () -> Unit,
    private val onNotify: (message: String) -> Unit
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
                LaunchResult.NOT_INSTALLED ->
                    onNotify(view.context.getString(R.string.app_not_installed))
                LaunchResult.NOT_WHITELISTED ->
                    onNotify(view.context.getString(R.string.app_not_allowed))
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
        if (request?.isForMainFrame != true) return
        val code = error?.errorCode ?: 0
        val description = error?.description?.toString()
        onMainFrameError(request.url?.toString(), code, description)
    }

    /** v0.9.0：4xx/5xx 只做提示，不拦截页面渲染（服务端自带错误页仍然显示）。 */
    override fun onReceivedHttpError(
        view: WebView?,
        request: WebResourceRequest?,
        errorResponse: WebResourceResponse?
    ) {
        if (request?.isForMainFrame != true) return
        val status = errorResponse?.statusCode ?: return
        if (status >= 400) onHttpError(status, request.url?.toString())
    }
}
