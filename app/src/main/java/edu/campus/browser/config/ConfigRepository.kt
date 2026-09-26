package edu.campus.browser.config

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import edu.campus.browser.SecurityConfig
import edu.campus.browser.crypto.Crypto
import com.tencent.mmkv.MMKV
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 配置仓库：负责向服务端注册、拉取/验签/缓存配置与心跳。
 * 三级兜底：新配置 → 本地缓存（由 MainActivity 保证）→ 首次启动未成功则强制停留在设置页。
 */
class ConfigRepository private constructor(private val context: Context) {

    private val kv: MMKV = MMKV.mmkvWithID("campus_browser")

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    fun isBootstrapped(): Boolean = kv.decodeBool(KEY_BOOTSTRAPPED, false)

    fun getBaseUrl(): String? = kv.decodeString(KEY_BASE_URL)?.takeIf { it.isNotBlank() }

    fun getCachedConfig(): AppConfig? =
        kv.decodeString(KEY_CONFIG_JSON)?.let {
            runCatching { AppConfig.parse(it) }.getOrNull()
        }

    fun getCachedVersion(): String = kv.decodeString(KEY_CONFIG_VERSION, "") ?: ""

    @SuppressLint("HardwareIds")
    private fun deviceId(): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: ("unknown-" + Build.DEVICE)

    private fun deviceName(): String = Build.MANUFACTURER + " " + Build.MODEL

    /**
     * 获取当前 IPv4 地址（IP 会随网络变化，每次注册/心跳都重新读取）。
     * 优先 ConnectivityManager 的链路地址（API 23+），兜底遍历网卡。
     */
    private fun getLocalIpAddress(): String = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val addr = cm.getLinkProperties(cm.activeNetwork)?.linkAddresses
            ?.map { it.address }
            ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
        addr ?: getLocalIpViaInterfaces()
    } catch (e: Exception) {
        getLocalIpViaInterfaces()
    }

    private fun getLocalIpViaInterfaces(): String = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress ?: ""
    } catch (e: Exception) {
        ""
    }

    private fun normalizeUrl(input: String): String {
        val raw = input.trim().trimEnd('/')
        if (raw.isBlank()) throw IOException("请输入服务器地址")
        val withScheme = when {
            raw.startsWith("http://", ignoreCase = true) ||
                raw.startsWith("https://", ignoreCase = true) -> raw
            else -> "http://$raw"
        }
        val uri = try {
            URI(withScheme)
        } catch (e: Exception) {
            null
        }
        if (uri == null || uri.host.isNullOrBlank() || uri.scheme !in listOf("http", "https")) {
            throw IOException("地址格式不正确，示例：http://192.168.1.10:8080")
        }
        return withScheme
    }

    /**
     * 首次启动：填地址 → 注册 → 拉配置并验签 → 写入本地。
     * 任何一步失败都抛异常，调用方必须停留在设置页，不得进入浏览器。
     */
    @Throws(IOException::class)
    fun bootstrap(inputUrl: String): AppConfig {
        val baseUrl = normalizeUrl(inputUrl)
        register(baseUrl)
        val (json, version, signature) = fetchConfig(baseUrl)
        verifyBody(json, signature)
        val config = parseAndValidate(json)
        kv.encode(KEY_BASE_URL, baseUrl)
        kv.encode(KEY_CONFIG_JSON, json)
        kv.encode(KEY_CONFIG_VERSION, version)
        kv.encode(KEY_BOOTSTRAPPED, true)
        return config
    }

    /**
     * 定时刷新。
     * - 网络失败：返回 null，调用方继续使用本地缓存；
     * - 验签/格式失败：同样返回 null，坏配置绝不覆盖缓存；
     * - 版本相同：返回 null；
     * - 拿到合法的新版本：落库并返回。
     */
    fun refresh(): AppConfig? {
        val baseUrl = getBaseUrl() ?: return null
        return try {
            val (json, version, signature) = fetchConfig(baseUrl)
            verifyBody(json, signature)
            val config = parseAndValidate(json)
            heartbeat(baseUrl, config.version)
            if (version.isNotBlank() && version == getCachedVersion()) {
                null
            } else {
                kv.encode(KEY_CONFIG_JSON, json)
                kv.encode(KEY_CONFIG_VERSION, version)
                config
            }
        } catch (e: Exception) {
            Log.w(TAG, "刷新配置失败，继续使用本地缓存：${e.message}")
            null
        }
    }

    private fun verifyBody(json: String, signature: String) {
        if (SecurityConfig.SERVER_PUBLIC_KEY_PEM.isBlank()) {
            Log.w(TAG, "未内置服务器公钥，跳过验签（仅限调试，正式部署必须配置）")
            return
        }
        val ok = signature.isNotBlank() &&
            Crypto.verifyRsaSha256(
                SecurityConfig.SERVER_PUBLIC_KEY_PEM,
                json.toByteArray(Charsets.UTF_8),
                signature
            )
        if (!ok) throw IOException("配置签名校验失败，拒绝使用")
    }

    private fun parseAndValidate(json: String): AppConfig {
        val config = try {
            AppConfig.parse(json)
        } catch (e: Exception) {
            throw IOException("配置格式错误：${e.message}")
        }
        if (!config.homeUrl.startsWith("http://", ignoreCase = true) &&
            !config.homeUrl.startsWith("https://", ignoreCase = true)
        ) {
            throw IOException("配置中的首页网址无效")
        }
        return config
    }

    private fun fetchConfig(baseUrl: String): Triple<String, String, String> {
        val url = "$baseUrl/api/v1/config?device_id=${deviceId()}"
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("配置服务器返回 HTTP ${resp.code}")
            val body = resp.body?.string() ?: throw IOException("配置响应为空")
            return Triple(
                body,
                resp.header("X-Config-Version") ?: "",
                resp.header("X-Signature") ?: ""
            )
        }
    }

    private fun register(baseUrl: String) {
        val payload = JSONObject()
            .put("deviceId", deviceId())
            .put("name", deviceName())
            .put("appVersion", edu.campus.browser.BuildConfig.VERSION_NAME)
            .put("ipAddress", getLocalIpAddress())
        if (postJson("$baseUrl/api/v1/devices/register", payload) != true) {
            throw IOException("设备注册失败，请检查服务器地址")
        }
    }

    private fun heartbeat(baseUrl: String, configVersion: String) {
        val payload = JSONObject()
            .put("deviceId", deviceId())
            .put("name", deviceName())
            .put("appVersion", edu.campus.browser.BuildConfig.VERSION_NAME)
            .put("configVersion", configVersion)
            .put("ipAddress", getLocalIpAddress())
        postJson("$baseUrl/api/v1/devices/heartbeat", payload)
    }

    /** 成功返回 true，网络/服务端失败返回 false（注册失败要阻断，心跳失败忽略）。 */
    private fun postJson(url: String, payload: JSONObject): Boolean? {
        return try {
            val request = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            null
        }
    }

    // ---------- 启动照片上传（v0.5.0） ----------

    /** 上传前置摄像头 JPEG（multipart）。成功 true；网络/服务端拒绝（含限频 429）返回 false，由调用方缓存待补传。 */
    fun uploadPhoto(jpeg: ByteArray): Boolean {
        val baseUrl = getBaseUrl() ?: return false
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("device_id", deviceId())
            .addFormDataPart("captured_at", isoNow())
            .addFormDataPart("image", "startup.jpg", jpeg.toRequestBody("image/jpeg".toMediaType()))
            .build()
        return try {
            val request = Request.Builder().url("$baseUrl/api/v1/photo").post(body).build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.w(TAG, "照片上传失败：${e.message}")
            false
        }
    }

    /** 无前置摄像头等原因跳过拍照时上报原因（不落盘照片）。 */
    fun uploadPhotoSkip(reason: String): Boolean {
        val baseUrl = getBaseUrl() ?: return false
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("device_id", deviceId())
            .addFormDataPart("skip_reason", reason)
            .build()
        return try {
            val request = Request.Builder().url("$baseUrl/api/v1/photo").post(body).build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            Log.w(TAG, "跳过原因上报失败：${e.message}")
            false
        }
    }

    /** 上传失败的照片写私有目录缓存，下次启动补传（沿用"三级兜底"思路）。 */
    fun savePendingPhoto(jpeg: ByteArray): Boolean = try {
        val dir = pendingPhotoDir().apply { mkdirs() }
        File(dir, "photo_${System.currentTimeMillis()}.jpg").writeBytes(jpeg)
        true
    } catch (e: Exception) {
        Log.w(TAG, "照片缓存失败：${e.message}")
        false
    }

    /** 启动时补传上次遗留的照片：逐个上传，成功即删；失败保留（含限频 429）等下次。返回成功张数。 */
    fun flushPendingPhotos(): Int {
        val dir = pendingPhotoDir()
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".jpg") } ?: return 0
        var ok = 0
        for (f in files) {
            val bytes = runCatching { f.readBytes() }.getOrNull() ?: continue
            if (uploadPhoto(bytes)) {
                f.delete()
                ok++
            }
        }
        return ok
    }

    private fun pendingPhotoDir(): File = File(context.filesDir, "pending_photos")

    private fun isoNow(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date())

    companion object {
        private const val TAG = "ConfigRepository"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private const val KEY_BASE_URL = "base_url"
        private const val KEY_CONFIG_JSON = "config_json"
        private const val KEY_CONFIG_VERSION = "config_version"
        private const val KEY_BOOTSTRAPPED = "bootstrapped"

        @Volatile
        private var instance: ConfigRepository? = null

        fun get(context: Context): ConfigRepository =
            instance ?: synchronized(this) {
                instance ?: ConfigRepository(context.applicationContext).also { instance = it }
            }
    }
}
