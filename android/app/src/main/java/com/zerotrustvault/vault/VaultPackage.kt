package com.zerotrustvault.vault

import com.facebook.react.ReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.uimanager.ViewManager
import com.zerotrustvault.vault.ui.PinPadViewManager
import com.zerotrustvault.vault.ui.SecureMediaViewManager

class VaultPackage : ReactPackage {

    override fun createNativeModules(reactContext: ReactApplicationContext): List<NativeModule> =
        listOf(VaultModule(reactContext))

    override fun createViewManagers(reactContext: ReactApplicationContext): List<ViewManager<*, *>> =
        listOf(PinPadViewManager(), SecureMediaViewManager())
}
