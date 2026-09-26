package edu.campus.browser

/**
 * 安全相关常量，部署时需要修改 SERVER_PUBLIC_KEY_PEM。
 */
object SecurityConfig {

    /**
     * 服务端配置签名的 RSA 公钥（PEM）。部署后从 Web 后台「配置签名公钥」复制粘贴到这里再打包。
     * 留空时 APP 仅打印警告、不验签——仅限开发调试，正式部署必须填写。
     */
    const val SERVER_PUBLIC_KEY_PEM: String = ""

    /**
     * 6 位管理密码的 MD5 盐值，必须与服务端 Services/Security.cs 中 PinHasher.Salt 完全一致。
     */
    const val PIN_SALT: String = "campus-browser-pin-v1"

    /** PIN 连续输错多少次后锁定。 */
    const val MAX_PIN_ATTEMPTS: Int = 5

    /** 锁定时长（毫秒）。 */
    const val PIN_LOCK_MS: Long = 5 * 60 * 1000L
}
