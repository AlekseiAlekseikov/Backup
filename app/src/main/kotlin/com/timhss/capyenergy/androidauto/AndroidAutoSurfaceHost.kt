package com.timhss.capyenergy.androidauto

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import com.njda.aauto.exfeature.IExFeatureManager

/**
 * Android glue: binder, render [SurfaceTexture], threading, watchdog.
 *
 * ПОРТ из capy (`androidauto/AndroidAutoSurfaceHost.kt`; структурный двойник
 * CarplaySurfaceHost — heartbeat у AA отключён: AndroidAutoContract.HEARTBEAT_MS = 0,
 * потому что повторный bare re-attach на этом стеке повторяет Carlink-сообщение
 * телефону «поверхность жива» с неизмеренным интервалом). Единственная адаптация:
 * Flutter `TextureRegistry` заменён на [TextureView] — UI GeelyTools живёт
 * в WebView, и SurfaceTexture показывается TextureView-оверлеем поверх него
 * (см. `ui/ProjectionRenderBridge`). Все остальные гарантии перенесены
 * дословно:
 *
 *  - R1+R7: attach только когда выполнены все условия ([CarplayAttachPolicy]);
 *  - R2: takeover — всегда `detach()` и только затем `attach()`;
 *  - R3: same-surface re-attach (repair 600/1800 мс, heartbeat 10 с);
 *  - R4: [onActivityPaused] синхронно на главном потоке, без post;
 *  - R8: detach → `Surface.release()` → освобождение буферов (в этом порту —
 *    до возврата `true` из `onSurfaceTextureDestroyed`);
 *  - R9: ни один сбой удалённой стороны не выходит наружу исключением.
 *
 * Threading (как в capy):
 *  - обычная работа (activate, deactivate, watchdog, колбэки сервиса) — на
 *    `HandlerThread("AndroidAutoHost")`, блокирующие binder-вызовы не трогают
 *    платформенный поток;
 *  - операции с `TextureView`/`Surface` — только на главном looper;
 *  - [onActivityPaused] и [releaseSurfaceSynchronously] — синхронно на
 *    вызывающем (главном) потоке, никогда не постятся (R4).
 */
class AndroidAutoSurfaceHost(
    private val context: Context,
    private val onStatus: (AndroidAutoStatus) -> Unit,
) {
    private val lock = Any()

    private val hostThread = HandlerThread("AndroidAutoHost")
    private val hostHandler: Handler
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Re-queried on every [activate]: the OEM CarPlay app can be installed,
     * updated or disabled after our engine was configured, and a value captured
     * once at construction would pin the UI to "unavailable" for the lifetime
     * of the process with no way back.
     */
    private var available: Boolean = serviceAvailable()

    private var service: IExFeatureManager? = null
    private var bindingActive = false
    private var surface: Surface? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var bufferWidth = 0
    private var bufferHeight = 0
    private var hostError: String? = null
    private var disposed = false

    private val coordinator: AndroidAutoRenderCoordinator

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            runOnHost {
                service = IExFeatureManager.Stub.asInterface(binder)
                hostError = null
                coordinator.setServiceConnected(true)
                publishStatusLocked()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // Binder died or service stopped. Keep the binding: the framework
            // rebinds with BIND_AUTO_CREATE.
            runOnHost {
                service = null
                coordinator.setServiceConnected(false)
                publishStatusLocked()
            }
        }

        override fun onBindingDied(name: ComponentName?) {
            runOnHost {
                service = null
                coordinator.setServiceConnected(false)
                hostError = "BINDING_DIED"
                bindingActive = false
                runCatching { context.unbindService(this) }
                bindLocked()
                publishStatusLocked()
            }
        }

        override fun onNullBinding(name: ComponentName?) {
            runOnHost {
                service = null
                hostError = "NULL_BINDING"
                publishStatusLocked()
            }
        }
    }

    private val repairTask = Runnable {
        synchronized(lock) {
            if (disposed || !coordinator.attached) return@Runnable
            coordinator.refresh()
        }
    }

    private val heartbeatTask = object : Runnable {
        override fun run() {
            var rearm = false
            synchronized(lock) {
                if (disposed || !coordinator.attached) return
                coordinator.refresh()
                rearm = true
            }
            if (rearm && AndroidAutoContract.HEARTBEAT_MS > 0) {
                hostHandler.postDelayed(this, AndroidAutoContract.HEARTBEAT_MS)
            }
        }
    }

    init {
        hostThread.start()
        hostHandler = Handler(hostThread.looper)
        coordinator = AndroidAutoRenderCoordinator(
            gateway = BinderRenderGateway(),
            onChanged = { onCoordinatorChanged() },
        )
    }

    // --- Public API (как в capy, §5.6) ---

    fun activate(width: Int?, height: Int?, onComplete: (AndroidAutoStatus) -> Unit) {
        hostHandler.post {
            val status = synchronized(lock) {
                if (!disposed) {
                    available = serviceAvailable()
                    val (w, h) = resolveGeometry(width, height)
                    if (surface == null || bufferWidth != w || bufferHeight != h) {
                        // R8: the detach happens here, on the host thread,
                        // before the platform thread frees the old buffers.
                        coordinator.setSurfaceReady(false)
                        configureTexture(w, h)
                    }
                    bindLocked()
                    coordinator.setDartActive(true)
                    publishStatusLocked()
                }
                buildStatusLocked()
            }
            onComplete(status)
        }
    }

    /**
     * Establishes the binder connection alone — no texture, no `dartActive`, no
     * attach. `bindService` never touches the shared render singleton; only
     * tx16/tx17 do, so this carries none of R1's exposure and is safe to call
     * before the CarPlay tab is ever on screen.
     */
    fun ensureBound(onComplete: (AndroidAutoStatus) -> Unit) {
        hostHandler.post {
            val status = synchronized(lock) {
                if (!disposed) {
                    available = serviceAvailable()
                    bindLocked()
                }
                buildStatusLocked()
            }
            onComplete(status)
        }
    }

    fun deactivate(onComplete: (AndroidAutoStatus) -> Unit) {
        hostHandler.post {
            val status = synchronized(lock) {
                if (!disposed) {
                    coordinator.setDartActive(false)
                    releaseTexture()
                    unbindLocked()
                    publishStatusLocked()
                }
                buildStatusLocked()
            }
            onComplete(status)
        }
    }

    fun requestRefresh(onComplete: (AndroidAutoStatus) -> Unit) {
        hostHandler.post {
            val status = synchronized(lock) {
                if (!disposed) coordinator.refresh()
                buildStatusLocked()
            }
            onComplete(status)
        }
    }

    /**
     * Posted to the host thread on purpose: resuming with the tab already open
     * reconciles straight into a TAKE_OVER, and the attach is a blocking binder
     * call whose remote side runs a full `eglSetupContext` synchronously.
     * Only the *detach* ordering is load-bearing (R4) — and that is
     * [onActivityPaused]'s job.
     */
    fun onActivityResumed() {
        hostHandler.post {
            synchronized(lock) {
                if (!disposed) coordinator.setActivityResumed(true)
            }
        }
    }

    /**
     * R4. Main thread, synchronous, never posted. The OEM CarplayActivity
     * creates its SurfaceView after this returns; any detach that lands later
     * tears down the EGL state it just built and leaves it black.
     */
    fun onActivityPaused() {
        synchronized(lock) {
            coordinator.setActivityResumed(false)
        }
    }

    /** Snapshot under [lock], safe to call from any thread. */
    fun status(): AndroidAutoStatus = synchronized(lock) { buildStatusLocked() }

    fun dispose() {
        synchronized(lock) {
            if (disposed) return
            disposed = true
            cancelRepairs()
            coordinator.setDartActive(false)
            coordinator.setSurfaceReady(false)
            service = null
            coordinator.setServiceConnected(false)
            hostError = null
            if (bindingActive) {
                bindingActive = false
                runCatching { context.unbindService(serviceConnection) }
            }
        }
        mainHandler.post { releasePlatformTexture() }
        hostHandler.removeCallbacksAndMessages(null)
        hostThread.quitSafely()
    }

    // --- TextureView (адаптация TextureRegistry → TextureView) ---

    /**
     * Main thread only. The bridge calls this when the TextureView's
     * SurfaceTexture appears. Configures the buffer and hands the reconcile
     * back to the host thread — never reconciles on the platform thread, for
     * the same reason capy's `createPlatformTexture` does not.
     */
    fun onSurfaceTextureAvailable(st: SurfaceTexture) {
        val ready = synchronized(lock) {
            if (disposed) return
            if (surface != null) return // уже сконфигурирована
            if (bufferWidth > 0 && bufferHeight > 0) {
                st.setDefaultBufferSize(bufferWidth, bufferHeight)
            }
            surfaceTexture = st
            surface = Surface(st)
            Pair(bufferWidth, bufferHeight)
        }
        if (ready.first > 0 && ready.second > 0) {
            hostHandler.post { markSurfaceReady(ready.first, ready.second) }
        }
    }

    /**
     * Main thread only, from `TextureView.SurfaceTextureListener`. The release
     * is synchronous and happens BEFORE `true` is returned (R8): the view
     * frees the SurfaceTexture right after this callback, and the OEM must
     * have been detached by then. Idempotent — a surface that was already
     * released by deactivate() releases nothing here.
     */
    fun onSurfaceTextureDestroyed() {
        releaseSurfaceSynchronously()
    }

    /** Synchronous teardown for onPause/hidden-tab paths. Idempotent. */
    fun releaseSurfaceSynchronously() {
        synchronized(lock) {
            if (disposed) return
            coordinator.setSurfaceReady(false) // RELEASE: detach раньше освобождения
            surface?.release()
            surface = null
            surfaceTexture = null
            publishStatusLocked()
        }
    }

    /**
     * Host thread, under [lock]. The caller must already have driven
     * `setSurfaceReady(false)` so the detach precedes the buffer change (R8).
     * Posts to the main looper: `TextureView`/`Surface` are platform-thread-only.
     */
    private fun configureTexture(width: Int, height: Int) {
        mainHandler.post { configurePlatformTexture(width, height) }
    }

    /**
     * Main looper. Applies the geometry to the live SurfaceTexture — the
     * TextureView keeps its own SurfaceTexture alive across buffer changes,
     * so unlike capy's registry there is nothing to recreate; a size change
     * simply re-arms the default buffer size. Does **not** touch the
     * coordinator: the reconcile is handed back to the host thread.
     */
    private fun configurePlatformTexture(width: Int, height: Int) {
        synchronized(lock) {
            if (disposed) return
            if (surface != null && bufferWidth == width && bufferHeight == height) return
            surface?.release()
            surface = null
            val st = surfaceTexture
            if (st != null) {
                st.setDefaultBufferSize(width, height)
                surface = Surface(st)
            }
            bufferWidth = width
            bufferHeight = height
        }
        hostHandler.post { markSurfaceReady(width, height) }
    }

    /** Host thread: the reconcile — and any attach it triggers — lands here. */
    private fun markSurfaceReady(width: Int, height: Int) {
        synchronized(lock) {
            if (disposed) return
            // A newer activate() may have replaced the geometry while this hop
            // was in flight; that call owns the reconcile for its own geometry.
            if (surface == null || bufferWidth != width || bufferHeight != height) return
            coordinator.setBufferSize(width, height)
            coordinator.setSurfaceReady(true)
            publishStatusLocked()
        }
    }

    /**
     * Host thread, under [lock]. R8: `detach` (via `setSurfaceReady(false)`,
     * synchronously here) → `Surface.release()` (on the main looper, below).
     */
    private fun releaseTexture() {
        coordinator.setSurfaceReady(false)
        mainHandler.post { releasePlatformTexture() }
    }

    /**
     * Main looper. No `disposed` guard on purpose: `dispose()` schedules this
     * so the buffers are still freed on teardown. Idempotent.
     */
    private fun releasePlatformTexture() {
        synchronized(lock) {
            surface?.release()
            surface = null
            bufferWidth = 0
            bufferHeight = 0
            publishStatusLocked()
        }
    }

    // --- Binding ---

    private fun exfeatureIntent() =
        Intent(AndroidAutoContract.EXFEATURE_ACTION).setPackage(AndroidAutoContract.PACKAGE)

    private fun serviceAvailable(): Boolean =
        context.packageManager.queryIntentServices(exfeatureIntent(), 0).isNotEmpty()

    private fun bindLocked() {
        if (bindingActive || service != null) return
        val accepted = runCatching {
            context.bindService(exfeatureIntent(), serviceConnection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        bindingActive = accepted
        if (!accepted) {
            // bindService can leak its registration record when it fails.
            runCatching { context.unbindService(serviceConnection) }
            hostError = "BIND_FAILED"
        }
    }

    private fun unbindLocked() {
        if (!bindingActive) return
        bindingActive = false
        service = null
        coordinator.setServiceConnected(false)
        runCatching { context.unbindService(serviceConnection) }
    }

    // --- Geometry ---

    private fun resolveGeometry(width: Int?, height: Int?): Pair<Int, Int> {
        val overrideInvalid = (width != null && width <= 0) || (height != null && height <= 0)
        if (overrideInvalid) {
            // R7: reject the override, fall back to the default, and say so.
            hostError = "INVALID_BUFFER_SIZE"
        } else if (hostError == "INVALID_BUFFER_SIZE") {
            hostError = null
        }
        if (width != null && width > 0 && height != null && height > 0) return width to height
        return defaultBufferSize()
    }

    private fun defaultBufferSize(): Pair<Int, Int> {
        val metrics = DisplayMetrics()
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics) // minSdk 28
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        return if (w > 0 && h > 0) {
            w to h
        } else {
            AndroidAutoContract.DEFAULT_BUFFER_WIDTH to AndroidAutoContract.DEFAULT_BUFFER_HEIGHT
        }
    }

    // --- Watchdog (R3) ---

    private var lastReportedAttached = false

    private fun onCoordinatorChanged() {
        // Called from the coordinator under `lock` (the coordinator only runs
        // inside a synchronized block in this class).
        val attached = coordinator.attached
        if (attached && !lastReportedAttached) {
            scheduleRepairs()
        } else if (!attached && lastReportedAttached) {
            cancelRepairs()
        }
        lastReportedAttached = attached
        publishStatusLocked()
    }

    private fun scheduleRepairs() {
        cancelRepairs()
        for (delay in AndroidAutoContract.REFRESH_DELAYS_MS) {
            hostHandler.postDelayed(repairTask, delay)
        }
        if (AndroidAutoContract.HEARTBEAT_MS > 0) {
            // Delayed, not immediate: posting with no delay fired a redundant
            // re-attach microseconds after the takeover's own attach.
            hostHandler.postDelayed(heartbeatTask, AndroidAutoContract.HEARTBEAT_MS)
        }
    }

    private fun cancelRepairs() {
        hostHandler.removeCallbacks(repairTask)
        hostHandler.removeCallbacks(heartbeatTask)
    }

    // --- Status ---

    /**
     * В capy здесь Flutter texture id. У нас его нет: UI не сэмплирует
     * текстуру по id — TextureView уже стоит в иерархии. Поле сохранено для
     * совместимости формата: 1L, когда Surface жив (эквивалент `canRender =
     * attached && textureId != null` в Dart).
     */
    private fun buildStatusLocked(): AndroidAutoStatus = AndroidAutoStatus(
        available = available,
        bound = service != null,
        attached = coordinator.attached,
        activityResumed = coordinator.inputs.activityResumed,
        dartActive = coordinator.inputs.dartActive,
        textureId = if (surface != null) 1L else null,
        bufferWidth = bufferWidth,
        bufferHeight = bufferHeight,
        attachCount = coordinator.attachCount,
        lastError = hostError ?: coordinator.lastError,
    )

    private fun publishStatusLocked() {
        onStatus(buildStatusLocked())
    }

    // --- Helpers ---

    private fun runOnHost(block: () -> Unit) {
        hostHandler.post {
            synchronized(lock) {
                if (!disposed) block()
            }
        }
    }

    private fun onRemoteFailure(t: Throwable) {
        hostError = "REMOTE_EXCEPTION"
        service = null
        coordinator.setServiceConnected(false)
    }

    /** R9: every binder call is wrapped; a dead remote never escapes. */
    private inner class BinderRenderGateway : AndroidAutoRenderGateway {
        override fun attach(width: Int, height: Int): Boolean {
            val manager = service ?: return false
            val target = surface ?: return false
            if (!target.isValid || width <= 0 || height <= 0) return false
            return runCatching {
                manager.notifyMainSurfaceAttached(target, width, height)
                // R5: no updateViewArea call here, ever. It draws nothing until
                // the next attach and it breaks the touch remap. The default
                // view area is already the full remote screen.
            }.onFailure { onRemoteFailure(it) }.isSuccess
        }

        override fun detach(): Boolean {
            val manager = service ?: return false
            return runCatching { manager.notifyMainSurfaceDetached() }
                .onFailure { onRemoteFailure(it) }
                .isSuccess
        }
    }
}
