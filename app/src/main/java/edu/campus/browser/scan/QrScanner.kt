package edu.campus.browser.scan

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanIntentResult
import com.journeyapps.barcodescanner.ScanOptions

/**
 * 二维码扫描器：封装 ZXing 的 ActivityResult 启动与相机权限申请。
 * 用法：scanner.start { contents -> ... }；用户取消、拒绝权限或失败时 contents 为 null。
 */
class QrScanner(private val activity: AppCompatActivity) {

    private var onResult: ((String?) -> Unit)? = null

    private val permissionLauncher =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) launchScan() else onResult?.invoke(null)
        }

    private val scanLauncher =
        activity.registerForActivityResult(ScanContract()) { result: ScanIntentResult ->
            onResult?.invoke(result.contents)
        }

    fun start(onResult: (String?) -> Unit) {
        this.onResult = onResult
        val granted = ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) launchScan() else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun launchScan() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("将二维码对准取景框")
            setBeepEnabled(false)
            setOrientationLocked(false)
            setCameraId(0)
        }
        scanLauncher.launch(options)
    }
}
