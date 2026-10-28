package com.wepwawet.pairing

import android.content.Context
import android.util.Base64
import com.wepwawet.log.WepWawetLogger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * PairingIdentity — persistent pairing identity (UUID + RSA keypair).
 *
 * v0.7.1 (round 6 — pairing persistence fix):
 *
 * Problem: every time the app started (or the foreground service restarted
 * for any reason — MainActivity.onCreate, PairingScreen appearance, PIN
 * change), [WeDiscoveryBroadcaster.broadcastLoop] generated a NEW random
 * UUID via `java.util.UUID.randomUUID()`, and [WePairingServer.start]
 * generated a NEW RSA keypair via [generateRsaKeyPair]. So after every
 * restart or app update the phone saw a brand-new computer — even though
 * the user had already paired. The phone's pairing database keyed by
 * (deviceUuid, publicKey) didn't match, so the phone asked for a new PIN
 * every time. Worse, the old "paired device" entry in the phone's
 * SharedPreferences would never be reused because the GUID + RSA pubkey
 * didn't match the previous pairing.
 *
 * Fix: this object persists the GUID and the RSA keypair ONCE per install.
 *   - GUID: stored as a String in `wepwawet_pairing` SharedPreferences,
 *     generated once on first call to [getGuid], then reused forever
 *     (until the user clears app data).
 *   - RSA keypair: stored as Base64 X509 public + Base64 PKCS8 private
 *     in the same SharedPreferences file. Generated once on first call
 *     to [loadOrCreateKeyPair], then reused forever. If decoding fails
 *     (corrupt Base64, wrong key algorithm, etc.), we regenerate a fresh
 *     keypair and persist it.
 *
 * Both writes use `commit()` (NOT `apply()`) — synchronous disk persistence
 * so the identity is durable across crashes/reboots even if the process is
 * killed before `apply()`'s async disk write completes.
 *
 * RSA size: 1024 bits — matches [WePairingServer.RSA_KEY_SIZE] (the wire
 * protocol sends a 128-byte modulus, verified from real PC capture).
 */
object PairingIdentity {

    private const val TAG = "PairingIdentity"

    /**
     * SharedPreferences file name — shared with [PairingService] so the
     * pairing PIN, paired-device name, GUID, and RSA key all live in the
     * same file (single source of truth for pairing state).
     */
    private const val PREFS_NAME = "wepwawet_pairing"

    private const val KEY_GUID = "device_guid"
    private const val KEY_RSA_PUBLIC = "rsa_public_b64"
    private const val KEY_RSA_PRIVATE = "rsa_private_b64"

    /**
     * RSA key size in bits — must match [WePairingServer.RSA_KEY_SIZE]
     * (1024 bits → 128-byte modulus → 128-byte RSA ciphertext blocks).
     * Changing this without updating the phone's expectations would
     * break the wire protocol.
     */
    private const val RSA_KEY_SIZE = 1024

    /**
     * Get the persistent device GUID (UUID string, uppercase, no braces).
     *
     * On first call, generates a fresh UUID and persists it synchronously
     * via `commit()`. On subsequent calls (across app restarts/updates),
     * returns the same UUID.
     *
     * @param context any Context — uses applicationContext to access
     *   SharedPreferences.
     * @return the persistent GUID string, e.g. "CEE54009-EDB4-4892-8C02-AFDCC76752B9".
     */
    fun getGuid(context: Context): String {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Read existing GUID if present.
        val existing = prefs.getString(KEY_GUID, null)
        if (existing != null && existing.isNotBlank()) {
            return existing
        }
        // Generate a new UUID and persist synchronously.
        val newGuid = java.util.UUID.randomUUID().toString().uppercase()
        // v0.7.1 (round 6): use commit() (NOT apply()) so the write is
        // durable across crashes/reboots even if the process is killed
        // before apply()'s async disk write completes. The pairing
        // identity MUST be persistent — losing it means the phone sees
        // a brand-new computer on next launch and re-prompts for PIN.
        prefs.edit().putString(KEY_GUID, newGuid).commit()
        WepWawetLogger.proxy("$TAG: generated new device GUID",
            "guid" to newGuid,
            "rsa_key_size" to RSA_KEY_SIZE)
        return newGuid
    }

    /**
     * Load (or create) the persistent RSA keypair.
     *
     * On first call, generates a fresh RSA-1024 keypair and persists both
     * the public key (X509 encoded → Base64) and private key (PKCS8
     * encoded → Base64) synchronously via `commit()`. On subsequent calls
     * (across app restarts/updates), returns the same keypair.
     *
     * If decoding fails (corrupt Base64, missing key, wrong algorithm,
     * etc.), we regenerate a fresh keypair and persist it — the phone's
     * pairing state for THIS GUID will be lost, but the user can re-pair.
     *
     * @param context any Context.
     * @return the persistent [KeyPair] (RSA-1024, X509 public + PKCS8 private).
     */
    fun loadOrCreateKeyPair(context: Context): KeyPair {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val pubB64 = prefs.getString(KEY_RSA_PUBLIC, null)
        val privB64 = prefs.getString(KEY_RSA_PRIVATE, null)

        if (pubB64 != null && privB64 != null) {
            // Try to decode the persisted keypair.
            try {
                val keyFactory = KeyFactory.getInstance("RSA")
                val pubKey: PublicKey = keyFactory.generatePublic(
                    X509EncodedKeySpec(Base64.decode(pubB64, Base64.NO_WRAP))
                )
                val privKey: PrivateKey = keyFactory.generatePrivate(
                    PKCS8EncodedKeySpec(Base64.decode(privB64, Base64.NO_WRAP))
                )
                val kp = KeyPair(pubKey, privKey)
                // Sanity check: verify the key size matches.
                val pubRsa = pubKey as? java.security.interfaces.RSAPublicKey
                if (pubRsa != null && pubRsa.modulus.bitLength() == RSA_KEY_SIZE) {
                    return kp
                }
                WepWawetLogger.proxyWarn("$TAG: persisted RSA key has wrong size — regenerating",
                    "expected_bits" to RSA_KEY_SIZE,
                    "actual_bits" to (pubRsa?.modulus?.bitLength() ?: -1))
            } catch (e: Exception) {
                WepWawetLogger.proxyWarn("$TAG: failed to decode persisted RSA keypair — regenerating",
                    "exception" to e.javaClass.name,
                    "message" to (e.message ?: ""))
            }
        }

        // Generate a fresh RSA keypair and persist it.
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(RSA_KEY_SIZE)
        val kp = generator.generateKeyPair()

        val pubEncoded = Base64.encodeToString(
            kp.public.encoded, Base64.NO_WRAP
        )
        val privEncoded = Base64.encodeToString(
            kp.private.encoded, Base64.NO_WRAP
        )
        // commit() — synchronous write, see getGuid() for rationale.
        prefs.edit()
            .putString(KEY_RSA_PUBLIC, pubEncoded)
            .putString(KEY_RSA_PRIVATE, privEncoded)
            .commit()

        val pubRsa = kp.public as java.security.interfaces.RSAPublicKey
        WepWawetLogger.proxy("$TAG: generated new RSA keypair",
            "modulus_bits" to pubRsa.modulus.bitLength(),
            "exponent" to pubRsa.publicExponent.toString(16).take(16))
        return kp
    }
}
