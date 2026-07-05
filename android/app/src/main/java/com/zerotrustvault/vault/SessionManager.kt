package com.zerotrustvault.vault

import android.content.Context
import android.security.keystore.UserNotAuthenticatedException
import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.zerotrustvault.vault.crypto.KeystoreManager
import com.zerotrustvault.vault.crypto.LockoutPolicy
import com.zerotrustvault.vault.crypto.MemoryUtil
import com.zerotrustvault.vault.crypto.PinManager
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Single authority over the vault's unlocked/locked state.
 *
 * While unlocked, the 32-byte DEK lives HERE and nowhere else — never in
 * JS, never on disk in the clear. [lock] zeroes it. MainActivity calls
 * [lockUnlessAuthenticating] from onPause/onWindowFocusChanged, giving
 * millisecond-level instant lockdown with no grace period.
 */
object SessionManager {

    private const val DEK_FILE = "dek.blob"
    private const val DEK_SIZE = 32
    private const val GCM_IV_SIZE = 12
    private const val GCM_TAG_BITS = 128

    @Volatile private var dek: ByteArray? = null
    @Volatile private var dekKey: SecretKeySpec? = null

    /**
     * True while a BiometricPrompt / device-credential flow is on screen.
     * The credential flow launches a system activity, which pauses ours —
     * without this flag the instant-lock would fire mid-unlock and the app
     * could never be opened.
     */
    @Volatile var authInProgress: Boolean = false
        private set

    /** Wired up by VaultModule so JS learns about lock-state transitions. */
    @Volatile var lockStateListener: ((unlocked: Boolean) -> Unit)? = null

    fun isUnlocked(): Boolean = dekKey != null

    /** The in-memory DEK as a JCA key. Throws if the vault is locked —
     *  every crypto path is therefore hard-gated on session state. */
    fun requireKey(): SecretKey =
        dekKey ?: throw SecurityException("Vault is locked")

    fun lockUnlessAuthenticating() {
        if (!authInProgress) lock()
    }

    @Synchronized
    fun lock() {
        val wasUnlocked = dekKey != null
        MemoryUtil.wipe(dek)
        dek = null
        // Best-effort destroy of the JCA key's internal copy.
        dekKey?.let { key -> runCatching { key.destroy() } }
        dekKey = null
        if (wasUnlocked) {
            lockStateListener?.invoke(false)
        }
    }

    /**
     * Completes an unlock after the PIN has been verified. If the KEK is
     * auth-bound (device has a secure lock screen), a hardware-backed
     * BiometricPrompt (biometric or device credential) is required before
     * the Keystore will unwrap the DEK. Runs [onResult] on the main thread.
     */
    fun unlockAfterPin(
        activity: FragmentActivity,
        onResult: (success: Boolean, error: String?) -> Unit,
    ) {
        val context = activity.applicationContext
        try {
            unwrapOrCreateDek(context)
            onUnlocked(onResult)
            return
        } catch (_: UserNotAuthenticatedException) {
            // Fall through to system auth.
        } catch (t: Throwable) {
            onResult(false, t.message ?: "unlock failed")
            return
        }

        authInProgress = true
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    try {
                        unwrapOrCreateDek(context)
                        onUnlocked(onResult)
                    } catch (t: Throwable) {
                        onResult(false, t.message ?: "unlock failed")
                    } finally {
                        authInProgress = false
                    }
                }

                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    authInProgress = false
                    onResult(false, message.toString())
                }

                override fun onAuthenticationFailed() {
                    // Individual biometric mismatch; prompt stays up.
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Vault")
            .setSubtitle("Hardware-backed key verification")
            .setAllowedAuthenticators(
                Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL,
            )
            .setConfirmationRequired(false)
            .build()
        prompt.authenticate(info)
    }

    private fun onUnlocked(onResult: (Boolean, String?) -> Unit) {
        lockStateListener?.invoke(true)
        onResult(true, null)
    }

    /**
     * Loads dek.blob (IV || KEK-wrapped DEK) and unwraps it inside the
     * Keystore. First run generates a fresh random DEK and persists only
     * the wrapped form.
     */
    @Synchronized
    private fun unwrapOrCreateDek(context: Context) {
        if (dekKey != null) return
        val kek = KeystoreManager.getOrCreateKek(context)
        val file = File(context.filesDir, DEK_FILE)

        val plainDek: ByteArray
        if (file.exists()) {
            val blob = file.readBytes()
            if (blob.size <= GCM_IV_SIZE) throw SecurityException("Corrupt DEK blob")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE, kek,
                GCMParameterSpec(GCM_TAG_BITS, blob, 0, GCM_IV_SIZE),
            )
            plainDek = cipher.doFinal(blob, GCM_IV_SIZE, blob.size - GCM_IV_SIZE)
        } else {
            plainDek = ByteArray(DEK_SIZE).also(SecureRandom()::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, kek) // Keystore generates the IV
            val wrapped = cipher.doFinal(plainDek)
            file.writeBytes(cipher.iv + wrapped)
        }

        dek = plainDek
        dekKey = SecretKeySpec(plainDek, "AES")
    }

    /**
     * Panic wipe: destroys the Keystore keys and deletes every file. With
     * the KEK gone, all ciphertext is permanently undecryptable even if
     * the deleted files were recovered forensically.
     */
    @Synchronized
    fun wipeVault(context: Context) {
        lock()
        KeystoreManager.destroyAllKeys()
        File(context.filesDir, DEK_FILE).delete()
        PinManager.clearPin(context)
        LockoutPolicy.reset(context)
        VaultStore.wipeAll(context)
    }
}
