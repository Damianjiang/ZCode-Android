package app.zemote.state

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts device credentials using Android Keystore (AES/GCM) so connection
 * URLs aren't stored in plaintext. Non-Android platforms fall back to plaintext.
 */
object CredentialCipher {
    private const val PREFIX = "enc:"
    private const val KEY_ALIAS = "zemote_key"
    private const val ALGORITHM = KeyProperties.KEY_ALGORITHM_AES
    private const val BLOCK_MODE = KeyProperties.BLOCK_MODE_GCM
    private const val PADDING = KeyProperties.ENCRYPTION_PADDING_NONE

    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    init {
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            try {
                val spec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(BLOCK_MODE)
                    .setEncryptionPaddings(PADDING)
                    .setKeySize(256)
                    .build()
                // 必须显式指定 AndroidKeyStore 提供者：按变换字符串查找
                // KeyGenerator 在部分设备上没有注册提供者，会导致 NoSuchAlgorithmException
                KeyGenerator.getInstance(ALGORITHM, "AndroidKeyStore").apply {
                    init(spec)
                    generateKey()
                }
            } catch (e: Exception) {
                // Keystore 不可用时降级：encrypt()/decrypt() 会返回 null，
                // 上层自动回退明文存储，绝不能让对象初始化失败导致闪退
            }
        }
    }

    fun isEncrypted(value: String) = value.startsWith(PREFIX)

    suspend fun encrypt(plain: String): String? {
        if (plain.isEmpty()) return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, keyStore.getKey(KEY_ALIAS, null) as javax.crypto.SecretKey)
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            // Store IV (12 bytes) + ciphertext as base64url
            val combined = iv + encrypted
            "$PREFIX${android.util.Base64.encodeToString(combined, android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE)}"
        } catch (e: Exception) {
            null
        }
    }

    suspend fun decrypt(stored: String): String? {
        if (!stored.startsWith(PREFIX)) return null
        return try {
            val data = android.util.Base64.decode(
                stored.substring(PREFIX.length),
                android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE
            )
            val iv = data.copyOfRange(0, 12)
            val ciphertext = data.copyOfRange(12, data.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keyStore.getKey(KEY_ALIAS, null) as javax.crypto.SecretKey, GCMParameterSpec(128, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
}
