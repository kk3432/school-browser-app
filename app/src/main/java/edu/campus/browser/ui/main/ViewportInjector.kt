package edu.campus.browser.ui.main

import android.util.Log
import android.webkit.WebView

/**
 * 页面比例兜底（性能/渲染优化 阶段2）。
 *
 * 背景：WebView 开了 `useWideViewPort` + `loadWithOverviewMode` 之后，**没有 viewport meta**
 * 的老页面仍会按较宽的布局宽度排版，在平板横屏下与系统浏览器观感不一致。这里做兜底：
 * 只在页面确实没有 `<meta name="viewport">` 时补一个 `width=device-width,initial-scale=1`，
 * 已有 viewport 的页面（响应式站点）**一个字节都不改**，尊重站点自身布局。
 *
 * 两个必须注意的点：
 *  - 幂等：同一 URL 只探测一次，SPA 路由变化导致 onPageFinished 重复触发时不会反复改页面，
 *    避免重排抖动；
 *  - 不错页：每次导航递增序号，异步回调返回时若序号已变（页面又跳走了）就丢弃结果，
 *    不会把"上一页已注入"误记到新页面头上。
 *
 * 注入发生在页面加载完之后，属于"纠正"而非"从第一帧就正确"，对响应式站点无影响。
 * 脚本不含任何 URL 插值，无需转义。
 */
object ViewportInjector {

    private const val TAG = "ViewportInjector"

    /** 已探测过的 URL，避免同一页重复求值。 */
    private var lastUrl: String? = null

    /** 导航序号：回调回来时序号不一致说明页面已切换，结果作废。 */
    private var generation = 0

    /** 探测 + 按需注入脚本；返回 'has' / 'nohead' / 'inject'。 */
    private const val SCRIPT = """
        (function(){
          var head = document.head || document.documentElement;
          if (!head) return 'nohead';
          if (document.querySelector('meta[name=viewport]')) return 'has';
          var m = document.createElement('meta');
          m.name = 'viewport';
          m.content = 'width=device-width, initial-scale=1';
          head.appendChild(m);
          window.dispatchEvent(new Event('resize'));
          return 'inject';
        })()
    """

    /**
     * 在 onPageFinished 时调用（UI 线程）。
     * 每页只执行一次探测脚本；重复调用直接返回，不产生额外 JS 求值开销。
     */
    fun injectIfMissing(web: WebView, url: String?) {
        if (url.isNullOrBlank()) return
        if (url == lastUrl) return // 同一页（含 SPA 二次 onPageFinished）不再探测
        lastUrl = url
        val current = generation
        web.evaluateJavascript(SCRIPT) { result ->
            if (current != generation) return@evaluateJavascript // 页面已切换，结果作废
            when (result?.trim('"')) {
                "inject" -> Log.i(TAG, "已为无 viewport 页面补注入：$url")
                "nohead" -> Log.w(TAG, "页面无 head 节点，跳过注入：$url")
                else -> Unit // 'has'：站点自带 viewport，不动
            }
        }
    }

    /** 页面开始加载（onPageStarted）时重置，保证新页面会重新探测。 */
    fun reset() {
        lastUrl = null
        generation++
    }
}
