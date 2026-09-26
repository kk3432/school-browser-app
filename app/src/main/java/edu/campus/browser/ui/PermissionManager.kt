package edu.campus.browser.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 启动权限自检（v0.5.0）：
 * - [missing]：返回当前缺失的危险权限（普通权限安装即授，无需运行时申请）；
 * - [request]：一次性批量申请缺失权限，回调 granted 表示全部已授予；
 * - [showRationale]：申请前弹"为什么需要该权限"说明框（教室场景透明告知）。
 *
 * 注意：ActivityResult 必须在 Activity 处于 STARTED 之前注册，因此本类由
 * MainActivity 在 onCreate 早期实例化（与 QrScanner 同一模式）。
 */
class PermissionManager(private val activity: AppCompatActivity) {

    private var onResult: ((Boolean) -> Unit)? = null

    private val permissionLauncher =
        activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val granted = RUNTIME_PERMISSIONS.all { results[it] == true }
            onResult?.invoke(granted)
        }

    /** 缺失的权限一次申请；已全部授予时直接回调 true。 */
    fun request(onResult: (Boolean) -> Unit) {
        this.onResult = onResult
        val need = missing(activity)
        if (need.isEmpty()) {
            onResult(true)
        } else {
            permissionLauncher.launch(need.toTypedArray())
        }
    }

    companion object {
        /** 本 APP 需要运行时申请的危险权限清单，未来新增权限只改这里。 */
        val RUNTIME_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)

        fun missing(context: Context): List<String> =
            RUNTIME_PERMISSIONS.filter {
                ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
            }

        /** 申请前的用途说明框；不可取消，确认走 onProceed，取消走 onCancel。 */
        fun showRationale(
            activity: AppCompatActivity,
            title: String,
            message: String,
            onProceed: () -> Unit,
            onCancel: () -> Unit
        ) {
            MaterialAlertDialogBuilder(activity)
                .setTitle(title)
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton("继续") { _, _ -> onProceed() }
                .setNegativeButton("取消") { _, _ -> onCancel() }
                .show()
        }
    }
}
