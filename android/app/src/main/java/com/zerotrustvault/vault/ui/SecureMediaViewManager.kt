package com.zerotrustvault.vault.ui

import com.facebook.react.bridge.ReadableArray
import com.facebook.react.common.MapBuilder
import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.annotations.ReactProp

class SecureMediaViewManager : SimpleViewManager<SecureMediaView>() {

    override fun getName(): String = "ZTVSecureMediaView"

    override fun createViewInstance(reactContext: ThemedReactContext): SecureMediaView =
        SecureMediaView(reactContext)

    @ReactProp(name = "itemId")
    fun setItemId(view: SecureMediaView, itemId: String?) {
        view.itemId = itemId
    }

    @ReactProp(name = "paused")
    fun setPaused(view: SecureMediaView, paused: Boolean) {
        view.paused = paused
    }

    @ReactProp(name = "thumbnail")
    fun setThumbnail(view: SecureMediaView, thumbnail: Boolean) {
        view.thumbnail = thumbnail
    }

    override fun receiveCommand(view: SecureMediaView, commandId: String, args: ReadableArray?) {
        when (commandId) {
            "seek" -> view.seekTo(args?.getInt(0) ?: 0)
        }
    }

    override fun getExportedCustomDirectEventTypeConstants(): Map<String, Any> =
        MapBuilder.of(
            SecureMediaView.EVENT_NAME,
            MapBuilder.of("registrationName", SecureMediaView.EVENT_NAME),
        )

    override fun onDropViewInstance(view: SecureMediaView) {
        view.itemId = null
        super.onDropViewInstance(view)
    }
}
