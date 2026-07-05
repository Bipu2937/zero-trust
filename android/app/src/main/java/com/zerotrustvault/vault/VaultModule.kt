package com.zerotrustvault.vault

import android.app.Activity
import android.content.Intent
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.facebook.react.bridge.ActivityEventListener
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.zerotrustvault.vault.crypto.KeystoreManager
import com.zerotrustvault.vault.crypto.PinManager
import com.zerotrustvault.vault.crypto.VaultCipher
import java.io.RandomAccessFile
import java.util.concurrent.Executors

/**
 * The ONLY doorway between JavaScript and the vault — and it is a command
 * interface, not a data interface. JS sends lightweight instructions
 * ("import", "export item X", "lock") and receives metadata and status
 * events. Passwords, keys and media bytes never appear on this bridge, so
 * a fully compromised JS bundle (malicious npm package, injected script)
 * still cannot read a single plaintext byte.
 */
class VaultModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext), ActivityEventListener {

    companion object {
        private const val REQUEST_IMPORT = 42001
        private const val REQUEST_EXPORT = 42002
        const val LOCK_EVENT = "vaultLockState"
    }

    private val executor = Executors.newSingleThreadExecutor()

    // SAF flows background the activity, which fires the instant lock.
    // Work is therefore queued and drained on the next unlock.
    private var pendingImport: PendingImport? = null
    private var pendingExport: PendingExport? = null

    private class PendingImport(
        val promise: Promise,
        val deleteOriginals: Boolean,
        var uris: List<android.net.Uri>? = null,
    )

    private class PendingExport(
        val promise: Promise,
        val itemId: String,
        var target: android.net.Uri? = null,
    )

    init {
        reactContext.addActivityEventListener(this)
        SessionManager.lockStateListener = { unlocked ->
            emit(LOCK_EVENT, Arguments.createMap().apply { putBoolean("unlocked", unlocked) })
            if (unlocked) drainPendingWork()
        }
    }

    override fun getName(): String = "VaultModule"

    /* ------------------------------ state ------------------------------ */

    @ReactMethod
    fun getState(promise: Promise) {
        try {
            val map = Arguments.createMap().apply {
                putBoolean("pinSet", PinManager.isPinSet(reactContext))
                putBoolean("unlocked", SessionManager.isUnlocked())
                putBoolean("deviceSecure", KeystoreManager.isDeviceSecure(reactContext))
                putBoolean("strongBox", KeystoreManager.lastKekStrongBoxBacked)
            }
            promise.resolve(map)
        } catch (t: Throwable) {
            promise.reject("E_STATE", t)
        }
    }

    @ReactMethod
    fun lock() {
        SessionManager.lock()
    }

    /** Panic wipe: Keystore keys destroyed, every file shredded. */
    @ReactMethod
    fun wipeVault(promise: Promise) {
        executor.execute {
            try {
                SessionManager.wipeVault(reactContext)
                promise.resolve(true)
            } catch (t: Throwable) {
                promise.reject("E_WIPE", t)
            }
        }
    }

    /* ------------------------------ items ------------------------------ */

    @ReactMethod
    fun listItems(promise: Promise) {
        executor.execute {
            try {
                val items = VaultStore.loadIndex(reactContext, SessionManager.requireKey())
                val array = Arguments.createArray()
                items.sortedByDescending { it.createdAt }.forEach { item ->
                    array.pushMap(itemToMap(item))
                }
                promise.resolve(array)
            } catch (t: Throwable) {
                promise.reject("E_LOCKED", t)
            }
        }
    }

    @ReactMethod
    fun deleteItem(itemId: String, promise: Promise) {
        executor.execute {
            try {
                VaultStore.removeItem(reactContext, SessionManager.requireKey(), itemId)
                promise.resolve(true)
            } catch (t: Throwable) {
                promise.reject("E_DELETE", t)
            }
        }
    }

    /* ------------------------------ import ----------------------------- */

    /**
     * Opens the SAF picker (permission-less, user-mediated). Each selected
     * file is streamed chunk-by-chunk through AES-256-GCM into isolated
     * internal storage. With [deleteOriginals], the source documents are
     * deleted afterwards — the "Move to Secure Folder" experience.
     */
    @ReactMethod
    fun importMedia(deleteOriginals: Boolean, promise: Promise) {
        val activity = currentActivity
        if (activity == null) {
            promise.reject("E_ACTIVITY", "No activity")
            return
        }
        if (pendingImport != null) {
            promise.reject("E_BUSY", "Import already in progress")
            return
        }
        pendingImport = PendingImport(promise, deleteOriginals)
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        activity.startActivityForResult(intent, REQUEST_IMPORT)
    }

    /* ------------------------------ export ----------------------------- */

    /**
     * Reverse flow: user picks a destination via CREATE_DOCUMENT (Downloads,
     * SD card, anywhere) and the item is decrypted chunk-by-chunk into it.
     * Requires an unlocked vault — export is an explicit, authorized act.
     */
    @ReactMethod
    fun exportItem(itemId: String, promise: Promise) {
        val activity = currentActivity
        if (activity == null) {
            promise.reject("E_ACTIVITY", "No activity")
            return
        }
        if (pendingExport != null) {
            promise.reject("E_BUSY", "Export already in progress")
            return
        }
        val item = try {
            VaultStore.findItem(reactContext, SessionManager.requireKey(), itemId)
        } catch (t: Throwable) {
            promise.reject("E_LOCKED", t)
            return
        }
        if (item == null) {
            promise.reject("E_NOT_FOUND", "Unknown item")
            return
        }
        pendingExport = PendingExport(promise, itemId)
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = item.mime
            putExtra(Intent.EXTRA_TITLE, item.name)
        }
        activity.startActivityForResult(intent, REQUEST_EXPORT)
    }

    /* -------------------------- activity results ------------------------ */

    override fun onActivityResult(activity: Activity, requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            REQUEST_IMPORT -> {
                val pending = pendingImport ?: return
                if (resultCode != Activity.RESULT_OK || data == null) {
                    pendingImport = null
                    pending.promise.resolve(importResult(0, 0))
                    return
                }
                val uris = mutableListOf<android.net.Uri>()
                data.clipData?.let { clip ->
                    for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
                } ?: data.data?.let { uris.add(it) }
                pending.uris = uris
                drainPendingWork()
            }
            REQUEST_EXPORT -> {
                val pending = pendingExport ?: return
                val uri = if (resultCode == Activity.RESULT_OK) data?.data else null
                if (uri == null) {
                    pendingExport = null
                    pending.promise.resolve(false)
                    return
                }
                pending.target = uri
                drainPendingWork()
            }
        }
    }

    override fun onNewIntent(intent: Intent) = Unit

    /**
     * The SAF picker backgrounds the app, so by the time results arrive
     * the vault has instant-locked. Queued work runs immediately if still
     * unlocked, otherwise automatically after the next successful unlock.
     */
    private fun drainPendingWork() {
        if (!SessionManager.isUnlocked()) return
        pendingImport?.let { pending ->
            if (pending.uris != null) {
                pendingImport = null
                executor.execute { runImport(pending) }
            }
        }
        pendingExport?.let { pending ->
            if (pending.target != null) {
                pendingExport = null
                executor.execute { runExport(pending) }
            }
        }
    }

    private fun runImport(pending: PendingImport) {
        var imported = 0
        var failed = 0
        val resolver = reactContext.contentResolver
        for (uri in pending.uris.orEmpty()) {
            try {
                val key = SessionManager.requireKey()
                var name = "media"
                var declaredSize = -1L
                resolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIdx >= 0) cursor.getString(nameIdx)?.let { name = it }
                        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) declaredSize = cursor.getLong(sizeIdx)
                    }
                }
                val mime = resolver.getType(uri) ?: "application/octet-stream"
                val id = VaultStore.newId()
                val target = VaultStore.mediaFile(reactContext, id)

                val written = resolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { out ->
                        VaultCipher.encryptStream(key, input, out)
                    }
                } ?: throw java.io.IOException("Cannot open source")
                VaultCipher.patchPlainSize(target, written)

                VaultStore.addItem(
                    reactContext, key,
                    VaultItem(
                        id = id,
                        name = name,
                        mime = mime,
                        kind = VaultItem.kindForMime(mime),
                        size = if (declaredSize >= 0) declaredSize else written,
                        createdAt = System.currentTimeMillis(),
                    ),
                )

                if (pending.deleteOriginals) {
                    // Best effort: works for providers supporting delete
                    // (Downloads, most file managers); MediaStore items may
                    // require the user to remove them from Gallery manually.
                    runCatching { DocumentsContract.deleteDocument(resolver, uri) }
                }
                imported++
            } catch (_: Throwable) {
                failed++
            }
        }
        pending.promise.resolve(importResult(imported, failed))
        emit("vaultChanged", Arguments.createMap())
    }

    private fun runExport(pending: PendingExport) {
        try {
            val key = SessionManager.requireKey()
            val file = VaultStore.mediaFile(reactContext, pending.itemId)
            reactContext.contentResolver.openOutputStream(pending.target!!)?.use { out ->
                RandomAccessFile(file, "r").use { raf ->
                    VaultCipher.decryptStream(key, raf, out)
                }
            } ?: throw java.io.IOException("Cannot open destination")
            pending.promise.resolve(true)
        } catch (t: Throwable) {
            pending.promise.reject("E_EXPORT", t)
        }
    }

    /* ------------------------------ helpers ----------------------------- */

    private fun itemToMap(item: VaultItem): WritableMap = Arguments.createMap().apply {
        putString("id", item.id)
        putString("name", item.name)
        putString("mime", item.mime)
        putString("kind", item.kind)
        putDouble("size", item.size.toDouble())
        putDouble("createdAt", item.createdAt.toDouble())
    }

    private fun importResult(imported: Int, failed: Int): WritableMap =
        Arguments.createMap().apply {
            putInt("imported", imported)
            putInt("failed", failed)
        }

    private fun emit(event: String, params: WritableMap) {
        if (reactContext.hasActiveReactInstance()) {
            reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit(event, params)
        }
    }

    /* Required stubs for NativeEventEmitter. */
    @ReactMethod
    fun addListener(eventName: String) = Unit

    @ReactMethod
    fun removeListeners(count: Int) = Unit
}
