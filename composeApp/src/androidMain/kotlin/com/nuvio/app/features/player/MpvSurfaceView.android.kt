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
import `is`.xyz.mpv.MPVNode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.Collections
import java.util.IdentityHashMap
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
    private val surfaceLifecycleMutex = Mutex()
    private val surfaceReleases = CopyOnWriteArrayList<Pair<Long, () -> Unit>>()
    private val ownedSurfaceTextures = Collections.synchronizedSet(
        Collections.newSetFromMap(IdentityHashMap<SurfaceTexture, Boolean>()),
    )
    @Volatile
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
    protected open fun onSurfaceFailure(error: Throwable) = Unit

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
                checkMpvResult("set option vo", mpv.setOptionString("vo", vo))
                initOptions()
                checkMpvResult("set option force-window", mpv.setOptionString("force-window", "no"))
                checkMpvResult("set option idle", mpv.setOptionString("idle", "once"))
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
        block: suspend () -> Unit,
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
        onFailure = { error ->
            val wasReady = surfaceReady.isCompleted()
            surfaceReady.fail(error)
            if (wasReady) onSurfaceFailure(error)
        },
    ) {
        surfaceLifecycleMutex.withLock {
            if (!surfaceGeneration.isCurrent(generation)) return@withLock

            val previousGeneration = attachedSurfaceGeneration
            if (surfaceAttached && previousGeneration != null && previousGeneration != generation) {
                detachSurface(previousGeneration)
                releaseSurface(previousGeneration)
            }

            if (!surfaceGeneration.isCurrent(generation)) return@withLock
            mpv.attachSurface(surface)
            surfaceAttached = true
            attachedSurfaceGeneration = generation
            checkMpvResult("set option force-window", mpv.setOptionString("force-window", "yes"))
            checkMpvResult("set property vo", mpv.setPropertyString("vo", vo))
            if (surfaceGeneration.isCurrent(generation)) surfaceReady.complete()
        }
    }

    private fun resize(width: Int, height: Int, generation: Long) = executeMpv(
        onFailure = onSurfaceFailure,
    ) {
        surfaceLifecycleMutex.withLock {
            if (!surfaceGeneration.isCurrent(generation) || attachedSurfaceGeneration != generation) {
                return@withLock
            }
            checkMpvResult(
                "set property android-surface-size",
                mpv.setPropertyString("android-surface-size", "${width}x$height"),
            )
        }
    }

    protected suspend fun <T> withSurfaceLifecycleLock(block: suspend () -> T): T =
        surfaceLifecycleMutex.withLock { block() }

    private fun trackSurfaceRelease(generation: Long, releaseSurface: () -> Unit) {
        surfaceReleases.add(generation to releaseSurface)
    }

    private fun detach(generation: Long) {
        val job = destroyJob ?: mpvScope.launch {
            surfaceLifecycleMutex.withLock {
                detachSurface(generation)
                releaseSurface(generation)
            }
        }
        job.invokeOnCompletion { cause ->
            if (cause == null) {
                releaseSurface(generation)
            } else {
                logFailure(cause)
                onSurfaceFailure(cause)
            }
        }
    }

    private fun releaseSurface(generation: Long) {
        val entry = surfaceReleases.firstOrNull { it.first == generation } ?: return
        if (surfaceReleases.remove(entry)) {
            runCatching(entry.second).onFailure(::logFailure)
        }
    }

    private fun releaseAllSurfaceResources() {
        surfaceReleases.toList().forEach { (generation, _) -> releaseSurface(generation) }
    }

    private suspend fun detachSurface(generation: Long? = null) {
        if (!initialized.get() || !surfaceAttached) return
        if (generation != null && attachedSurfaceGeneration != generation) return

        val currentVideoOutput = mpv.getPropertyString("current-vo")
        if (!currentVideoOutput.isNullOrBlank() && currentVideoOutput != "null") {
            val releaseGate = MpvVideoOutputReleaseGate()
            val observer = object : MPV.EventObserver {
                override fun eventProperty(property: String) = Unit
                override fun eventProperty(property: String, value: Long) = Unit
                override fun eventProperty(property: String, value: Boolean) = Unit
                override fun eventProperty(property: String, value: String) = Unit
                override fun eventProperty(property: String, value: Double) = Unit
                override fun eventProperty(property: String, value: MPVNode) = Unit

                override fun event(eventId: Int, data: MPVNode) {
                    if (eventId == MPV.mpvEvent.MPV_EVENT_VIDEO_RECONFIG) {
                        mpvScope.launch {
                            val activeVideoOutput = runCatching {
                                mpv.getPropertyString("current-vo")
                            }.getOrNull()
                            releaseGate.onVideoReconfigured(activeVideoOutput)
                        }
                    }
                }
            }

            mpv.addObserver(observer)
            try {
                checkMpvResult("set property vo=null", mpv.setPropertyString("vo", "null"))
                withTimeout(5_000) {
                    releaseGate.await()
                }
            } finally {
                mpv.removeObserver(observer)
            }
        } else {
            // Clear a pending VO selection as well: an attach can be queued before
            // the first VO exists, in which case current-vo is temporarily unavailable.
            checkMpvResult("set property vo=null", mpv.setPropertyString("vo", "null"))
        }

        // mpv emits VIDEO_RECONFIG after uninit_video_out() has destroyed and joined
        // the previous VO. Only now is it safe for JNI to delete its global Surface ref.
        mpv.detachSurface()
        surfaceAttached = false
        attachedSurfaceGeneration = null
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
                surfaceLifecycleMutex.withLock {
                    runCatching { detachSurface() }.onFailure(::logFailure)
                    if (mpvInstance.isInitialized()) mpv.close()
                    releaseAllSurfaceResources()
                }
            } finally {
                mpvDispatcher.close()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun createSurfaceView() = SurfaceView(context).apply {
        holder.addCallback(object : SurfaceHolder.Callback {
            private lateinit var layer: SurfaceControl
            private var generation: Long? = null

            override fun surfaceCreated(holder: SurfaceHolder) {
                val currentGeneration = surfaceGeneration.begin() ?: return
                generation = currentGeneration
                val frame = holder.surfaceFrame
                val createdLayer = SurfaceControl.Builder()
                    .setName("MPV video")
                    .setParent(surfaceControl)
                    .setBufferSize(frame.width(), frame.height())
                    .build()
                val createdOutput = Surface(createdLayer)
                layer = createdLayer
                trackSurfaceRelease(currentGeneration) {
                    createdOutput.release()
                    createdLayer.release()
                }
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
                attach(createdOutput, currentGeneration)
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
                synchronized(frameRateLock) {
                    if (activeVideoLayer == oldLayer) {
                        activeVideoLayer = null
                        appliedFrameRateRequest = null
                    }
                }
                SurfaceControl.Transaction().use {
                    it.setVisibility(oldLayer, false).reparent(oldLayer, null).apply()
                }
                detach(destroyedGeneration)
            }
        })
    }

    private fun createTextureView() = TextureView(context).apply {
        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            private var generation: Long? = null

            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                val currentGeneration = surfaceGeneration.begin() ?: return
                generation = currentGeneration
                val createdOutput = Surface(texture)
                ownedSurfaceTextures.add(texture)
                trackSurfaceRelease(currentGeneration) {
                    createdOutput.release()
                    if (ownedSurfaceTextures.remove(texture)) texture.release()
                }
                attach(createdOutput, currentGeneration)
                resize(width, height, currentGeneration)
            }

            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                generation?.let { resize(width, height, it) }
            }

            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                val destroyedGeneration = generation
                if (destroyedGeneration == null) {
                    // A duplicate callback must not transfer a still-retained texture
                    // back to the framework before native VO teardown has completed.
                    return !ownedSurfaceTextures.contains(texture)
                }
                generation = null
                surfaceGeneration.invalidate(destroyedGeneration)
                detach(destroyedGeneration)
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

internal class MpvVideoOutputReleaseGate {
    private val detached = CompletableDeferred<Unit>()

    fun onVideoReconfigured(currentVideoOutput: String?) {
        if (currentVideoOutput == null || currentVideoOutput == "null") {
            detached.complete(Unit)
        }
    }

    fun isComplete(): Boolean = detached.isCompleted

    suspend fun await() {
        detached.await()
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

    fun isCompleted(): Boolean = ready.isCompleted

    suspend fun await() {
        ready.await()
    }
}
