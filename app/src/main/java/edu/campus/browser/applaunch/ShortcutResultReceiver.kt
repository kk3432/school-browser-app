package edu.campus.browser.applaunch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * 钉选快捷方式的回调（v0.8.0）。
 * 系统在用户点了「添加」后通知这里；部分 launcher 不回传，所以只做"锦上添花"的提示，
 * 不依赖它判断成功与否。
 */
class ShortcutResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val label = intent.getStringExtra(BookmarkShortcut.EXTRA_BOOKMARK_URL) ?: ""
        Toast.makeText(context, "已添加到桌面：$label", Toast.LENGTH_SHORT).show()
    }
}
