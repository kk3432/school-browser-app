package edu.campus.browser.config

import org.json.JSONObject

data class Bookmark(
    val title: String,
    val url: String
)

/** 允许唤醒的其他应用（白名单条目）。 */
data class AllowedApp(
    val packageName: String,
    val label: String,
    val scheme: String
)

/**
 * 服务端下发的配置文件，字段名与 JSON 完全对应。
 */
data class AppConfig(
    val schemaVersion: Int,
    val version: String,
    val title: String,
    val homeUrl: String,
    val fallbackUrl: String,
    val mode: String,            // whitelist | blacklist
    val rules: List<String>,
    val bookmarks: List<Bookmark>,
    val updateIntervalSeconds: Int,
    val kiosk: Boolean,
    val hiddenEntryEnabled: Boolean,
    val blockScreenshot: Boolean,
    val requireStartupPhoto: Boolean,
    val allowedApps: List<AllowedApp>
) {
    companion object {
        fun parse(json: String): AppConfig {
            val o = JSONObject(json)
            val rulesArray = o.optJSONArray("rules")
            val rules = if (rulesArray != null) {
                (0 until rulesArray.length()).map { rulesArray.getString(it) }
            } else emptyList()
            val bmArray = o.optJSONArray("bookmarks")
            val bookmarks = if (bmArray != null) {
                (0 until bmArray.length()).map {
                    val b = bmArray.getJSONObject(it)
                    Bookmark(b.optString("title", ""), b.optString("url", ""))
                }.filter { it.url.isNotBlank() }
            } else emptyList()
            val appsArray = o.optJSONArray("allowed_apps")
            val allowedApps = if (appsArray != null) {
                (0 until appsArray.length()).map {
                    val a = appsArray.getJSONObject(it)
                    AllowedApp(
                        a.optString("package", ""),
                        a.optString("label", ""),
                        a.optString("scheme", "")
                    )
                }.filter { it.packageName.isNotBlank() }
            } else emptyList()
            return AppConfig(
                schemaVersion = o.optInt("schema_version", 1),
                version = o.optString("version", ""),
                title = o.optString("title", "校园门户"),
                homeUrl = o.optString("home_url", ""),
                fallbackUrl = o.optString("fallback_url", ""),
                mode = o.optString("mode", "whitelist"),
                rules = rules,
                bookmarks = bookmarks,
                updateIntervalSeconds = o.optInt("update_interval_seconds", 300),
                kiosk = o.optBoolean("kiosk", false),
                // 旧配置无字段时默认开启，保持与升级前行为一致
                hiddenEntryEnabled = o.optBoolean("hidden_entry_enabled", true),
                blockScreenshot = o.optBoolean("block_screenshot", true),
                requireStartupPhoto = o.optBoolean("require_startup_photo", false),
                allowedApps = allowedApps
            )
        }
    }
}
