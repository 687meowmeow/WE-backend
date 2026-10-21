package com.wepwawet.proxy

import com.wepwawet.log.WepWawetLogger
import java.security.SecureRandom
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import java.security.MessageDigest

/**
 * PairingCrypto — the cryptographic primitives used by the WE pairing protocol.
 *
 * !! WARNING — UNVERIFIED IMPLEMENTATION !!
 *
 * Per research Task 1-B, the WE developer "Biohazard" confirmed Wine/Proton cannot
 * complete the pairing handshake because Wine's `bcrypt` library is missing an API
 * WE uses. This means WE's pairing crypto uses one of:
 *   - BCryptGenerateSymmetricKey + BCryptEncrypt (AES-256-GCM) — common for PIN protocols
 *   - BCryptHash + BCryptDeriveKeyPBKDF2 (key derivation)
 *   - BCryptSecretAgreement + BCryptDeriveKey (ECDH — unlikely for a 4-digit PIN protocol)
 *
 * We hypothesize the algorithm is:
 *   salt = "WallpaperEnginePairingSalt" (fixed string)
 *   iterations = 100000 (PBKDF2 standard)
 *   keyLen = 32 bytes (AES-256)
 *   key = PBKDF2-HMAC-SHA256(pin, salt, iterations, keyLen)
 *   AES-256-GCM with 12-byte random IV per message, 16-byte tag
 *
 * But THIS IS UNVERIFIED. Until the official WE Android APK is decompiled with jadx
 * and the pairing client code is extracted, we cannot be sure.
 *
 * Until verified, this class provides:
 *   - The hypothesized derivation (so we can test it against a real WE session)
 *   - Hook points to swap in alternative algorithms once we know the real one
 *   - Extensive logging of every crypto operation for the RE effort
 *
 * When RE confirms the algorithm, this file will be updated and the stub replaced.
 */
class PairingCrypto {

    /**
     * Derive a 256-bit AES key from the 4-digit PIN.
     *
     * Hypothesized algorithm (UNVERIFIED):
     *   PBKDF2-HMAC-SHA256(password = pin, salt = fixed, iterations = 100000, keyLen = 32)
     *
     * The salt and iteration count are educated guesses — they need to match the WE
     * Android app's implementation exactly. Once jadx-confirmed, these constants will be
     * set in stone (changing them = breaking pairing).
     */
    fun deriveKeyFromPin(pin: String): SecretKey {
        require(pin.length == 4 && pin.all { it.isDigit() }) { "PIN must be 4 digits" }
        val salt = HYPOTHESIZED_SALT.toByteArray(Charsets.US_ASCII)
        val iterations = HYPOTHESIZED_ITERATIONS
        val keyLen = 32  // AES-256

        // PBKDF2-HMAC-SHA256 implementation using javax.crypto
        val spec = javax.crypto.spec.PBEKeySpec(pin.toCharArray(), salt, iterations, keyLen * 8)
        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val rawKey = factory.generateSecret(spec).encoded
        val key = SecretKeySpec(rawKey, "AES")
        WepWawetLogger.proxyDebug("deriveKeyFromPin",
            "iterations" to iterations, "salt_len" to salt.size, "key_len" to keyLen)
        // Don't log the key itself, ever — just that it was derived.
        return key
    }

    /**
     * Encrypt a plaintext message for the wire protocol.
     * Returns: 12-byte IV || ciphertext || 16-byte GCM tag (concatenated).
     */
    fun encrypt(key: SecretKey, plaintext: ByteArray): ByteArray {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val ciphertextWithTag = cipher.doFinal(plaintext)
        return iv + ciphertextWithTag
    }

    /**
     * Decrypt a wire-protocol message (12-byte IV + ciphertext + 16-byte tag).
     * Returns null on auth failure (tag mismatch — wrong key, wrong PIN).
     */
    fun decrypt(key: SecretKey, ivWithCiphertext: ByteArray): ByteArray? {
        if (ivWithCiphertext.size < 12 + 16) return null  // IV + minimum tag
        val iv = ivWithCiphertext.copyOfRange(0, 12)
        val ciphertextWithTag = ivWithCiphertext.copyOfRange(12, ivWithCiphertext.size)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.doFinal(ciphertextWithTag)
        } catch (e: Exception) {
            WepWawetLogger.proxyDebug("decrypt failed", "exception" to e.javaClass.name)
            null
        }
    }

    /**
     * Generate a fingerprint hash of a key (for the RE effort — to compare keys derived
     * by different algorithms without logging the key itself).
     */
    fun fingerprint(key: SecretKey): String {
        val md = MessageDigest.getInstance("SHA-256")
        val hash = md.digest(key.encoded)
        return hash.joinToString("") { "%02x".format(it) }.take(16)
    }

    companion object {
        // === HYPOTHESIZED CONSTANTS — UNVERIFIED ===
        // These are educated guesses based on common Windows bcrypt patterns.
        // The WE Android APK decompile (jadx) will reveal the real values.
        // If we guess wrong, pairing will fail with a tag mismatch — which is fine,
        // it tells us the algorithm differs and we need to RE more.
        const val HYPOTHESIZED_SALT = "WallpaperEnginePairingSalt"
        const val HYPOTHESIZED_ITERATIONS = 100000

        // Alternative hypotheses to try if the first doesn't work:
        const val ALT_SALT_1 = ""  // empty salt (some lazy impls)
        const val ALT_SALT_2 = "wallpaper_engine"  // lowercase variant
        const val ALT_ITERATIONS_1 = 10000  // fewer iterations
        const val ALT_ITERATIONS_2 = 1  // some PIN protocols skip PBKDF2 entirely
    }
}
