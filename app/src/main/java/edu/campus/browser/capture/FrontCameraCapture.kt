package edu.campus.browser.capture

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 前置摄像头静默拍照（CameraX，v0.5.0）：
 * - 无预览 UI，启动时直接拍一张 JPEG，返回字节数组（内存传递，不落媒体库，无需存储权限）；
 * - [hasFrontCamera] 用于决策点：设备无前置摄像头时跳过并上报，不阻断进入；
 * - 拍照失败/超时返回 null，调用方跳过（下次启动再试），避免把设备锁死。
 */
class FrontCameraCapture(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {

    private val executor = Executors.newSingleThreadExecutor()

    /** 设备是否具备前置摄像头。 */
    fun hasFrontCamera(): Boolean = try {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        manager.cameraIdList.any { id ->
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        }
    } catch (e: Exception) {
        false
    }

    /** 拍一张前置 JPEG；失败/超时/相机被占用返回 null。 */
    suspend fun captureJpeg(timeoutMs: Long = 8000): ByteArray? = try {
        withTimeout(timeoutMs) {
            withContext(Dispatchers.Main) {
                val provider = ProcessCameraProvider.getInstance(context).get()
                val imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setJpegQuality(85)
                    .build()
                try {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_FRONT_CAMERA,
                        imageCapture
                    )
                    suspendCancellableCoroutine { cont ->
                        imageCapture.takePicture(
                            executor,
                            object : ImageCapture.OnImageCapturedCallback() {
                                override fun onCaptureSuccess(image: ImageProxy) {
                                    val buffer = image.planes[0].buffer
                                    val bytes = ByteArray(buffer.remaining())
                                    buffer.get(bytes)
                                    image.close()
                                    cont.resume(bytes)
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    cont.resumeWithException(exception)
                                }
                            }
                        )
                    }
                } finally {
                    // 无论成功/失败/取消，拍完立即解绑释放相机
                    runCatching { provider.unbindAll() }
                }
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "前置拍照失败：${e.message}")
        null
    }

    fun close() {
        runCatching { executor.shutdown() }
    }

    companion object {
        private const val TAG = "FrontCameraCapture"
    }
}
