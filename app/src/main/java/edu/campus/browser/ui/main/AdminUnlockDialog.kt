package edu.campus.browser.ui.main

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import edu.campus.browser.R
import edu.campus.browser.SecurityConfig
import edu.campus.browser.capture.FrontCameraCapture
import edu.campus.browser.config.AppConfig
import edu.campus.browser.config.ConfigRepository
import edu.campus.browser.crypto.Crypto
import edu.campus.browser.databinding.DialogAdminPinBinding
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 隐藏入口调出的管理员解锁。
 * v0.7.0 起：PIN 不在本地比对，改为把 MD5(pin+盐) 哈希发到服务端 /api/v1/verify-pin 校验；
 * 离线/网络失败/限频统一按「密码错误」处理（不暴露离线原因）。
 * 通过后可：修改服务器地址 / 开启临时无管控模式。连续输错锁定。
 *
 * v0.9.0 可用性改造（P0-7）：340dp 宽、打开即聚焦并弹键盘、错误走 TextInputLayout.error、
 * 提交中禁用按钮 + 进度环、回车即提交。锁定与输错拍照逻辑保持不变。
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
                    .setTitle(R.string.pin_locked_title)
                    .setMessage(activity.getString(R.string.pin_locked, minutes))
                    .setPositiveButton(R.string.pin_got_it, null)
                    .show()
                return
            }

            val binding = DialogAdminPinBinding.inflate(activity.layoutInflater)
            val dialog = MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.pin_title)
                .setView(binding.root)
                .setNegativeButton(R.string.cancel, null)
                .create()

            fun setVerifying(verifying: Boolean) {
                binding.btnConfirm.isEnabled = !verifying
                binding.etPin.isEnabled = !verifying
                binding.pinProgress.isVisible = verifying
                binding.tvPinMsg.isVisible = verifying
                if (verifying) binding.tvPinMsg.setText(R.string.pin_verifying)
            }

            fun submit() {
                val pin = binding.etPin.text.toString()
                if (pin.length != 6 || !pin.all { it.isDigit() }) {
                    binding.tilPin.error = activity.getString(R.string.pin_invalid_length)
                    return
                }
                binding.tilPin.error = null
                setVerifying(true)
                activity.lifecycleScope.launch(Dispatchers.IO) {
                    val pinHash = Crypto.pinHash(pin)
                    val matched = ConfigRepository.get(activity).verifyPin(pinHash)
                    withContext(Dispatchers.Main) {
                        setVerifying(false)
                        if (matched) {
                            kv.encode(KV_FAIL_COUNT, 0)
                            kv.encode(KV_LOCK_UNTIL, 0L)
                            dialog.dismiss()
                            showActions(activity, onAction)
                        } else {
                            // 输错密码：异步拍一张前置照片上传服务端（不阻塞对话框，失败缓存）
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
                                binding.tilPin.error = activity.getString(R.string.pin_wrong, left)
                                binding.etPin.text?.clear()
                            }
                        }
                    }
                }
            }

            binding.btnConfirm.setOnClickListener { submit() }
            binding.etPin.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    submit()
                    true
                } else false
            }
            // 用户重新输入时清掉错误提示
            binding.etPin.doAfterTextChanged { binding.tilPin.error = null }

            dialog.setOnShowListener {
                binding.etPin.requestFocus()
                dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
                binding.etPin.postDelayed({
                    val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showSoftInput(binding.etPin, InputMethodManager.SHOW_IMPLICIT)
                }, 100L)
            }
            dialog.show()
        }

        private fun showActions(activity: Activity, onAction: (Action) -> Unit) {
            val items = arrayOf(
                activity.getString(R.string.admin_change_server),
                activity.getString(R.string.admin_temp_unlock)
            )
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.admin_actions_title)
                .setItems(items) { _, which ->
                    onAction(if (which == 0) Action.CHANGE_SERVER else Action.TEMP_UNLOCK)
                }
                .setNegativeButton(R.string.close, null)
                .show()
        }

        /**
         * 输错密码时静默拍一张前置照片上传（type=wrong_pin）。
         * 无相机权限/无前置摄像头静默跳过，不弹权限框（避免输错密码反而触发权限弹窗暴露行为）；
         * 拍照成功但上传失败（含限频 429）时缓存到 pending_photos，下次启动补传（v0.7.0）。
         */
        private fun captureWrongPinPhoto(activity: AppCompatActivity) {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) return
            activity.lifecycleScope.launch(Dispatchers.IO) {
                val capture = FrontCameraCapture(activity, activity)
                try {
                    if (!capture.hasFrontCamera()) return@launch
                    val jpeg = capture.captureJpeg() ?: return@launch
                    val uploaded = ConfigRepository.get(activity).uploadPhoto(jpeg, "wrong_pin")
                    if (!uploaded) ConfigRepository.get(activity).savePendingPhoto(jpeg, "wrong_pin")
                } catch (_: Exception) {
                    // 静默失败
                } finally {
                    capture.close()
                }
            }
        }
    }
}
