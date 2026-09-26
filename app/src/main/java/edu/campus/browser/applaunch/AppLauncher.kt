package edu.campus.browser.applaunch

import android.content.Context
import android.content.Intent
import android.net.Uri
import edu.campus.browser.config.AppConfig
import edu.campus.browser.config.AllowedApp

/** 应用拉起结果。 */
enum class LaunchResult { LAUNCHED, NOT_WHITELISTED, NOT_INSTALLED }

/**
 * 唤醒其他应用：仅允许拉起配置 allowed_apps 白名单内的应用。
 * 支持两种触发 URL：
 *  - app://com.example.learning        （host 即包名）
 *  - learning://...                    （自定义 scheme，按白名单 scheme 字段映射）
 */
object AppLauncher {

    /** 按触发 URL 解析白名单并拉起。 */
    fun launch(context: Context, config: AppConfig, url: String): LaunchResult {
        val uri = try {
            Uri.parse(url)
        } catch (e: Exception) {
            return LaunchResult.NOT_WHITELISTED
        }
        val scheme = uri.scheme?.lowercase() ?: return LaunchResult.NOT_WHITELISTED
        val entry: AllowedApp? = when (scheme) {
            "app" -> {
                val pkg = uri.host ?: return LaunchResult.NOT_WHITELISTED
                config.allowedApps.firstOrNull { it.packageName.equals(pkg, ignoreCase = true) }
            }
            else -> config.allowedApps.firstOrNull { it.scheme.equals(scheme, ignoreCase = true) }
        }
        if (entry == null) return LaunchResult.NOT_WHITELISTED

        // 包名方式最可靠，优先；再用自定义 scheme 兜底
        if (launchByPackage(context, entry.packageName)) return LaunchResult.LAUNCHED
        if (entry.scheme.isNotBlank() && launchByScheme(context, entry.scheme))
            return LaunchResult.LAUNCHED
        return LaunchResult.NOT_INSTALLED
    }

    /** 按包名取启动 Intent 拉起。 */
    fun launchByPackage(context: Context, packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 按自定义 scheme 拉起（目标应用需已注册该 scheme）。 */
    fun launchByScheme(context: Context, scheme: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("$scheme://"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(context.packageManager) == null) return false
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }
}
