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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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
    private val deferredSurfaceReleases = CopyOnWriteArrayList<() -> Unit>()
    private var surfaceAttached = false
    private var attachedSurfaceGeneration: Long? = null
    private val surfaceGeneration = MpvSurfaceGeneration()
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

    private fun attach(surface: Surface, generation: Long) = executeMpv(
        onFailure = surfaceReady::fail,
    ) {
        if (!surfaceGeneration.isCurrent(generation)) return@executeMpv
        mpv.attachSurface(surface)
        surfaceAttached = true
        attachedSurfaceGeneration = generation
        if (!surfaceGeneration.isCurrent(generation)) {
            detachSurface(generation)
            return@executeMpv
        }
        checkMpvResult("set option force-window", mpv.setOptionString("force-window", "yes"))
        if (!surfaceGeneration.isCurrent(generation)) {
            detachSurface(generation)
            return@executeMpv
        }
        checkMpvResult("set property vo", mpv.setPropertyString("vo", vo))
        if (surfaceGeneration.isCurrent(generation)) surfaceReady.complete()
        else detachSurface(generation)
    }

    private fun resize(width: Int, height: Int, generation: Long) = executeMpv {
        if (!surfaceGeneration.isCurrent(generation) || attachedSurfaceGeneration != generation) {
            return@executeMpv
        }
        checkMpvResult(
            "set property android-surface-size",
            mpv.setPropertyString("android-surface-size", "${width}x$height"),
        )
    }

    private fun detach(generation: Long, releaseSurface: () -> Unit) {
        deferredSurfaceReleases.add(releaseSurface)
        val job = destroyJob ?: mpvScope.launch { detachSurface(generation) }
        job.invokeOnCompletion { cause ->
            if (cause == null) releaseDeferredSurface(releaseSurface) else logFailure(cause)
        }
    }

    private fun releaseDeferredSurface(releaseSurface: () -> Unit) {
        if (deferredSurfaceReleases.remove(releaseSurface)) {
            runCatching(releaseSurface).onFailure(::logFailure)
        }
    }

    private fun releaseAllDeferredSurfaces() {
        deferredSurfaceReleases.toList().forEach(::releaseDeferredSurface)
    }

    private fun detachSurface(generation: Long? = null) {
        if (!initialized.get() || !surfaceAttached) return
        if (generation != null && attachedSurfaceGeneration != generation) return
        var failure: Throwable? = null
        try {
            checkMpvResult("set property vo=null", mpv.setPropertyString("vo", "null"))
            checkMpvResult(
                "set option force-window=no",
                mpv.setOptionString("force-window", "no"),
            )
        } catch (error: Throwable) {
            failure = error
        }
        try {
            mpv.detachSurface()
            if (failure == null) {
                surfaceAttached = false
                attachedSurfaceGeneration = null
            }
        } catch (error: Throwable) {
            if (failure == null) failure = error else failure.addSuppressed(error)
        }
        failure?.let { throw it }
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
        surfaceGeneration.release()
        surfaceReady.cancel()
        destroyJob = mpvScope.launch {
            try {
                runCatching { detachSurface() }.onFailure(::logFailure)
                if (mpvInstance.isInitialized()) mpv.close()
                releaseAllDeferredSurfaces()
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
            private var generation: Long? = null

            override fun surfaceCreated(holder: SurfaceHolder) {
                val currentGeneration = surfaceGeneration.begin() ?: return
                generation = currentGeneration
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
                attach(output, currentGeneration)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                val currentGeneration = generation ?: return
                if (!surfaceGeneration.isCurrent(currentGeneration)) return
                SurfaceControl.Transaction().use { it.setBufferSize(layer, width, height).apply() }
                resize(width, height, currentGeneration)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                val destroyedGeneration = generation ?: return
                generation = null
                surfaceGeneration.invalidate(destroyedGeneration)
                val oldLayer = layer
                val oldOutput = output
                synchronized(frameRateLock) {
                    if (activeVideoLayer == oldLayer) {
                        activeVideoLayer = null
                        appliedFrameRateRequest = null
                    }
                }
                SurfaceControl.Transaction().use { it.reparent(oldLayer, null).apply() }
                detach(destroyedGeneration) {
                    oldOutput.release()
                    oldLayer.release()
                }
            }
        })
    }

    private fun createTextureView() = TextureView(context).apply {
        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            private lateinit var output: Surface
            private var generation: Long? = null

            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                val currentGeneration = surfaceGeneration.begin() ?: return
                generation = currentGeneration
                output = Surface(texture)
                attach(output, currentGeneration)
                resize(width, height, currentGeneration)
            }

            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                generation?.let { resize(width, height, it) }
            }

            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                val destroyedGeneration = generation ?: return false
                generation = null
                surfaceGeneration.invalidate(destroyedGeneration)
                val oldOutput = output
                detach(destroyedGeneration) {
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


internal class MpvSurfaceGeneration {
    private val current = AtomicLong(0)
    private val released = AtomicBoolean(false)

    fun begin(): Long? {
        if (released.get()) return null
        val generation = current.incrementAndGet()
        return generation.takeIf { !released.get() }
    }

    fun isCurrent(generation: Long): Boolean =
        !released.get() && current.get() == generation

    fun invalidate(generation: Long): Boolean =
        !released.get() && current.compareAndSet(generation, generation + 1)

    fun release() {
        if (released.compareAndSet(false, true)) current.incrementAndGet()
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
