package edu.campus.browser.net

import android.net.Uri
import edu.campus.browser.config.AppConfig

/**
 * 白/黑名单规则匹配。
 *
 * 支持的规则写法：
 *  - *.example.edu.cn                域名通配，匹配 example.edu.cn 及其所有子域名（http/https 均可）
 *  - example.edu.cn                  精确域名
 *  - https://lib.example.edu.cn/     该站点任意路径（规则末尾为斜杠加星号）
 *  - https://host/path 后接斜杠星号  指定路径前缀
 *  - https://host/page               精确网址
 *
 * 黑名单优先：同时命中白名单和黑名单时按拦截处理。
 */
class UrlRuleMatcher(private val config: AppConfig) {

    private data class Rule(val host: String, val pathPrefix: String?, val exactPath: Boolean)

    // 白名单模式下，管理员配置的书签网址自动放行（无需再重复加进规则）；
    // 黑名单模式不自动放行，书签命中黑名单仍会被拦截（黑名单优先）。
    private val rules: List<Rule> = buildList {
        config.rules.forEach { rule -> parseRule(rule.trim())?.let { add(it) } }
        if (config.mode != MODE_BLACKLIST) {
            config.bookmarks.forEach { bm -> parseRule(bm.url.trim())?.let { add(it) } }
        }
    }

    fun isAllowed(url: String): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val host = uri.host ?: return false
        val path = uri.path ?: "/"
        val hit = rules.any { hostMatches(it.host, host) && pathMatches(it, path) }
        return if (config.mode == MODE_BLACKLIST) !hit else hit
    }

    private fun parseRule(rule: String): Rule? {
        if (rule.isEmpty()) return null
        return if (rule.contains("://")) {
            val afterScheme = rule.substringAfter("://")
            val hostPart = afterScheme.substringBefore("/")
            val rawPath = if (afterScheme.contains("/")) "/" + afterScheme.substringAfter("/") else null
            when {
                rawPath == null -> Rule(hostPart, null, false)
                rawPath.endsWith("/*") -> Rule(hostPart, rawPath.dropLast(1), false) // "/a/*" -> "/a/"
                else -> Rule(hostPart, rawPath, true)
            }
        } else {
            // 纯域名规则（可能以 *. 开头），匹配任意路径
            Rule(rule, null, false)
        }
    }

    private fun hostMatches(pattern: String, host: String): Boolean {
        val p = pattern.lowercase()
        val h = host.lowercase()
        return if (p.startsWith("*.")) {
            val base = p.removePrefix("*.")
            h == base || h.endsWith(".$base")
        } else {
            h == p
        }
    }

    private fun pathMatches(rule: Rule, path: String): Boolean {
        val prefix = rule.pathPrefix ?: return true
        return if (rule.exactPath) {
            path == prefix
        } else {
            path.startsWith(prefix) || path == prefix.trimEnd('/')
        }
    }

    companion object {
        const val MODE_BLACKLIST = "blacklist"
    }
}
