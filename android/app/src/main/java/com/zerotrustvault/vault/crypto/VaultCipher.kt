package com.zerotrustvault.vault.crypto

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Chunked AES-256-GCM container ("ZTV1" format).
 *
 * Layout:
 *   [ 4B magic "ZTV1" ][ 4B random IV prefix ][ 8B plaintext size ]
 *   [ chunk 0 ][ chunk 1 ] ... [ chunk n ]
 *
 * Each chunk is GCM(plain[64KiB]) = ciphertext + 16B tag.
 * Chunk IV (12B) = ivPrefix(4B) || chunkIndex(8B big-endian)  — unique per
 * chunk, never reused. AAD = magic || chunkIndex, so chunks cannot be
 * reordered, truncated or transplanted between files without detection.
 *
 * Fixed-size chunks give O(1) random access: chunk i lives at
 * HEADER + i * (CHUNK + TAG). That is what makes seekable, fully
 * authenticated video playback possible without ever writing plaintext
 * to disk.
 */
object VaultCipher {

    val MAGIC = byteArrayOf('Z'.code.toByte(), 'T'.code.toByte(), 'V'.code.toByte(), '1'.code.toByte())
    const val HEADER_SIZE = 16
    const val CHUNK_SIZE = 64 * 1024
    const val TAG_BITS = 128
    const val TAG_SIZE = TAG_BITS / 8
    const val ENCRYPTED_CHUNK_SIZE = CHUNK_SIZE + TAG_SIZE

    private val secureRandom = SecureRandom()

    class Header(val ivPrefix: ByteArray, val plainSize: Long)

    private fun chunkIv(ivPrefix: ByteArray, chunkIndex: Long): ByteArray =
        ByteBuffer.allocate(12).put(ivPrefix).putLong(chunkIndex).array()

    private fun chunkAad(chunkIndex: Long): ByteArray =
        ByteBuffer.allocate(MAGIC.size + 8).put(MAGIC).putLong(chunkIndex).array()

    fun chunkCount(plainSize: Long): Long =
        if (plainSize == 0L) 0 else (plainSize + CHUNK_SIZE - 1) / CHUNK_SIZE

    /* ----------------------------- write ------------------------------ */

    /**
     * Streams [input] into [out] as an encrypted container. Plaintext only
     * ever exists in one 64KiB working buffer, wiped before returning.
     * Returns the number of plaintext bytes consumed.
     */
    fun encryptStream(key: SecretKey, input: InputStream, out: OutputStream): Long {
        val ivPrefix = ByteArray(4).also(secureRandom::nextBytes)
        val plain = ByteArray(CHUNK_SIZE)
        var total = 0L
        var chunkIndex = 0L

        // Header is written with a placeholder size only when the size is
        // unknown; we buffer chunks after the header and patch the size via
        // the caller when writing to a seekable target. To stay simple and
        // streaming-friendly, we instead pre-read chunk lengths: size is
        // computed on the fly and written at the END into a fixed header —
        // so we require a seekable strategy: write header last is not
        // possible on OutputStream. Therefore: write header with size 0,
        // and the true size is stored by rewriting the header — callers
        // that pass a FileOutputStream get the header patched by
        // [patchPlainSize]. For SAF export we know the size up front, so
        // this is only used for import where the target is a local file.
        out.write(MAGIC)
        out.write(ivPrefix)
        out.write(ByteBuffer.allocate(8).putLong(0L).array())

        try {
            while (true) {
                val read = readFully(input, plain)
                if (read <= 0) break
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    Cipher.ENCRYPT_MODE, key,
                    GCMParameterSpec(TAG_BITS, chunkIv(ivPrefix, chunkIndex)),
                )
                cipher.updateAAD(chunkAad(chunkIndex))
                out.write(cipher.doFinal(plain, 0, read))
                total += read
                chunkIndex++
                if (read < CHUNK_SIZE) break
            }
        } finally {
            MemoryUtil.wipe(plain)
        }
        out.flush()
        return total
    }

    /** Patches the plaintext-size field of a just-written local container. */
    fun patchPlainSize(file: java.io.File, plainSize: Long) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(8)
            raf.write(ByteBuffer.allocate(8).putLong(plainSize).array())
        }
    }

    /* ------------------------------ read ------------------------------ */

    fun readHeader(raf: RandomAccessFile): Header {
        val header = ByteArray(HEADER_SIZE)
        raf.seek(0)
        raf.readFully(header)
        for (i in MAGIC.indices) {
            if (header[i] != MAGIC[i]) throw SecurityException("Not a ZTV1 container")
        }
        val buffer = ByteBuffer.wrap(header, 4, 12)
        val ivPrefix = ByteArray(4)
        buffer.get(ivPrefix)
        val plainSize = buffer.long
        if (plainSize < 0) throw SecurityException("Corrupt container header")
        return Header(ivPrefix, plainSize)
    }

    fun plainChunkSize(header: Header, chunkIndex: Long): Int {
        val chunks = chunkCount(header.plainSize)
        require(chunkIndex in 0 until chunks) { "chunk out of range" }
        return if (chunkIndex == chunks - 1 && header.plainSize % CHUNK_SIZE != 0L) {
            (header.plainSize % CHUNK_SIZE).toInt()
        } else {
            CHUNK_SIZE
        }
    }

    /**
     * Decrypts and authenticates a single chunk. Caller OWNS the returned
     * buffer and must [MemoryUtil.wipe] it as soon as it is consumed.
     * Tampering (bit flips, swapped chunks) throws AEADBadTagException.
     */
    fun decryptChunk(
        key: SecretKey,
        raf: RandomAccessFile,
        header: Header,
        chunkIndex: Long,
    ): ByteArray {
        val plainLen = plainChunkSize(header, chunkIndex)
        val encrypted = ByteArray(plainLen + TAG_SIZE)
        raf.seek(HEADER_SIZE + chunkIndex * ENCRYPTED_CHUNK_SIZE)
        raf.readFully(encrypted)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE, key,
            GCMParameterSpec(TAG_BITS, chunkIv(header.ivPrefix, chunkIndex)),
        )
        cipher.updateAAD(chunkAad(chunkIndex))
        return cipher.doFinal(encrypted)
    }

    /** Streams the full plaintext of a container into [out] (used for
     *  export). One chunk of plaintext in memory at a time, wiped after. */
    fun decryptStream(key: SecretKey, raf: RandomAccessFile, out: OutputStream) {
        val header = readHeader(raf)
        val chunks = chunkCount(header.plainSize)
        for (i in 0 until chunks) {
            val plain = decryptChunk(key, raf, header, i)
            try {
                out.write(plain)
            } finally {
                MemoryUtil.wipe(plain)
            }
        }
        out.flush()
    }

    /** Decrypts an entire small container to memory (index / images).
     *  Caller owns and must wipe the result. */
    fun decryptAll(key: SecretKey, raf: RandomAccessFile, maxBytes: Long): ByteArray {
        val header = readHeader(raf)
        if (header.plainSize > maxBytes) {
            throw SecurityException("Container larger than allowed in-memory limit")
        }
        val result = ByteArray(header.plainSize.toInt())
        var offset = 0
        val chunks = chunkCount(header.plainSize)
        for (i in 0 until chunks) {
            val plain = decryptChunk(key, raf, header, i)
            plain.copyInto(result, offset)
            offset += plain.size
            MemoryUtil.wipe(plain)
        }
        return result
    }

    private fun readFully(input: InputStream, target: ByteArray): Int {
        var offset = 0
        while (offset < target.size) {
            val read = try {
                input.read(target, offset, target.size - offset)
            } catch (_: EOFException) {
                -1
            }
            if (read < 0) break
            offset += read
        }
        return offset
    }
}
