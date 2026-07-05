package com.zerotrustvault.vault

import android.content.Context
import com.zerotrustvault.vault.crypto.MemoryUtil
import com.zerotrustvault.vault.crypto.VaultCipher
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.SecretKey

/**
 * Item metadata. Lives decrypted only transiently; the index file on disk
 * is itself a ZTV1 (AES-256-GCM) container — file names, MIME types and
 * sizes of vaulted media are sensitive too.
 */
class VaultItem(
    val id: String,
    val name: String,
    val mime: String,
    val kind: String, // "image" | "video"
    val size: Long,
    val createdAt: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("mime", mime)
        .put("kind", kind)
        .put("size", size)
        .put("createdAt", createdAt)

    companion object {
        fun fromJson(o: JSONObject) = VaultItem(
            id = o.getString("id"),
            name = o.getString("name"),
            mime = o.getString("mime"),
            kind = o.getString("kind"),
            size = o.getLong("size"),
            createdAt = o.getLong("createdAt"),
        )

        fun kindForMime(mime: String): String =
            if (mime.startsWith("video/")) "video" else "image"
    }
}

/**
 * Storage layout (all inside the app's isolated internal storage,
 * /data/data/com.zerotrustvault/files — inaccessible to other apps):
 *
 *   files/vault/index.ztv        encrypted JSON index
 *   files/vault/media/<id>.ztv   encrypted media containers
 */
object VaultStore {

    private const val MAX_INDEX_BYTES = 4L * 1024 * 1024

    private fun vaultDir(context: Context) =
        File(context.filesDir, "vault").apply { mkdirs() }

    private fun mediaDir(context: Context) =
        File(vaultDir(context), "media").apply { mkdirs() }

    private fun indexFile(context: Context) = File(vaultDir(context), "index.ztv")

    fun mediaFile(context: Context, id: String): File {
        // ids are UUIDs we generated; reject anything else to make path
        // traversal impossible even if the JS layer is compromised.
        require(id.matches(Regex("^[0-9a-fA-F-]{36}$"))) { "invalid item id" }
        return File(mediaDir(context), "$id.ztv")
    }

    fun newId(): String = UUID.randomUUID().toString()

    @Synchronized
    fun loadIndex(context: Context, key: SecretKey): MutableList<VaultItem> {
        val file = indexFile(context)
        if (!file.exists()) return mutableListOf()
        val plain = RandomAccessFile(file, "r").use { raf ->
            VaultCipher.decryptAll(key, raf, MAX_INDEX_BYTES)
        }
        try {
            val array = JSONArray(String(plain, Charsets.UTF_8))
            return MutableList(array.length()) { i -> VaultItem.fromJson(array.getJSONObject(i)) }
        } finally {
            MemoryUtil.wipe(plain)
        }
    }

    @Synchronized
    fun saveIndex(context: Context, key: SecretKey, items: List<VaultItem>) {
        val array = JSONArray()
        items.forEach { array.put(it.toJson()) }
        val plain = array.toString().toByteArray(Charsets.UTF_8)
        val temp = File(vaultDir(context), "index.ztv.tmp")
        try {
            temp.outputStream().use { out ->
                VaultCipher.encryptStream(key, ByteArrayInputStream(plain), out)
            }
            VaultCipher.patchPlainSize(temp, plain.size.toLong())
            if (!temp.renameTo(indexFile(context))) {
                throw java.io.IOException("Failed to commit index")
            }
        } finally {
            MemoryUtil.wipe(plain)
            temp.delete()
        }
    }

    @Synchronized
    fun addItem(context: Context, key: SecretKey, item: VaultItem) {
        val items = loadIndex(context, key)
        items.add(item)
        saveIndex(context, key, items)
    }

    @Synchronized
    fun removeItem(context: Context, key: SecretKey, id: String) {
        val items = loadIndex(context, key)
        items.removeAll { it.id == id }
        saveIndex(context, key, items)
        secureDelete(mediaFile(context, id))
    }

    fun findItem(context: Context, key: SecretKey, id: String): VaultItem? =
        loadIndex(context, key).firstOrNull { it.id == id }

    /**
     * Best-effort secure delete: one random overwrite pass, then unlink.
     * Flash wear-leveling means overwrites are not a hard guarantee — the
     * real protection is that the file was only ever ciphertext under a
     * hardware key. The overwrite additionally destroys the GCM structure.
     */
    fun secureDelete(file: File) {
        if (!file.exists()) return
        runCatching {
            val random = SecureRandom()
            RandomAccessFile(file, "rw").use { raf ->
                val buffer = ByteArray(64 * 1024)
                var remaining = raf.length()
                raf.seek(0)
                while (remaining > 0) {
                    random.nextBytes(buffer)
                    val n = minOf(buffer.size.toLong(), remaining).toInt()
                    raf.write(buffer, 0, n)
                    remaining -= n
                }
                raf.fd.sync()
            }
        }
        file.delete()
    }

    fun wipeAll(context: Context) {
        vaultDir(context).walkBottomUp().forEach { f ->
            if (f.isFile) secureDelete(f) else f.delete()
        }
    }
}
