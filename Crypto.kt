package com.clipsync.app

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object Crypto {
    private val cache = ConcurrentHashMap<String, SecretKeySpec>()

    private fun key(pin: String): SecretKeySpec {
        cache[pin]?.let { return it }
        val spec = PBEKeySpec(pin.toCharArray(), "clipsync-v3".toByteArray(), 10000, 256)
        val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
        val k = SecretKeySpec(raw, "AES")
        cache[pin] = k
        return k
    }

    fun enc(pin: String, text: String): ByteArray {
        val iv = ByteArray(12)
        SecureRandom().nextBytes(iv)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(pin), GCMParameterSpec(128, iv))
        return iv + c.doFinal(text.toByteArray(Charsets.UTF_8))
    }

    fun dec(pin: String, data: ByteArray): String? {
        return try {
            if (data.size < 13) return null
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(pin), GCMParameterSpec(128, data.copyOfRange(0, 12)))
            String(c.doFinal(data, 12, data.size - 12), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
}
