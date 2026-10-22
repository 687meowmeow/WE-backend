package com.wepwawet.proxy

import com.wepwawet.log.WepWawetLogger
import java.security.SecureRandom
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec

/**
 * PairingCrypto — the cryptographic primitives used by the WE pairing protocol.
 *
 * PROTOCOL VERIFIED FROM REAL WIRESHARK CAPTURE (2026-10-22).
 *
 * Previous hypothesis (PBKDF2-HMAC-SHA256 + AES-256-GCM) was WRONG.
 * The real protocol is:
 *   - RSA-1024 with standard exponent e=65537 (sent as 3-byte BE BigInteger `01 00 01`)
 *   - Modulus sent as 128 raw BE bytes (no sign byte)
 *   - AES key (size TBD — 16/24/32 bytes) encrypted with RSA/ECB/PKCS1Padding
 *   - AES IV (16 bytes) encrypted with RSA/ECB/PKCS1Padding (separate RSA block)
 *   - Third RSA-encrypted block (purpose TBD — possibly MAC/session token)
 *   - EncAuthRequest encrypted with AES/CBC/PKCS5Padding (= PKCS7 in Java for 16-byte blocks)
 *   - Length prefixes are 4-byte LITTLE-ENDIAN (NOT Java's DataInputStream default which is BE)
 *
 * The phone is the client (encrypts AES key with PC's RSA pubkey);
 * the PC (us) is the server (decrypts with our RSA private key).
 *
 * See docs/RE_FINDINGS_VERIFIED.md for the full decoded protocol, including
 * a frame-by-frame breakdown of the captured exchange.
 */
class PairingCrypto {

    companion object {
        // VERIFIED from real capture
        const val RSA_KEY_SIZE_BITS = 1024
        const val RSA_BLOCK_SIZE_BYTES = 128
        const val RSA_EXPONENT = 65537  // 0x10001, sent as 3-byte BE BigInteger `01 00 01`
        const val AES_IV_SIZE_BYTES = 16
    }

    /**
     * Encrypt a plaintext message using AES/CBC/PKCS5Padding.
     * Returns the ciphertext bytes (no IV prefix — IV is sent separately).
     *
     * The IV must be the same one negotiated during the AES key exchange
     * (passed via the IvParameterSpec).
     */
    fun encryptAesCbc(key: SecretKey, iv: IvParameterSpec, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key, iv)
        val ct = cipher.doFinal(plaintext)
        WepWawetLogger.proxyDebug("encryptAesCbc",
            "plaintext_len" to plaintext.size,
            "ciphertext_len" to ct.size)
        return ct
    }

    /**
     * Decrypt an AES/CBC/PKCS5Padding ciphertext.
     * Returns the plaintext bytes (with PKCS5 padding stripped automatically by JCE).
     */
    fun decryptAesCbc(key: SecretKey, iv: IvParameterSpec, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, key, iv)
        val pt = cipher.doFinal(ciphertext)
        WepWawetLogger.proxyDebug("decryptAesCbc",
            "ciphertext_len" to ciphertext.size,
            "plaintext_len" to pt.size)
        return pt
    }

    /**
     * Generate a random AES key for testing (used by the standalone Python server).
     * The actual WE protocol derives the AES key on the phone side and sends it
     * RSA-encrypted to the PC, so this method is not normally called on the PC side.
     */
    fun generateAesKey(keySizeBits: Int = 256): SecretKey {
        val gen = javax.crypto.KeyGenerator.getInstance("AES")
        gen.init(keySizeBits)
        return gen.generateKey()
    }

    /**
     * Generate a random AES IV (16 bytes for AES-CBC).
     */
    fun generateAesIv(): IvParameterSpec {
        val ivBytes = ByteArray(AES_IV_SIZE_BYTES).also { SecureRandom().nextBytes(it) }
        return IvParameterSpec(ivBytes)
    }

    /**
     * Wrap a raw AES key byte array into a SecretKey object.
     */
    fun secretKeyFromBytes(keyBytes: ByteArray): SecretKey {
        return SecretKeySpec(keyBytes, "AES")
    }

    /**
     * Wrap a raw IV byte array into an IvParameterSpec.
     */
    fun ivFromBytes(ivBytes: ByteArray): IvParameterSpec {
        require(ivBytes.size == AES_IV_SIZE_BYTES) {
            "AES-CBC IV must be $AES_IV_SIZE_BYTES bytes, got ${ivBytes.size}"
        }
        return IvParameterSpec(ivBytes)
    }
}
