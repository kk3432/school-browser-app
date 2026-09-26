package edu.campus.browser.crypto

import android.util.Base64
import edu.campus.browser.SecurityConfig
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

object Crypto {

    /** MD5 小写十六进制。 */
    fun md5Hex(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** 平板 6 位管理密码的本地比对值，与服务端 PinHasher.HashPin 算法一致。 */
    fun pinHash(pin: String): String = md5Hex(pin + SecurityConfig.PIN_SALT)

    /**
     * 用 RSA 公钥校验服务端配置正文的 SHA256withRSA 签名。
     * @param publicKeyPem PEM 格式 X.509 SubjectPublicKeyInfo
     * @param data 被签名的配置正文原始字节
     * @param signatureBase64 响应头 X-Signature
     */
    fun verifyRsaSha256(publicKeyPem: String, data: ByteArray, signatureBase64: String): Boolean {
        return try {
            val pemBody = publicKeyPem.lineSequence()
                .filter { !it.startsWith("-----") }
                .joinToString("")
                .trim()
            val keyBytes = Base64.decode(pemBody, Base64.DEFAULT)
            val publicKey = KeyFactory.getInstance("RSA")
                .generatePublic(X509EncodedKeySpec(keyBytes))
            val signer = Signature.getInstance("SHA256withRSA")
            signer.initVerify(publicKey)
            signer.update(data)
            signer.verify(Base64.decode(signatureBase64, Base64.DEFAULT))
        } catch (e: Exception) {
            false
        }
    }
}
