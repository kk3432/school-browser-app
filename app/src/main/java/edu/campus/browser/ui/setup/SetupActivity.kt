package edu.campus.browser.ui.setup

import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import edu.campus.browser.config.ConfigRepository
import edu.campus.browser.databinding.ActivitySetupBinding
import edu.campus.browser.scan.QrScanner
import edu.campus.browser.ui.main.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 首次启动 / 管理员修改服务器地址。
 * 必须成功拉到配置才能进入，失败则停留在此页。
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding
    private lateinit var scanner: QrScanner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        scanner = QrScanner(this)
        ConfigRepository.get(this).getBaseUrl()?.let { binding.etBaseUrl.setText(it) }
        binding.btnConnect.setOnClickListener { attemptConnect() }
        binding.btnScan.setOnClickListener {
            scanner.start { contents ->
                if (contents.isNullOrBlank()) return@start
                val scanned = contents.trim()
                if (scanned.startsWith("http://", ignoreCase = true) ||
                    scanned.startsWith("https://", ignoreCase = true)
                ) {
                    binding.etBaseUrl.setText(scanned)
                    attemptConnect()
                } else {
                    Toast.makeText(this, "请扫描服务器地址二维码", Toast.LENGTH_SHORT).show()
                }
            }
        }
        binding.etBaseUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                attemptConnect()
                true
            } else false
        }
    }

    private fun attemptConnect() {
        val input = binding.etBaseUrl.text.toString()
        binding.progress.isVisible = true
        binding.btnConnect.isEnabled = false
        binding.tvStatus.text = "正在连接服务器并获取配置…"

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { ConfigRepository.get(this@SetupActivity).bootstrap(input) }
            }
            binding.progress.isVisible = false
            binding.btnConnect.isEnabled = true

            result.onSuccess {
                val intent = Intent(this@SetupActivity, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(intent)
                finish()
            }.onFailure { e ->
                binding.tvStatus.text = "无法进入：${e.message ?: "连接失败，请检查地址与网络"}"
            }
        }
    }
}
