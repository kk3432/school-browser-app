package edu.campus.browser.ui.main

import android.app.Activity
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import edu.campus.browser.SecurityConfig
import edu.campus.browser.config.AppConfig
import edu.campus.browser.crypto.Crypto
import edu.campus.browser.databinding.DialogAdminPinBinding
import com.tencent.mmkv.MMKV

/**
 * 隐藏入口调出的管理员解锁：输入 6 位密码，本地 MD5 比对配置中的哈希。
 * 通过后可：修改服务器地址 / 开启临时无管控模式。连续输错锁定。
 */
class AdminUnlockDialog private constructor() {

    enum class Action { CHANGE_SERVER, TEMP_UNLOCK }

    companion object {
        private const val KV_FAIL_COUNT = "pin_fail_count"
        private const val KV_LOCK_UNTIL = "pin_lock_until"

        fun show(activity: AppCompatActivity, config: AppConfig, onAction: (Action) -> Unit) {
            val kv = MMKV.mmkvWithID("campus_browser")
            val lockUntil = kv.decodeLong(KV_LOCK_UNTIL, 0L)
            val now = System.currentTimeMillis()
            if (now < lockUntil) {
                val minutes = (lockUntil - now) / 60000 + 1
                MaterialAlertDialogBuilder(activity)
                    .setTitle("已锁定")
                    .setMessage("密码错误次数过多，请 ${minutes} 分钟后再试。")
                    .setPositiveButton("知道了", null)
                    .show()
                return
            }

            val binding = DialogAdminPinBinding.inflate(activity.layoutInflater)
            val dialog = MaterialAlertDialogBuilder(activity)
                .setTitle("管理员验证")
                .setView(binding.root)
                .setNegativeButton("取消", null)
                .create()

            binding.btnConfirm.setOnClickListener {
                val pin = binding.etPin.text.toString()
                if (pin.length != 6 || !pin.all { it.isDigit() }) {
                    binding.tvPinMsg.text = "请输入 6 位数字密码"
                    return@setOnClickListener
                }
                if (config.adminPinHash.isBlank()) {
                    binding.tvPinMsg.text = "服务器尚未设置管理密码"
                    return@setOnClickListener
                }
                val matched = Crypto.pinHash(pin).equals(config.adminPinHash, ignoreCase = true)
                if (matched) {
                    kv.encode(KV_FAIL_COUNT, 0)
                    kv.encode(KV_LOCK_UNTIL, 0L)
                    dialog.dismiss()
                    showActions(activity, onAction)
                } else {
                    val fails = kv.decodeInt(KV_FAIL_COUNT, 0) + 1
                    kv.encode(KV_FAIL_COUNT, fails)
                    if (fails >= SecurityConfig.MAX_PIN_ATTEMPTS) {
                        kv.encode(KV_LOCK_UNTIL, System.currentTimeMillis() + SecurityConfig.PIN_LOCK_MS)
                        kv.encode(KV_FAIL_COUNT, 0)
                        dialog.dismiss()
                        show(activity, config, onAction) // 直接展示锁定提示
                    } else {
                        val left = SecurityConfig.MAX_PIN_ATTEMPTS - fails
                        binding.tvPinMsg.text = "密码错误，还可尝试 $left 次"
                        binding.etPin.text?.clear()
                    }
                }
            }
            dialog.show()
        }

        private fun showActions(activity: Activity, onAction: (Action) -> Unit) {
            val items = arrayOf("修改服务器地址", "临时无管控模式（重启 APP 后恢复）")
            MaterialAlertDialogBuilder(activity)
                .setTitle("管理员操作")
                .setItems(items) { _, which ->
                    onAction(if (which == 0) Action.CHANGE_SERVER else Action.TEMP_UNLOCK)
                }
                .setNegativeButton("关闭", null)
                .show()
        }
    }
}
