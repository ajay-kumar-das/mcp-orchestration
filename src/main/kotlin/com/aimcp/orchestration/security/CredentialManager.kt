package com.aimcp.orchestration.security

import org.springframework.stereotype.Component
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

@Component
class CredentialManager {

    private val encryptionKey: SecretKey by lazy {
        // In production, load this from secure configuration
        val keyBytes = "MySecretKey12345".toByteArray()
        SecretKeySpec(keyBytes, "AES")
    }

    fun getBasicAuth(username: String, password: String): String {
        val credentials = "$username:$password"
        return Base64.getEncoder().encodeToString(credentials.toByteArray())
    }

    fun getBearerToken(token: String): String {
        return decrypt(token)
    }

    fun getApiKey(apiKey: String): String {
        return decrypt(apiKey)
    }

    fun encryptCredential(plaintext: String): String {
        val cipher = Cipher.getInstance("AES")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey)
        val encryptedBytes = cipher.doFinal(plaintext.toByteArray())
        return Base64.getEncoder().encodeToString(encryptedBytes)
    }

    private fun decrypt(encryptedText: String): String {
        return try {
            val cipher = Cipher.getInstance("AES")
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey)
            val decodedBytes = Base64.getDecoder().decode(encryptedText)
            val decryptedBytes = cipher.doFinal(decodedBytes)
            String(decryptedBytes)
        } catch (e: Exception) {
            // If decryption fails, assume it's already plaintext (for development)
            encryptedText
        }
    }
}
