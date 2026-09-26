package edu.campus.browser.ui.main

import android.graphics.Bitmap
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
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
    private val onPageFinished: (url: String) -> Unit
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url?.toString() ?: return true
        val scheme = request.url?.scheme ?: return true

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
        val allowed = UrlRuleMatcher(config).isAllowed(url)
        return if (allowed) {
            false
        } else {
            onBlocked(url)
            true // 拦截，不加载
        }
    }

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        // M1 只拦截页面跳转；页面内静态资源（CDN 图片/脚本）不拦截，避免误伤。
        return super.shouldInterceptRequest(view, request)
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
