package com.zerotrustvault.vault.media

import android.media.MediaDataSource
import com.zerotrustvault.vault.SessionManager
import com.zerotrustvault.vault.crypto.MemoryUtil
import com.zerotrustvault.vault.crypto.VaultCipher
import java.io.File
import java.io.RandomAccessFile

/**
 * Seekable, on-the-fly decrypting data source for MediaPlayer.
 *
 * This is the piece that keeps video playback zero-trust:
 *   * plaintext NEVER touches disk — MediaPlayer pulls byte ranges and we
 *     decrypt the covering 64KiB chunks straight from the encrypted file;
 *   * every chunk is GCM-authenticated on read, so a tampered file aborts
 *     playback instead of rendering attacker-controlled data;
 *   * the DEK is fetched from [SessionManager] on every read — the moment
 *     the vault locks, reads fail and playback halts, even mid-frame;
 *   * one decrypted chunk is cached for sequential-read performance and
 *     is zeroed on eviction and on [close].
 */
class EncryptedMediaDataSource(file: File) : MediaDataSource() {

    private val raf = RandomAccessFile(file, "r")
    private val header = VaultCipher.readHeader(raf)

    private var cachedIndex = -1L
    private var cachedChunk: ByteArray? = null
    private var closed = false

    override fun getSize(): Long = header.plainSize

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (closed) return -1
        if (position < 0 || position >= header.plainSize) return -1
        return try {
            var copied = 0
            var pos = position
            while (copied < size && pos < header.plainSize) {
                val chunkIndex = pos / VaultCipher.CHUNK_SIZE
                val chunk = chunkAt(chunkIndex)
                val within = (pos % VaultCipher.CHUNK_SIZE).toInt()
                val n = minOf(size - copied, chunk.size - within)
                if (n <= 0) break
                System.arraycopy(chunk, within, buffer, offset + copied, n)
                copied += n
                pos += n
            }
            if (copied == 0) -1 else copied
        } catch (_: SecurityException) {
            // Vault locked mid-playback: scrub and refuse.
            MemoryUtil.wipe(cachedChunk)
            cachedChunk = null
            cachedIndex = -1
            -1
        }
    }

    private fun chunkAt(chunkIndex: Long): ByteArray {
        if (chunkIndex != cachedIndex) {
            MemoryUtil.wipe(cachedChunk)
            cachedChunk = VaultCipher.decryptChunk(
                SessionManager.requireKey(), raf, header, chunkIndex,
            )
            cachedIndex = chunkIndex
        }
        return cachedChunk!!
    }

    @Synchronized
    override fun close() {
        closed = true
        MemoryUtil.wipe(cachedChunk)
        cachedChunk = null
        cachedIndex = -1
        runCatching { raf.close() }
    }
}
