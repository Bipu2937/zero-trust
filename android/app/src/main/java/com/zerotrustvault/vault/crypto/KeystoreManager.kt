package com.zerotrustvault.vault.crypto

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Owns every key in the app. All keys live inside AndroidKeyStore
 * (StrongBox secure element when the hardware has one, TEE otherwise) and
 * are NON-EXPORTABLE: key material physically cannot leave secure hardware,
 * so even a full compromise of the app process cannot steal them.
 *
 * Key hierarchy:
 *   KEK  (AES-256-GCM, Keystore, user-auth-bound) — wraps the DEK.
 *   DEK  (random 32B, stored on disk ONLY wrapped by the KEK) — encrypts
 *        media files and the vault index. Unwrapped copy exists only in
 *        native process memory while the vault is unlocked, then zeroed.
 *   PIN-HMAC key (HMAC-SHA256, Keystore) — keyed hash of the PIN, making
 *        the stored PIN digest useless off-device (no offline brute force).
 */
object KeystoreManager {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEK_ALIAS = "ztv_kek_v1"
    private const val PIN_HMAC_ALIAS = "ztv_pin_hmac_v1"

    /** Seconds a system authentication (biometric/credential) stays valid
     *  for Keystore operations. Only the KEK unwrap needs it, which happens
     *  once per unlock, so keep it tight. */
    const val AUTH_VALIDITY_SECONDS = 10

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    @Volatile
    var lastKekStrongBoxBacked: Boolean = false
        private set

    fun isDeviceSecure(context: Context): Boolean {
        val kg = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        return kg.isDeviceSecure
    }

    fun isKekAuthBound(context: Context): Boolean = isDeviceSecure(context)

    /** The KEK requires fresh user authentication (biometric or device
     *  credential) before it will unwrap the DEK — hardware-enforced. */
    fun getOrCreateKek(context: Context): SecretKey {
        (keyStore.getKey(KEK_ALIAS, null) as? SecretKey)?.let { return it }

        val builder = KeyGenParameterSpec.Builder(
            KEK_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Key unusable while the device itself is locked.
            builder.setUnlockedDeviceRequired(true)
        }

        if (isDeviceSecure(context)) {
            builder.setUserAuthenticationRequired(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setUserAuthenticationParameters(
                    AUTH_VALIDITY_SECONDS,
                    KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
                )
            } else {
                @Suppress("DEPRECATION")
                builder.setUserAuthenticationValidityDurationSeconds(AUTH_VALIDITY_SECONDS)
            }
        }
        // else: device has no lock screen. The vault still works (gated by
        // the in-app PIN + Keystore-HMAC), but hardware auth-binding is
        // impossible; surfaced to the UI via getState().

        return generateAes(builder)
    }

    private fun generateAes(builder: KeyGenParameterSpec.Builder): SecretKey {
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                generator.init(builder.setIsStrongBoxBacked(true).build())
                return generator.generateKey().also { lastKekStrongBoxBacked = true }
            } catch (_: StrongBoxUnavailableException) {
                builder.setIsStrongBoxBacked(false)
            }
        }
        generator.init(builder.build())
        return generator.generateKey().also { lastKekStrongBoxBacked = false }
    }

    /** HMAC key for PIN verification. Not auth-bound: the PIN check is the
     *  first factor and must work before any system auth prompt. */
    fun getOrCreatePinHmacKey(): SecretKey {
        (keyStore.getKey(PIN_HMAC_ALIAS, null) as? SecretKey)?.let { return it }

        val builder = KeyGenParameterSpec.Builder(
            PIN_HMAC_ALIAS,
            KeyProperties.PURPOSE_SIGN,
        ).setDigests(KeyProperties.DIGEST_SHA256)

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                generator.init(builder.setIsStrongBoxBacked(true).build())
                return generator.generateKey()
            } catch (_: StrongBoxUnavailableException) {
                builder.setIsStrongBoxBacked(false)
            } catch (_: java.security.InvalidAlgorithmParameterException) {
                // Some StrongBox implementations lack HMAC; fall back to TEE.
                builder.setIsStrongBoxBacked(false)
            }
        }
        generator.init(builder.build())
        return generator.generateKey()
    }

    /** Destroys all Keystore keys — renders every wrapped DEK and PIN digest
     *  permanently undecryptable. Used by panic wipe. */
    fun destroyAllKeys() {
        runCatching { keyStore.deleteEntry(KEK_ALIAS) }
        runCatching { keyStore.deleteEntry(PIN_HMAC_ALIAS) }
    }
}
