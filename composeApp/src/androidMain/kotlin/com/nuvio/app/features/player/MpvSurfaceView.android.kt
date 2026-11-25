package com.nuvio.app.features.player

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Build
import android.util.Log
import android.view.Surface
import android.view.SurfaceControl
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import `is`.xyz.mpv.MPV
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal abstract class MpvSurfaceView(context: Context, private val vo: String) : FrameLayout(context) {
    private val mpvInstance = lazy { MPV(context) }
    protected val mpv by mpvInstance
    protected val mpvDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "NuvioLibmpv").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val mpvScope = CoroutineScope(SupervisorJob() + mpvDispatcher)
    protected val surfaceReady = MpvSurfaceReadiness()
    protected val released = AtomicBoolean(false)
    private val initialized = AtomicBoolean(false)
    private var destroyJob: Job? = null
    private var surfaceAttached = false
    private val frameRateLock = Any()
    private var activeVideoLayer: Any? = null
    private var latestFrameRateRequest = MpvSurfaceFrameRateRequest(
        0f,
        Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS,
    )
    private var appliedFrameRateRequest: MpvSurfaceFrameRateRequest? = null

    protected abstract fun initOptions()
    protected abstract fun observeProperties()

    suspend fun initialize(
        configDir: String,
        cacheDir: String,
        prepareAssets: () -> Unit,
    ) {
        try {
            withContext(mpvDispatcher) {
                check(!released.get()) { "libmpv view was released before initialization" }
                prepareAssets()
                mpv.setConfigDir(configDir)
                mpv.setCacheDir(cacheDir)
                mpv.setOptionString("vo", vo)
                initOptions()
                mpv.setOptionString("force-window", "no")
                mpv.setOptionString("idle", "once")
                observeProperties()
                initialized.set(true)
            }
            withContext(Dispatchers.Main.immediate) {
                if (!released.get()) {
                    addView(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) createSurfaceView() else createTextureView())
                }
            }
        } catch (error: Throwable) {
            initialized.set(false)
            if (error !is CancellationException) {
                surfaceReady.fail(error)
            }
            throw error
        }
    }

    fun addObserver(observer: MPV.EventObserver) = executeMpv {
        mpv.addObserver(observer)
    }

    fun removeObserver(observer: MPV.EventObserver) = executeMpv {
        mpv.removeObserver(observer)
    }

    fun addLogObserver(observer: MPV.LogObserver) = executeMpv {
        mpv.addLogObserver(observer)
    }

    fun removeLogObserver(observer: MPV.LogObserver) = executeMpv {
        mpv.removeLogObserver(observer)
    }

    protected fun updateMpvSurfaceFrameRateHint(
        mediaFrameRate: Double,
        playbackSpeed: Double,
        durationSeconds: Double,
        isPlaying: Boolean,
        liveStream: Boolean,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val request = mpvSurfaceFrameRateRequest(
            userPreference = context.videoFrameRateUserPreference(),
            mediaFrameRate = mediaFrameRate,
            playbackSpeed = playbackSpeed,
            durationSeconds = durationSeconds,
            isPlaying = isPlaying,
            liveStream = liveStream,
        )
        synchronized(frameRateLock) {
            latestFrameRateRequest = request
            applyMpvSurfaceFrameRateLocked()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun applyMpvSurfaceFrameRateLocked() {
        val layer = activeVideoLayer as? SurfaceControl ?: return
        val request = latestFrameRateRequest
        if (appliedFrameRateRequest == request) return
        runCatching {
            SurfaceControl.Transaction().use { transaction ->
                val updatedTransaction = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    transaction.setFrameRate(
                        layer,
                        request.frameRate,
                        Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                        request.changeFrameRateStrategy,
                    )
                } else {
                    // API 30 supports the seamless-only overload; the non-seamless strategy
                    // overload was added in API 31.
                    transaction.setFrameRate(
                        layer,
                        request.frameRate,
                        Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                    )
                }
                updatedTransaction.apply()
            }
        }.onSuccess {
            appliedFrameRateRequest = request
        }.onFailure(::logFailure)
    }

    protected fun executeMpv(
        onFailure: ((Throwable) -> Unit)? = null,
        block: () -> Unit,
    ) {
        if (released.get()) return
        mpvScope.launch {
            if (!released.get() && initialized.get()) {
                try {
                    block()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    logFailure(error)
                    onFailure?.invoke(error)
                }
            }
        }
    }

    private fun attach(surface: Surface) = executeMpv(
        onFailure = surfaceReady::fail,
    ) {
        mpv.attachSurface(surface)
        surfaceAttached = true
        checkMpvResult("set option force-window", mpv.setOptionString("force-window", "yes"))
        checkMpvResult("set property vo", mpv.setPropertyString("vo", vo))
        surfaceReady.complete()
    }

    private fun resize(width: Int, height: Int) = executeMpv {
        checkMpvResult(
            "set property android-surface-size",
            mpv.setPropertyString("android-surface-size", "${width}x$height"),
        )
    }

    private fun detach(releaseSurface: () -> Unit) {
        val job = destroyJob ?: mpvScope.launch { detachSurface() }
        job.invokeOnCompletion { cause ->
            if (cause == null) releaseSurface() else logFailure(cause)
        }
    }

    private fun detachSurface() {
        if (!initialized.get() || !surfaceAttached) return
        surfaceAttached = false
        runCatching {
            try {
                checkMpvResult("set property vo=null", mpv.setPropertyString("vo", "null"))
                checkMpvResult(
                    "set option force-window=no",
                    mpv.setOptionString("force-window", "no"),
                )
            } finally {
                mpv.detachSurface()
            }
        }.onFailure(::logFailure)
    }

    fun releaseMpv() {
        if (!released.compareAndSet(false, true)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            synchronized(frameRateLock) {
                latestFrameRateRequest = MpvSurfaceFrameRateRequest(
                    0f,
                    Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS,
                )
                applyMpvSurfaceFrameRateLocked()
            }
        }
        surfaceReady.cancel()
        destroyJob = mpvScope.launch {
            try {
                detachSurface()
                if (mpvInstance.isInitialized()) runCatching { mpv.close() }.onFailure(::logFailure)
            } finally {
                mpvDispatcher.close()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun createSurfaceView() = SurfaceView(context).apply {
        holder.addCallback(object : SurfaceHolder.Callback {
            private lateinit var layer: SurfaceControl
            private lateinit var output: Surface

            override fun surfaceCreated(holder: SurfaceHolder) {
                val frame = holder.surfaceFrame
                layer = SurfaceControl.Builder()
                    .setName("MPV video")
                    .setParent(surfaceControl)
                    .setBufferSize(frame.width(), frame.height())
                    .build()
                output = Surface(layer)
                synchronized(frameRateLock) {
                    activeVideoLayer = layer
                    appliedFrameRateRequest = null
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        applyMpvSurfaceFrameRateLocked()
                    }
                }
                SurfaceControl.Transaction().use {
                    it.setLayer(layer, 1).setVisibility(layer, true).apply()
                }
                attach(output)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                SurfaceControl.Transaction().use { it.setBufferSize(layer, width, height).apply() }
                resize(width, height)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                val oldLayer = layer
                val oldOutput = output
                synchronized(frameRateLock) {
                    if (activeVideoLayer == oldLayer) {
                        activeVideoLayer = null
                        appliedFrameRateRequest = null
                    }
                }
                SurfaceControl.Transaction().use { it.reparent(oldLayer, null).apply() }
                detach {
                    oldOutput.release()
                    oldLayer.release()
                }
            }
        })
    }

    private fun createTextureView() = TextureView(context).apply {
        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            private lateinit var output: Surface

            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                output = Surface(texture)
                attach(output)
                resize(width, height)
            }

            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                resize(width, height)
            }

            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                val oldOutput = output
                detach {
                    oldOutput.release()
                    texture.release()
                }
                return false
            }

            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
        }
    }

    private fun checkMpvResult(operation: String, result: Int) {
        if (result < 0) {
            throw IllegalStateException("$operation failed: ${mpv.errorString(result)} ($result)")
        }
    }

    private fun logFailure(error: Throwable) {
        Log.w(
            "MpvSurfaceView",
            "libmpv operation failed: ${sanitizeMpvErrorText(error.message) ?: error::class.simpleName.orEmpty()}",
        )
    }
}


internal class MpvSurfaceReadiness {
    private val ready = CompletableDeferred<Unit>()

    fun complete() {
        ready.complete(Unit)
    }

    fun fail(error: Throwable) {
        ready.completeExceptionally(error)
    }

    fun cancel() {
        ready.cancel()
    }

    suspend fun await() {
        ready.await()
    }
}
