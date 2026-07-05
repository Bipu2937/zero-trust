package com.zerotrustvault

import android.os.Bundle
import android.view.WindowManager
import com.facebook.react.ReactActivity
import com.facebook.react.ReactActivityDelegate
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint.fabricEnabled
import com.facebook.react.defaults.DefaultReactActivityDelegate
import com.zerotrustvault.vault.SessionManager

class MainActivity : ReactActivity() {

    override fun getMainComponentName(): String = "ZeroTrustVault"

    override fun createReactActivityDelegate(): ReactActivityDelegate =
        DefaultReactActivityDelegate(this, mainComponentName, fabricEnabled)

    override fun onCreate(savedInstanceState: Bundle?) {
        // FLAG_SECURE before super.onCreate: no frame of this window can ever
        // be captured. Screenshots, screen recorders, accessibility-based
        // scrapers, the Recents thumbnail and MediaProjection all receive
        // black frames. Set first so not even the first laid-out frame leaks.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        // Pass null: never restore state. A restored instance could resurrect
        // UI from a previous (authenticated) session.
        super.onCreate(null)
    }

    /**
     * Instant lockdown, no grace period. onPause fires the millisecond the
     * app stops being the foreground-resumed activity: home button, screen
     * off, notification shade "half-swipe" app switch, split-screen focus
     * loss, another activity drawing on top — everything.
     */
    override fun onPause() {
        super.onPause()
        SessionManager.lockUnlessAuthenticating()
    }

    /**
     * Belt and braces: also lock on focus loss that does not pause the
     * activity (e.g. certain overlay/multi-window cases).
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) {
            SessionManager.lockUnlessAuthenticating()
        }
    }
}
