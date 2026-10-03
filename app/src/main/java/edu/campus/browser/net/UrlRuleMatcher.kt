package edu.campus.browser.net

import android.net.Uri
import edu.campus.browser.config.AppConfig

/**
 * 白/黑名单规则匹配。
 *
 * 支持的规则写法：
 *  - *.example.edu.cn                域名通配，匹配 example.edu.cn 及其所有子域名（http/https 均可）
 *  - example.edu.cn                  精确域名（任意端口、任意路径）
 *  - example.edu.cn:8080             精确域名 + 精确端口（v0.7.0）
 *  - https://lib.example.edu.cn/     该站点任意路径（协议仅用于解析，不参与匹配）
 *  - https://host:8443/path 后接斜杠星号  指定端口 + 路径前缀
 *  - https://host/page               精确网址
 *
 * 匹配只看 域名 / 端口 / 路径，**不区分 http 与 https**（v0.7.0 明确）。
 * 黑名单优先：同时命中白名单和黑名单时按拦截处理。
 */
class UrlRuleMatcher(private val config: AppConfig) {

    private data class Rule(
        val host: String,
        val port: Int?,          // null = 任意端口
        val pathPrefix: String?, // null = 任意路径
        val exactPath: Boolean
    )

    // 白名单模式下，管理员配置的书签网址自动放行（无需再重复加进规则）；
    // 黑名单模式不自动放行，书签命中黑名单仍会被拦截（黑名单优先）。
    private val rules: List<Rule> = buildList {
        config.rules.forEach { rule -> parseRule(rule.trim())?.let { add(it) } }
        if (config.mode != MODE_BLACKLIST) {
            config.bookmarks.forEach { bm ->
                // 书签统一去尾斜杠，避免 "https://host/" 被误判为精确路径 "/"
                parseRule(bm.url.trim().trimEnd('/'))?.let { add(it) }
            }
        }
    }

    fun isAllowed(url: String): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val host = uri.host ?: return false
        val path = uri.path ?: "/"
        val hit = rules.any {
            hostMatches(it.host, host) &&
            portMatches(it.port, uri) &&
            pathMatches(it, path)
        }
        return if (config.mode == MODE_BLACKLIST) !hit else hit
    }

    private fun parseRule(rule: String): Rule? {
        if (rule.isEmpty()) return null
        val afterScheme = if (rule.contains("://")) rule.substringAfter("://") else rule
        // 拆出 host[:port] 与 path
        val hostPart = afterScheme.substringBefore("/")
        val rawPath = if (afterScheme.contains("/")) "/" + afterScheme.substringAfter("/") else null

        val (host, port) = splitHostPort(hostPart)

        // 路径为空或仅 "/" 时视为整站放行（修复书签带尾斜杠被误判为精确根路径的问题）
        val normalizedPath = rawPath?.takeIf { it != "/" && it.isNotBlank() }
        return when {
            normalizedPath == null -> Rule(host, port, null, false)
            normalizedPath.endsWith("/*") -> Rule(host, port, normalizedPath.dropLast(1), false) // "/a/*" -> "/a/"
            else -> Rule(host, port, normalizedPath, true)
        }
    }

    /** 拆 host 与可选端口：example.com:8080 -> ("example.com", 8080)；无端口 port=null。 */
    private fun splitHostPort(hostPart: String): Pair<String, Int?> {
        val idx = hostPart.lastIndexOf(':')
        if (idx < 0) return hostPart to null
        val portText = hostPart.substring(idx + 1)
        val port = portText.toIntOrNull()
        return if (port != null && port in 1..65535) {
            hostPart.substring(0, idx) to port
        } else {
            hostPart to null // 非法端口忽略，按任意端口处理
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

    private fun portMatches(rulePort: Int?, uri: Uri): Boolean {
        if (rulePort == null) return true
        val actual = when (uri.port) {
            -1 -> if (uri.scheme?.lowercase() == "https") 443 else 80 // 未显式端口时按协议默认端口
            else -> uri.port
        }
        return actual == rulePort
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
