package edu.campus.browser.ui

/**
 * 管理员临时会话状态。只存在于内存中，APP 重启即清空——临时无管控模式随之自动失效。
 */
object AdminSession {
    @Volatile
    var bypassRules: Boolean = false
}
