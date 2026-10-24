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

    // One random generator and one cipher per thread, instead of new ones for every packet
    private val random = SecureRandom()
    private val encCipher = object : ThreadLocal<Cipher>() {
        override fun initialValue(): Cipher = Cipher.getInstance("AES/GCM/NoPadding")
    }
    private val decCipher = object : ThreadLocal<Cipher>() {
        override fun initialValue(): Cipher = Cipher.getInstance("AES/GCM/NoPadding")
    }

    private fun key(pin: String): SecretKeySpec {
        cache[pin]?.let { return it }
        val spec = PBEKeySpec(pin.toCharArray(), "clipsync-v3".toByteArray(), 10000, 256)
        val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
        val k = SecretKeySpec(raw, "AES")
        cache[pin] = k
        return k
    }

    /** Works out the key for a PIN ahead of time (the first call takes a moment). */
    fun prepare(pin: String) {
        try {
            key(pin)
        } catch (e: Exception) {
        }
    }

    fun enc(pin: String, text: String): ByteArray {
        val iv = ByteArray(12)
        random.nextBytes(iv)
        val c = encCipher.get()!!
        c.init(Cipher.ENCRYPT_MODE, key(pin), GCMParameterSpec(128, iv))
        return iv + c.doFinal(text.toByteArray(Charsets.UTF_8))
    }

    fun dec(pin: String, data: ByteArray): String? {
        return try {
            if (data.size < 13) return null
            val c = decCipher.get()!!
            c.init(Cipher.DECRYPT_MODE, key(pin), GCMParameterSpec(128, data.copyOfRange(0, 12)))
            String(c.doFinal(data, 12, data.size - 12), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
}
