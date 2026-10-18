package edu.campus.browser.ui.setup

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import edu.campus.browser.R
import edu.campus.browser.config.ConfigRepository
import edu.campus.browser.databinding.ActivitySetupBinding
import edu.campus.browser.scan.QrScanner
import edu.campus.browser.ui.main.MainActivity
import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 首次启动 / 管理员修改服务器地址。
 * 必须成功拉到配置才能进入，失败则停留在此页。
 *
 * v0.9.0 界面改造（P0-12 / 规划 5.5）：卡片最大宽度钳制、地址前缀预填、错误分级文案走
 * TextInputLayout.error（不再显示异常原文）、连接中输入框与按钮禁用。
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding
    private lateinit var scanner: QrScanner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        scanner = QrScanner(this)
        prefillBaseUrl()
        clampCardWidth()

        binding.btnConnect.setOnClickListener { attemptConnect() }
        binding.btnScan.setOnClickListener {
            scanner.start { contents ->
                if (contents.isNullOrBlank()) return@start
                val scanned = contents.trim()
                if (scanned.startsWith("http://", ignoreCase = true) ||
                    scanned.startsWith("https://", ignoreCase = true)
                ) {
                    binding.etBaseUrl.setText(scanned)
                    binding.etBaseUrl.setSelection(scanned.length)
                    attemptConnect()
                } else {
                    binding.tvStatus.setText(R.string.setup_scan_invalid)
                }
            }
        }
        binding.etBaseUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                attemptConnect()
                true
            } else false
        }
        binding.etBaseUrl.doAfterTextChanged { binding.tilBaseUrl.error = null }
    }

    /** 已有配置就回填并全选，否则预填 https:// 便于直接输入域名。 */
    private fun prefillBaseUrl() {
        val existing = ConfigRepository.get(this).getBaseUrl()
        if (!existing.isNullOrBlank()) {
            binding.etBaseUrl.setText(existing)
            binding.etBaseUrl.setSelection(existing.length)
        } else {
            val prefix = "https://"
            binding.etBaseUrl.setText(prefix)
            binding.etBaseUrl.setSelection(prefix.length)
        }
    }

    /** 大屏上限制卡片宽度，避免输入区被拉得过宽（规划 5.5）。 */
    private fun clampCardWidth() {
        val card = binding.setupCard
        card.post {
            val parent = card.parent as? View ?: return@post
            val available = parent.width - parent.paddingLeft - parent.paddingRight
            if (available <= 0) return@post
            val maxWidth = resources.getDimensionPixelSize(R.dimen.setup_card_max_width)
            val target = min(available, maxWidth)
            val lp = card.layoutParams
            if (lp.width != target) {
                lp.width = target
                card.layoutParams = lp
            }
        }
    }

    private fun attemptConnect() {
        val input = binding.etBaseUrl.text.toString().trim()
        if (input.isBlank()) {
            binding.tilBaseUrl.error = getString(R.string.setup_error_empty)
            return
        }
        if (!input.startsWith("http://", ignoreCase = true) &&
            !input.startsWith("https://", ignoreCase = true)
        ) {
            binding.tilBaseUrl.error = getString(R.string.setup_error_scheme)
            return
        }
        binding.tilBaseUrl.error = null
        setConnecting(true)

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { ConfigRepository.get(this@SetupActivity).bootstrap(input) }
            }
            setConnecting(false)

            result.onSuccess {
                val intent = Intent(this@SetupActivity, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(intent)
                finish()
            }.onFailure { e ->
                // 分级文案，绝不把异常原文甩给用户
                binding.tilBaseUrl.error = getString(messageFor(e))
                binding.tvStatus.text = ""
            }
        }
    }

    private fun setConnecting(connecting: Boolean) {
        binding.progress.isVisible = connecting
        binding.btnConnect.isEnabled = !connecting
        binding.btnScan.isEnabled = !connecting
        binding.etBaseUrl.isEnabled = !connecting
        if (connecting) {
            binding.tvStatus.setText(R.string.setup_connecting)
        } else {
            binding.tvStatus.text = ""
        }
    }

    private fun messageFor(e: Throwable): Int = when {
        e is SSLException -> R.string.setup_error_cert
        e is SocketTimeoutException -> R.string.setup_error_network
        e is IOException -> R.string.setup_error_network
        e.message?.contains("sign", ignoreCase = true) == true -> R.string.setup_error_signature
        else -> R.string.setup_error_unknown
    }
}
