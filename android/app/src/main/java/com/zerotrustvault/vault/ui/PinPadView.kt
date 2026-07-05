package com.zerotrustvault.vault.ui

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.fragment.app.FragmentActivity
import com.facebook.react.bridge.Arguments
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.events.RCTEventEmitter
import com.zerotrustvault.vault.SessionManager
import com.zerotrustvault.vault.crypto.LockoutPolicy
import com.zerotrustvault.vault.crypto.MemoryUtil
import com.zerotrustvault.vault.crypto.PinManager
import java.security.SecureRandom

/**
 * Fully custom in-app PIN keypad, drawn directly on this view's canvas.
 *
 * Anti-keylogger properties:
 *   * The Android IME (system keyboard) is never invoked, so IME-level
 *     keyloggers and "keyboard replacement" spyware see nothing.
 *   * Digits are captured in onTouchEvent and accumulate in a CharArray
 *     inside this native view. They are never a String, never logged,
 *     and NEVER cross the React Native bridge — JS only receives coarse
 *     events ("wrong_pin", "unlocked"), not what was typed.
 *   * The digit layout is re-shuffled on every attempt, defeating both
 *     smudge attacks and tap-coordinate inference by accessibility or
 *     overlay malware (coordinates alone don't reveal digits).
 *   * FLAG_SECURE on the activity window means the keypad itself cannot
 *     be screen-captured while in use.
 */
@SuppressLint("ViewConstructor")
class PinPadView(private val reactContext: ThemedReactContext) : View(reactContext) {

    companion object {
        const val EVENT_NAME = "onPinEvent"
        private const val MIN_PIN = 4
        private const val MAX_PIN = 8
        private const val KEY_BACKSPACE = 9
        private const val KEY_ZERO = 10
        private const val KEY_SUBMIT = 11
        private const val ROWS = 4
        private const val COLS = 3
    }

    /** "verify" unlocks an existing vault; "setup" enrolls a new PIN. */
    var mode: String = "verify"
        set(value) {
            field = value
            resetEntry()
        }

    private val random = SecureRandom()
    private var digitLayout: CharArray = shuffledDigits()

    private val pin = CharArray(MAX_PIN)
    private var pinLength = 0

    // Setup-mode confirmation stage.
    private var pendingPin: CharArray? = null
    private var pendingLength = 0

    private var pressedKey = -1
    private var busy = false

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#141B24")
        style = Paint.Style.FILL
    }
    private val keyPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#22303F")
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E6EDF3")
        textAlign = Paint.Align.CENTER
    }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4FD1C5")
        textAlign = Paint.Align.CENTER
    }
    private val dotEmptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2A3644")
        style = Paint.Style.FILL
    }
    private val dotFilledPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4FD1C5")
        style = Paint.Style.FILL
    }

    init {
        isClickable = true
        isFocusable = true
        // Never let accessibility services introspect keypad contents.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    /* ------------------------------ layout ---------------------------- */

    private val dotsAreaHeight: Float
        get() = height * 0.15f

    private fun keyRect(index: Int): RectF {
        val row = index / COLS
        val col = index % COLS
        val padH = width * 0.04f
        val padV = height * 0.02f
        val cellW = (width - padH * (COLS + 1)) / COLS
        val cellH = (height - dotsAreaHeight - padV * (ROWS + 1)) / ROWS
        val left = padH + col * (cellW + padH)
        val top = dotsAreaHeight + padV + row * (cellH + padV)
        return RectF(left, top, left + cellW, top + cellH)
    }

    private fun keyAt(x: Float, y: Float): Int {
        for (i in 0 until ROWS * COLS) {
            if (keyRect(i).contains(x, y)) return i
        }
        return -1
    }

    private fun labelFor(index: Int): String = when (index) {
        KEY_BACKSPACE -> "⌫"
        KEY_SUBMIT -> "✓"
        KEY_ZERO -> digitLayout[9].toString()
        else -> digitLayout[index].toString()
    }

    /* ------------------------------- draw ------------------------------ */

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        // PIN progress dots.
        val dotRadius = height * 0.012f
        val dotGap = dotRadius * 4
        val totalWidth = (MAX_PIN - 1) * dotGap
        val startX = width / 2f - totalWidth / 2f
        val dotY = dotsAreaHeight / 2f
        for (i in 0 until MAX_PIN) {
            canvas.drawCircle(
                startX + i * dotGap, dotY, dotRadius,
                if (i < pinLength) dotFilledPaint else dotEmptyPaint,
            )
        }

        // Keys.
        val corner = width * 0.045f
        for (i in 0 until ROWS * COLS) {
            val rect = keyRect(i)
            canvas.drawRoundRect(rect, corner, corner, if (i == pressedKey) keyPressedPaint else keyPaint)
            val paint = if (i == KEY_SUBMIT) accentPaint else labelPaint
            paint.textSize = rect.height() * 0.42f
            val textY = rect.centerY() - (paint.descent() + paint.ascent()) / 2f
            canvas.drawText(labelFor(i), rect.centerX(), textY, paint)
        }
    }

    /* ------------------------------- input ----------------------------- */

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedKey = keyAt(event.x, event.y)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val key = keyAt(event.x, event.y)
                if (key >= 0 && key == pressedKey && !busy) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onKey(key)
                }
                pressedKey = -1
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedKey = -1
                invalidate()
            }
        }
        return true
    }

    private fun onKey(key: Int) {
        when (key) {
            KEY_BACKSPACE -> {
                if (pinLength > 0) {
                    pinLength--
                    pin[pinLength] = ' '
                }
            }
            KEY_SUBMIT -> submit()
            else -> {
                if (pinLength < MAX_PIN) {
                    pin[pinLength] = if (key == KEY_ZERO) digitLayout[9] else digitLayout[key]
                    pinLength++
                }
            }
        }
        invalidate()
    }

    /* ------------------------------ pin flow ---------------------------- */

    private fun submit() {
        if (pinLength < MIN_PIN) {
            emit("too_short")
            return
        }
        when (mode) {
            "setup" -> submitSetup()
            else -> submitVerify()
        }
    }

    private fun submitVerify() {
        val context = reactContext.applicationContext
        val waitMs = LockoutPolicy.retryInMs(context)
        if (waitMs > 0) {
            resetEntry()
            emit("lockout", waitMs)
            return
        }
        val ok = PinManager.verifyPin(context, pin, pinLength)
        resetEntry()
        if (!ok) {
            val retry = LockoutPolicy.recordFailure(context)
            emit("wrong_pin", retry)
            return
        }
        LockoutPolicy.reset(context)
        completeUnlock()
    }

    private fun submitSetup() {
        val pending = pendingPin
        if (pending == null) {
            pendingPin = pin.copyOf()
            pendingLength = pinLength
            resetEntry()
            emit("confirm_stage")
            return
        }
        val match = pinLength == pendingLength &&
            constantTimeEquals(pin, pending, pinLength)
        if (!match) {
            MemoryUtil.wipe(pending)
            pendingPin = null
            pendingLength = 0
            resetEntry()
            emit("mismatch")
            return
        }
        val context = reactContext.applicationContext
        PinManager.setPin(context, pin, pinLength)
        LockoutPolicy.reset(context)
        MemoryUtil.wipe(pending)
        pendingPin = null
        pendingLength = 0
        resetEntry()
        completeUnlock()
    }

    private fun completeUnlock() {
        val activity = reactContext.currentActivity as? FragmentActivity
        if (activity == null) {
            emit("error", message = "No activity")
            return
        }
        busy = true
        activity.runOnUiThread {
            SessionManager.unlockAfterPin(activity) { success, error ->
                busy = false
                if (success) {
                    emit("unlocked")
                } else {
                    emit("auth_failed", message = error)
                }
            }
        }
    }

    private fun constantTimeEquals(a: CharArray, b: CharArray, length: Int): Boolean {
        var diff = 0
        for (i in 0 until length) {
            diff = diff or (a[i].code xor b[i].code)
        }
        return diff == 0
    }

    private fun resetEntry() {
        MemoryUtil.wipe(pin)
        pinLength = 0
        digitLayout = shuffledDigits()
        invalidate()
    }

    private fun shuffledDigits(): CharArray {
        val digits = ('0'..'9').toMutableList()
        for (i in digits.indices.reversed()) {
            val j = random.nextInt(i + 1)
            val tmp = digits[i]; digits[i] = digits[j]; digits[j] = tmp
        }
        return digits.toCharArray()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        MemoryUtil.wipe(pin)
        MemoryUtil.wipe(pendingPin)
        pendingPin = null
        pinLength = 0
    }

    /* ------------------------------ events ----------------------------- */

    private fun emit(type: String, retryInMs: Long = 0, message: String? = null) {
        val map = Arguments.createMap().apply {
            putString("type", type)
            if (retryInMs > 0) putDouble("retryInMs", retryInMs.toDouble())
            if (message != null) putString("message", message)
        }
        reactContext.getJSModule(RCTEventEmitter::class.java)
            .receiveEvent(id, EVENT_NAME, map)
    }
}
