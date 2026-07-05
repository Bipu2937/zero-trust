package com.zerotrustvault.vault.ui

import com.facebook.react.common.MapBuilder
import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.annotations.ReactProp

class PinPadViewManager : SimpleViewManager<PinPadView>() {

    override fun getName(): String = "ZTVPinPad"

    override fun createViewInstance(reactContext: ThemedReactContext): PinPadView =
        PinPadView(reactContext)

    @ReactProp(name = "mode")
    fun setMode(view: PinPadView, mode: String?) {
        view.mode = mode ?: "verify"
    }

    override fun getExportedCustomDirectEventTypeConstants(): Map<String, Any> =
        MapBuilder.of(
            PinPadView.EVENT_NAME,
            MapBuilder.of("registrationName", PinPadView.EVENT_NAME),
        )
}
