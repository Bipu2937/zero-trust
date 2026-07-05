package com.zerotrustvault.vault.crypto

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac

/**
 * PIN verification without the PIN (or anything derivable from it off
 * this device) ever being persisted:
 *
 *   stored digest = HMAC-SHA256( KeystoreHmacKey, salt || pinUtf8 )
 *
 * Because the HMAC key is non-exportable Keystore material, the pin.dat
 * file is cryptographically useless off-device — offline brute force is
 * impossible, unlike a plain salted hash. On-device guessing is throttled
 * by [LockoutPolicy] with exponential backoff.
 *
 * The PIN itself only ever exists as a CharArray inside the native
 * PinPadView and this class; it is never a String, never crosses the RN
 * bridge, and every buffer is wiped after use.
 */
object PinManager {

    private const val PIN_FILE = "pin.dat"
    private const val SALT_SIZE = 16
    private const val MAC_SIZE = 32

    private fun pinFile(context: Context) = File(context.filesDir, PIN_FILE)

    fun isPinSet(context: Context): Boolean = pinFile(context).exists()

    fun setPin(context: Context, pin: CharArray, length: Int) {
        val salt = ByteArray(SALT_SIZE).also(SecureRandom()::nextBytes)
        val mac = computeMac(salt, pin, length)
        try {
            pinFile(context).writeBytes(salt + mac)
        } finally {
            MemoryUtil.wipe(mac)
        }
    }

    fun verifyPin(context: Context, pin: CharArray, length: Int): Boolean {
        val file = pinFile(context)
        if (!file.exists()) return false
        val stored = file.readBytes()
        if (stored.size != SALT_SIZE + MAC_SIZE) return false
        val salt = stored.copyOfRange(0, SALT_SIZE)
        val expected = stored.copyOfRange(SALT_SIZE, stored.size)
        val actual = computeMac(salt, pin, length)
        try {
            // Constant-time comparison.
            return MessageDigest.isEqual(expected, actual)
        } finally {
            MemoryUtil.wipe(actual)
        }
    }

    fun clearPin(context: Context) {
        pinFile(context).delete()
    }

    private fun computeMac(salt: ByteArray, pin: CharArray, length: Int): ByteArray {
        val pinBytes = MemoryUtil.charsToUtf8(pin, length)
        try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(KeystoreManager.getOrCreatePinHmacKey())
            mac.update(salt)
            mac.update(pinBytes)
            return mac.doFinal()
        } finally {
            MemoryUtil.wipe(pinBytes)
        }
    }
}

/**
 * Exponential-backoff throttle for failed PIN attempts, persisted so a
 * process kill does not reset it. 5 free attempts, then 30s, 60s, 120s...
 * capped at 30 minutes.
 */
object LockoutPolicy {

    private const val PREFS = "ztv_lockout"
    private const val KEY_FAILS = "fails"
    private const val KEY_UNTIL = "until"
    private const val FREE_ATTEMPTS = 5
    private const val BASE_DELAY_MS = 30_000L
    private const val MAX_DELAY_MS = 30 * 60_000L

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Milliseconds until the next attempt is allowed; 0 = allowed now. */
    fun retryInMs(context: Context): Long {
        val until = prefs(context).getLong(KEY_UNTIL, 0)
        return (until - System.currentTimeMillis()).coerceAtLeast(0)
    }

    fun recordFailure(context: Context): Long {
        val p = prefs(context)
        val fails = p.getInt(KEY_FAILS, 0) + 1
        var until = 0L
        if (fails >= FREE_ATTEMPTS) {
            val exponent = (fails - FREE_ATTEMPTS).coerceAtMost(10)
            val delay = (BASE_DELAY_MS shl exponent).coerceAtMost(MAX_DELAY_MS)
            until = System.currentTimeMillis() + delay
        }
        p.edit().putInt(KEY_FAILS, fails).putLong(KEY_UNTIL, until).apply()
        return (until - System.currentTimeMillis()).coerceAtLeast(0)
    }

    fun reset(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
