package edu.campus.browser.applaunch

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * 把书签钉到安卓桌面（v0.8.0，需求3）。
 *
 * 点桌面图标的效果：拉起校园浏览器并直接打开该书签（仍受白名单管控）。
 *
 * 两条路径（各 ROM 支持不一，优先用新式 API）：
 *  - API 26+：ShortcutManager.requestPinShortcut（会弹系统确认框，最规范）；
 *  - 老旧 launcher：INSTALL_SHORTCUT 广播（华为/小米等桌面普遍仍认这一套）。
 *
 * 说明：Android 不允许创建"不经过任何 Activity 直接打开网址"的独立快捷方式，
 * 所以快捷方式必定先拉起本 APP，再由 APP 打开页面。
 */
object BookmarkShortcut {

    private const val TAG = "BookmarkShortcut"

    /** 快捷方式 Intent 里携带的目标网址，MainActivity 启动时读取。 */
    const val EXTRA_BOOKMARK_URL = "bookmark_url"

    /** 兜底方案用的广播 action（老 launcher 通用）。 */
    private const val ACTION_INSTALL_SHORTCUT = "com.android.launcher.action.INSTALL_SHORTCUT"
    private const val EXTRA_SHORTCUT_INTENT = "android.intent.extra.shortcut.INTENT"
    private const val EXTRA_SHORTCUT_NAME = "android.intent.extra.shortcut.NAME"
    private const val EXTRA_SHORTCUT_ICON_RESOURCE = "android.intent.extra.shortcut.ICON_RESOURCE"
    private const val EXTRA_SHORTCUT_DUPLICATE = "duplicate"

    /**
     * 构造"点图标 → 打开该书签"的 Intent。
     * 显式指定组件，避免被系统用浏览器打开（那样会绕过管控）。
     */
    private fun shortcutIntent(ctx: Context, url: String): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            setClass(ctx, edu.campus.browser.ui.main.MainActivity::class.java)
            putExtra(EXTRA_BOOKMARK_URL, url)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

    /** 从启动 Intent 中取出书签网址（非快捷方式启动返回 null）。 */
    fun urlFromIntent(intent: Intent?): String? {
        val url = intent?.getStringExtra(EXTRA_BOOKMARK_URL)
        return url?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
    }

    /**
     * 请求把书签钉到桌面。
     * [onResult] 在 UI 线程回调：(是否已发起/成功, 给用户看的提示文案)。
     *
     * 注意：老 launcher 的广播方式拿不到任何结果回调，只能提示"已发送请求"。
     */
    fun pin(ctx: Context, title: String, url: String, onResult: (Boolean, String) -> Unit) {
        if (url.isBlank()) {
            onResult(false, "书签网址为空，无法创建快捷方式")
            return
        }
        val label = title.ifBlank { url }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && pinWithShortcutManager(ctx, label, url, onResult)) {
            return
        }
        // 兜底：老式广播；部分 ROM 会弹出"已在桌面添加快捷方式"
        val ok = pinWithBroadcast(ctx, label, url)
        onResult(ok, if (ok) "已发送添加快捷方式请求，请查看桌面" else "当前桌面不支持自动添加，请长按应用图标手动添加")
    }

    /** API 26+ 的钉选流程；launcher 不支持 requestPinShortcut 时返回 false 交给兜底。 */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun pinWithShortcutManager(ctx: Context, label: String, url: String, onResult: (Boolean, String) -> Unit): Boolean {
        val manager = ctx.getSystemService(ShortcutManager::class.java) ?: return false
        if (!manager.isRequestPinShortcutSupported) return false
        return try {
            val iconRes = ctx.applicationInfo.icon.takeIf { it != 0 }
                ?: edu.campus.browser.R.mipmap.ic_launcher
            val info = ShortcutInfo.Builder(ctx, shortcutId(url))
                .setShortLabel(label.take(20))
                .setLongLabel(label.take(60))
                .setIcon(Icon.createWithResource(ctx, iconRes))
                .setIntent(shortcutIntent(ctx, url))
                .build()
            // 系统确认框点了"添加"后回这个 PendingIntent（部分 launcher 不回调，不影响主体流程）
            val callback = PendingIntent.getBroadcast(
                ctx, 0,
                Intent(ctx, ShortcutResultReceiver::class.java).putExtra(EXTRA_BOOKMARK_URL, url),
                PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0)
            )
            val requested = manager.requestPinShortcut(info, callback.intentSender)
            if (requested) onResult(true, "请在系统弹窗中确认添加")
            else onResult(false, "当前桌面不支持自动添加，请长按应用图标手动添加")
            requested
        } catch (e: Exception) {
            Log.w(TAG, "requestPinShortcut 失败：${e.message}")
            false
        }
    }

    /** 老式广播方式（无结果回调）。 */
    @Suppress("UnspecifiedRegisterReceiverFlag")
    private fun pinWithBroadcast(ctx: Context, label: String, url: String): Boolean = try {
        val intent = Intent(ACTION_INSTALL_SHORTCUT).apply {
            putExtra(EXTRA_SHORTCUT_NAME, label)
            putExtra(EXTRA_SHORTCUT_INTENT, shortcutIntent(ctx, url))
            putExtra(EXTRA_SHORTCUT_DUPLICATE, false)
            putExtra(
                EXTRA_SHORTCUT_ICON_RESOURCE,
                Intent.ShortcutIconResource.fromContext(ctx, ctx.applicationInfo.icon)
            )
        }
        ctx.sendBroadcast(intent)
        true
    } catch (e: Exception) {
        Log.w(TAG, "INSTALL_SHORTCUT 广播失败：${e.message}")
        false
    }

    /** 每个网址一个稳定的 shortcut id（同网址重复添加会覆盖而不是堆叠）。 */
    private fun shortcutId(url: String): String = "campus-bm-" + Integer.toHexString(url.hashCode())
}
