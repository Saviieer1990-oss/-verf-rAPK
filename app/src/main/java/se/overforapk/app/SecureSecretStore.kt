package se.overforapk.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores small secrets encrypted with an AES key held by Android Keystore. */
internal class SecureSecretStore(context: Context, fileName: String) {
    private val prefs = context.getSharedPreferences(fileName, Context.MODE_PRIVATE)
    private val alias = "se.overforapk.secret.$fileName"
    private val storeName = "AndroidKeyStore"

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(storeName).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, storeName)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    @Synchronized
    fun put(name: String, value: String) {
        if (value.isEmpty()) {
            prefs.edit().remove(name).apply()
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        val payload = cipher.iv + encrypted
        prefs.edit().putString(name, "v1:" + Base64.encodeToString(payload, Base64.NO_WRAP)).apply()
    }

    @Synchronized
    fun get(name: String): String {
        val stored = prefs.getString(name, null) ?: return ""
        return try {
            require(stored.startsWith("v1:")) { "Unknown encrypted secret format." }
            val payload = Base64.decode(stored.removePrefix("v1:"), Base64.NO_WRAP)
            require(payload.size > 12) { "Encrypted secret is incomplete." }
            val iv = payload.copyOfRange(0, 12)
            val ciphertext = payload.copyOfRange(12, payload.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            prefs.edit().remove(name).apply()
            ""
        }
    }
}
