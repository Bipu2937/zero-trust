package com.zerotrustvault.vault.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import com.facebook.react.bridge.Arguments
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.events.RCTEventEmitter
import com.zerotrustvault.vault.SessionManager
import com.zerotrustvault.vault.VaultStore
import com.zerotrustvault.vault.crypto.MemoryUtil
import com.zerotrustvault.vault.crypto.VaultCipher
import java.io.RandomAccessFile
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Hardware-protected render target for vault media — the DRM-style path.
 *
 * The SurfaceView is marked setSecure(true): its buffers are flagged
 * protected in the display pipeline, exactly like a DRM video surface.
 * Screenshots, screen recording, MediaProjection-based malware, scrcpy
 * and the Recents thumbnail all receive BLACK where this view renders.
 *
 * Decryption happens HERE, in native code. The RN/JS layer only supplies
 * an opaque item id ("show item X") and receives coarse status events.
 * Raw media bytes never cross the bridge:
 *   * images: decrypt → decode → draw onto the secure surface → wipe the
 *     plaintext buffer and recycle the bitmap immediately;
 *   * video:  MediaPlayer pulls from [EncryptedMediaDataSource], which
 *     decrypts 64KiB chunks on demand straight onto the secure surface.
 *     Nothing is ever staged on disk; there is no disk cache to disable.
 *
 * In [thumbnail] mode the same surface is reused as a gallery tile: images
 * draw the same down-sampled still, and videos decode a SINGLE poster
 * frame (via [EncryptedMediaDataSource] + MediaMetadataRetriever) instead
 * of starting playback. Because the tile is a secure surface too, a grid
 * of thumbnails is still screenshot-black and still keeps every decrypted
 * byte out of the JS runtime — the vault's whole point.
 */
@SuppressLint("ViewConstructor")
class SecureMediaView(private val reactContext: ThemedReactContext) :
    FrameLayout(reactContext), SurfaceHolder.Callback {

    companion object {
        const val EVENT_NAME = "onMediaEvent"
        private const val MAX_IMAGE_BYTES = 128L * 1024 * 1024
    }

    private val surfaceView = SurfaceView(reactContext)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    private var player: MediaPlayer? = null
    private var dataSource: EncryptedMediaDataSourceHolder? = null
    private var surfaceReady = false
    private var generation = 0

    // Content aspect ratio for letterboxing (0 = unknown, fill).
    private var contentWidth = 0
    private var contentHeight = 0

    var itemId: String? = null
        set(value) {
            if (field == value) return
            field = value
            restart()
        }

    var paused: Boolean = false
        set(value) {
            field = value
            val p = player ?: return
            runCatching { if (value) p.pause() else p.start() }
        }

    /**
     * Thumbnail (gallery-tile) mode: draw a still poster instead of playing.
     * Videos decode one frame; images behave exactly as in full view.
     */
    var thumbnail: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            restart()
        }

    /** Seek command from JS (milliseconds). Position only — no bytes. */
    fun seekTo(positionMs: Int) {
        player?.let { p -> runCatching { p.seekTo(positionMs) } }
    }

    /* ---------------------- playback progress loop ---------------------- */

    private var progressLoopRunning = false

    private val progressRunnable = object : Runnable {
        override fun run() {
            val p = player
            if (p == null) {
                progressLoopRunning = false
                return
            }
            runCatching {
                if (p.isPlaying) {
                    emit("progress", durationMs = p.duration, positionMs = p.currentPosition)
                }
            }
            postDelayed(this, 250)
        }
    }

    private fun startProgressLoop() {
        if (progressLoopRunning) return
        progressLoopRunning = true
        post(progressRunnable)
    }

    init {
        setBackgroundColor(Color.BLACK)
        // THE core protection: mark the surface as secure/protected.
        surfaceView.setSecure(true)
        surfaceView.holder.addCallback(this)
        addView(surfaceView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /* --------------------------- letterboxing --------------------------- */

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val w = right - left
        val h = bottom - top
        if (w <= 0 || h <= 0) return
        var childW = w
        var childH = h
        if (contentWidth > 0 && contentHeight > 0) {
            val viewRatio = w.toFloat() / h
            val contentRatio = contentWidth.toFloat() / contentHeight
            if (contentRatio > viewRatio) {
                childH = (w / contentRatio).toInt()
            } else {
                childW = (h * contentRatio).toInt()
            }
        }
        val offsetX = (w - childW) / 2
        val offsetY = (h - childH) / 2
        surfaceView.layout(offsetX, offsetY, offsetX + childW, offsetY + childH)
    }

    private fun setContentSize(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        contentWidth = w
        contentHeight = h
        post {
            requestLayout()
            // RN doesn't drive layout passes for native-only children.
            measure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
            )
            layout(left, top, right, bottom)
        }
    }

    /* ------------------------------ surface ----------------------------- */

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        restart()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        // Image content needs a redraw at the new size.
        if (player == null) restart()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        teardownPlayback()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        teardownPlayback()
        executor.shutdown()
    }

    /* ----------------------------- rendering ---------------------------- */

    private fun restart() {
        teardownPlayback()
        val id = itemId ?: return
        if (!surfaceReady) return
        val gen = ++generation
        executor.execute { load(gen, id) }
    }

    private fun load(gen: Int, id: String) {
        try {
            val context = reactContext.applicationContext
            val key = SessionManager.requireKey()
            val item = VaultStore.findItem(context, key, id)
                ?: throw SecurityException("Unknown item")
            if (gen != generation) return
            when {
                item.kind != "video" -> drawImage(gen, id)
                thumbnail -> drawVideoPoster(gen, id)
                else -> startVideo(gen, id)
            }
        } catch (t: Throwable) {
            emit("error", message = t.message ?: "load failed")
        }
    }

    private fun drawImage(gen: Int, id: String) {
        val context = reactContext.applicationContext
        val file = VaultStore.mediaFile(context, id)
        val plain = RandomAccessFile(file, "r").use { raf ->
            VaultCipher.decryptAll(SessionManager.requireKey(), raf, MAX_IMAGE_BYTES)
        }
        try {
            if (gen != generation || !surfaceReady) return

            // Bounds-only pass, then sample down near the surface size so a
            // 100-megapixel photo doesn't balloon plaintext in RAM.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(plain, 0, plain.size, bounds)
            val targetW = width.coerceAtLeast(1)
            val targetH = height.coerceAtLeast(1)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= targetW &&
                bounds.outHeight / (sample * 2) >= targetH
            ) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = BitmapFactory.decodeByteArray(plain, 0, plain.size, opts)
                ?: throw SecurityException("Undecodable image")

            try {
                drawBitmapToSurface(bitmap)
            } finally {
                bitmap.recycle()
            }
            emit("loaded")
        } finally {
            MemoryUtil.wipe(plain)
        }
    }

    /**
     * Decode ONE frame from an encrypted video and draw it as a still — the
     * gallery-tile poster. Uses the same on-the-fly decrypting data source
     * as playback, so no plaintext ever hits disk; the retriever and its
     * source are released the moment the frame is drawn.
     */
    private fun drawVideoPoster(gen: Int, id: String) {
        val context = reactContext.applicationContext
        val file = VaultStore.mediaFile(context, id)
        val source = EncryptedMediaDataSourceHolder(file)
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(source.source)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toIntOrNull() ?: 0
            // First sync frame; fall back to whatever frame is available.
            val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTime
                ?: throw SecurityException("No decodable frame")
            try {
                if (gen != generation || !surfaceReady) return
                drawBitmapToSurface(frame)
            } finally {
                frame.recycle()
            }
            emit("loaded", durationMs = durationMs)
        } finally {
            runCatching { retriever.release() }
            source.close()
        }
    }

    /** Draws a bitmap centered/letterboxed onto the secure surface. */
    private fun drawBitmapToSurface(bitmap: Bitmap) {
        setContentSize(bitmap.width, bitmap.height)
        val holder = surfaceView.holder
        val canvas = holder.lockCanvas() ?: return
        try {
            canvas.drawColor(Color.BLACK)
            val dest = Rect(0, 0, canvas.width, canvas.height)
            canvas.drawBitmap(bitmap, null, dest, null)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    private fun startVideo(gen: Int, id: String) {
        val context = reactContext.applicationContext
        val file = VaultStore.mediaFile(context, id)
        val source = EncryptedMediaDataSourceHolder(file)
        val mediaPlayer = MediaPlayer()
        mediaPlayer.setDataSource(source.source)
        mediaPlayer.setOnVideoSizeChangedListener { _, w, h -> setContentSize(w, h) }
        mediaPlayer.setOnPreparedListener { p ->
            if (gen != generation || !surfaceReady) return@setOnPreparedListener
            p.setSurface(surfaceView.holder.surface)
            emit("loaded", durationMs = p.duration)
            if (!paused) p.start()
            startProgressLoop()
        }
        mediaPlayer.setOnCompletionListener { emit("ended") }
        mediaPlayer.setOnErrorListener { _, what, extra ->
            emit("error", message = "MediaPlayer error $what/$extra")
            true
        }
        synchronized(this) {
            if (gen != generation) {
                mediaPlayer.release()
                source.close()
                return
            }
            player = mediaPlayer
            dataSource = source
        }
        mediaPlayer.prepareAsync()
    }

    @Synchronized
    private fun teardownPlayback() {
        generation++
        player?.let { p ->
            runCatching { p.stop() }
            p.release()
        }
        player = null
        dataSource?.close()
        dataSource = null
        contentWidth = 0
        contentHeight = 0
    }

    /* ------------------------------ events ------------------------------ */

    private fun emit(
        type: String,
        message: String? = null,
        durationMs: Int? = null,
        positionMs: Int? = null,
    ) {
        val map = Arguments.createMap().apply {
            putString("type", type)
            if (message != null) putString("message", message)
            if (durationMs != null) putInt("durationMs", durationMs)
            if (positionMs != null) putInt("positionMs", positionMs)
        }
        reactContext.getJSModule(RCTEventEmitter::class.java)
            .receiveEvent(id, EVENT_NAME, map)
    }
}

/** Tiny holder so teardown can close the data source MediaPlayer holds. */
private class EncryptedMediaDataSourceHolder(file: java.io.File) {
    val source = com.zerotrustvault.vault.media.EncryptedMediaDataSource(file)
    fun close() = runCatching { source.close() }
}
