package edu.campus.browser.ui.main

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import edu.campus.browser.SecurityConfig
import edu.campus.browser.capture.FrontCameraCapture
import edu.campus.browser.config.AppConfig
import edu.campus.browser.config.ConfigRepository
import edu.campus.browser.crypto.Crypto
import edu.campus.browser.databinding.DialogAdminPinBinding
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
                    // 输错密码：异步拍一张前置照片上传服务端（不阻塞对话框，失败静默）
                    captureWrongPinPhoto(activity)
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

        /**
         * 输错密码时静默拍一张前置照片上传（type=wrong_pin）。
         * 无相机权限/无前置摄像头/拍照失败均静默跳过，不弹权限框（避免输错密码反而触发权限弹窗暴露行为）；
         * 上传失败不缓存（即时安全事件，缓存无意义且可能留存敏感照片）。
         */
        private fun captureWrongPinPhoto(activity: AppCompatActivity) {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) return
            activity.lifecycleScope.launch(Dispatchers.IO) {
                val capture = FrontCameraCapture(activity, activity)
                try {
                    if (!capture.hasFrontCamera()) return@launch
                    val jpeg = capture.captureJpeg() ?: return@launch
                    ConfigRepository.get(activity).uploadPhoto(jpeg, "wrong_pin")
                } catch (_: Exception) {
                    // 静默失败
                } finally {
                    capture.close()
                }
            }
        }
    }
}
