package com.example.voiceapp3.ui

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.TextureView
import android.view.View
import com.example.voiceapp3.MainActivity
import com.example.voiceapp3.R
import com.timhss.capyenergy.androidauto.AndroidAutoStatus
import com.timhss.capyenergy.androidauto.AndroidAutoSurfaceHost
import com.timhss.capyenergy.carplay.CarplaySurfaceHost
import com.timhss.capyenergy.projection.ProjectionStack
import com.timhss.capyenergy.projection.ProjectionTouchController
import org.json.JSONObject

/**
 * Мост рендеринга CarPlay / Android Auto — адаптация трёх Flutter-мостов capy
 * (`CarplayBridge` + `AndroidAutoBridge` + `ProjectionTouchBridge`) на UiBridge
 * GeelyTools.
 *
 * Замена пар MethodChannel/EventChannel:
 *  - команды идут из JS событиями `projection.activate` / `projection.deactivate`
 *    / `projection.refresh` (эквиваленты методов activate/deactivate/refresh);
 *  - статусы уходят в WebView пушами `carplay.status` / `android_auto.status`
 *    (эквиваленты EventChannel `.../carplay/status` и `.../android_auto/status`,
 *    тело — CarplayStatus.toMap() / AndroidAutoStatus.toMap());
 *  - прямоугольник области видео приходит синхронным вызовом
 *    `GeelyNative.projectionFrame(stack, x, y, w, h)` — UI GeelyTools живёт в
 *    WebView, поэтому SurfaceTexture показывают два TextureView-оверлея поверх
 *    него; мост раскладывает активный оверлей ровно по прямоугольнику,
 *    который измерил JS (в физических пикселях: CSS px × devicePixelRatio —
 *    WebView занимает всё окно, системы координат совпадают).
 *
 * Гарантии, перенесённые из capy:
 *  - ровно один источник истины на протокол: Surface создаётся и владеется
 *    только здесь (через CarplaySurfaceHost / AndroidAutoSurfaceHost);
 *  - касания: TextureView стоит ПОВЕРХ WebView и перехватывает их —
 *    [ProjectionTouchListener] маппит и отправляет через
 *    [ProjectionTouchController]; буфер касаний синхронизируется из того же
 *    рендер-статуса, что ушёл в notifyMainSurfaceAttached;
 *  - R4 (жизненный цикл): [onActivityPausedSynchronously] вызывается из
 *    MainActivity.onPause СИНХРОННО ДО super.onPause() — release CarPlay,
 *    release Android Auto, подъём активных жестов, в этом порядке;
 *  - binding ≠ attach: bind/ensureBound не трогает render-синглтон OEM.
 */
class ProjectionRenderBridge(
    activity: MainActivity,
    private val push: (name: String, json: String) -> Unit,
) {
    private val appContext = activity.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private val carplayView: TextureView =
        activity.findViewById(R.id.carplaySurface)
    private val aautoView: TextureView =
        activity.findViewById(R.id.aautoSurface)

    /** Какой стек сейчас активен на вкладке (null — никакого). */
    @Volatile
    private var activeStack: ProjectionStack? = null

    private val carplayHost: CarplaySurfaceHost
    private val aautoHost: AndroidAutoSurfaceHost
    private val touchController: ProjectionTouchController
    private val carplayTouch: ProjectionTouchListener
    private val aautoTouch: ProjectionTouchListener

    init {
        touchController = ProjectionTouchController(appContext) { /* статус касаний логируется контроллером */ }
        carplayTouch = ProjectionTouchListener(ProjectionStack.CARPLAY, touchController) {
            activeStack == ProjectionStack.CARPLAY
        }
        aautoTouch = ProjectionTouchListener(ProjectionStack.ANDROID_AUTO, touchController) {
            activeStack == ProjectionStack.ANDROID_AUTO
        }
        carplayHost = CarplaySurfaceHost(appContext) { status ->
            publish("carplay.status", status.toMap())
            syncTouchBuffer(ProjectionStack.CARPLAY, status.bufferWidth, status.bufferHeight)
        }
        aautoHost = AndroidAutoSurfaceHost(appContext) { status ->
            publish("android_auto.status", status.toMap())
            syncTouchBuffer(ProjectionStack.ANDROID_AUTO, status.bufferWidth, status.bufferHeight)
        }

        carplayView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                st: android.graphics.SurfaceTexture,
                width: Int,
                height: Int,
            ) = carplayHost.onSurfaceTextureAvailable(st)

            override fun onSurfaceTextureSizeChanged(
                st: android.graphics.SurfaceTexture,
                width: Int,
                height: Int,
            ) = Unit // размер буфера задаёт хост, не размер View

            override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                carplayHost.onSurfaceTextureDestroyed()
                return true
            }

            override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) = Unit
        }
        aautoView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                st: android.graphics.SurfaceTexture,
                width: Int,
                height: Int,
            ) = aautoHost.onSurfaceTextureAvailable(st)

            override fun onSurfaceTextureSizeChanged(
                st: android.graphics.SurfaceTexture,
                width: Int,
                height: Int,
            ) = Unit

            override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                aautoHost.onSurfaceTextureDestroyed()
                return true
            }

            override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) = Unit
        }
        carplayView.setOnTouchListener(carplayTouch)
        aautoView.setOnTouchListener(aautoTouch)
        carplayView.visibility = View.GONE
        aautoView.visibility = View.GONE
    }

    // --- Команды из JS (вызывается из UiBridge; поток JS-моста) ---

    /**
     * Вкладка протокола стала активной. Показываем оверлей (SurfaceTexture
     * появится после layout), поднимаем binding сессии касаний — как
     * `ProjectionTouchSurface._sync` при enabled=true, — и просим рендер.
     */
    fun activate(stack: ProjectionStack) {
        activeStack = stack
        touchController.bind(stack)
        mainHandler.post { viewFor(stack).visibility = View.VISIBLE }
        when (stack) {
            ProjectionStack.CARPLAY -> {
                carplayHost.activate(width = null, height = null) { /* статус придёт пушем */ }
                carplayHost.ensureBound { }
            }

            ProjectionStack.ANDROID_AUTO -> {
                aautoHost.activate(width = null, height = null) { }
                aautoHost.ensureBound { }
            }
        }
    }

    /**
     * Вкладка ушла (переключение, отключение телефона, выключение тумблера,
     * пауза не отсюда — onPause идёт синхронно отдельно).
     *
     * Порядок как в capy: сначала поднять пальцы и снять binding касаний,
     * затем синхронно на главном потоке освободить Surface (R8: detach раньше
     * освобождения буферов), затем dartActive=false + unbind на потоке хоста.
     */
    fun deactivate(stack: ProjectionStack) {
        if (activeStack == stack) activeStack = null
        touchController.unbind(stack) // поднимает зависшие пальцы, затем unbind
        mainHandler.post {
            val view = viewFor(stack)
            when (stack) {
                ProjectionStack.CARPLAY -> carplayHost.releaseSurfaceSynchronously()
                ProjectionStack.ANDROID_AUTO -> aautoHost.releaseSurfaceSynchronously()
            }
            view.visibility = View.GONE
        }
        when (stack) {
            ProjectionStack.CARPLAY -> carplayHost.deactivate { }
            ProjectionStack.ANDROID_AUTO -> aautoHost.deactivate { }
        }
    }

    /** Ручной Reconnect (R3: same-surface re-attach, без detach перед ним). */
    fun refresh(stack: ProjectionStack) {
        when (stack) {
            ProjectionStack.CARPLAY -> carplayHost.requestRefresh { }
            ProjectionStack.ANDROID_AUTO -> aautoHost.requestRefresh { }
        }
    }

    /**
     * Прямоугольник видео-области в физических пикселях окна. Нулевой размер —
     * спрятать оверлей.
     */
    fun frame(stackName: String, x: Float, y: Float, w: Float, h: Float) {
        val stack = stackFromWire(stackName) ?: return
        mainHandler.post {
            if (stack != activeStack) return@post
            val view = viewFor(stack)
            if (w <= 0f || h <= 0f) {
                view.visibility = View.GONE
                return@post
            }
            view.layout(
                x.toInt(),
                y.toInt(),
                (x + w).toInt(),
                (y + h).toInt(),
            )
        }
    }

    /**
     * Повторная публикация обоих статусов — после перезагрузки страницы UI
     * (state WebView сброшен, хосты в Kotlin живы). Как onListen EventChannel
     * в capy, который всегда начинает с текущего снимка.
     */
    fun publishStatuses() {
        // Как getStatus в capy: не только читаем, но и поднимаем binding —
        // иначе bound навсегда false за циклической зависимостью «activate
        // биндит, а activate доступен только с открытой вкладкой».
        carplayHost.ensureBound { }
        aautoHost.ensureBound { }
        publish("carplay.status", carplayHost.status().toMap())
        publish("android_auto.status", aautoHost.status().toMap())
    }

    // --- Жизненный цикл активности (как в MainActivity capy) ---

    fun onActivityResumed() {
        carplayHost.onActivityResumed()
        aautoHost.onActivityResumed()
    }

    /**
     * R4: синхронно, на главном потоке, ДО super.onPause() в MainActivity.
     * Posted- detach мог бы прилететь после того, как OEM CarplayActivity
     * построит свою поверхность, и разрушить её EGL-состояние.
     * Порядок: CarPlay → Android Auto → подъём активных жестов касаний.
     */
    fun onActivityPausedSynchronously() {
        carplayHost.onActivityPaused()
        aautoHost.onActivityPaused()
        touchController.releaseGestures()
        carplayTouch.reset()
        aautoTouch.reset()
    }

    /** Эквивалент cleanUpFlutterEngine: dispose мостов. */
    fun dispose() {
        activeStack = null
        carplayHost.dispose()
        aautoHost.dispose()
        touchController.dispose()
        mainHandler.post {
            carplayView.visibility = View.GONE
            aautoView.visibility = View.GONE
        }
    }

    // --- Внутреннее ---

    private fun viewFor(stack: ProjectionStack): TextureView = when (stack) {
        ProjectionStack.CARPLAY -> carplayView
        ProjectionStack.ANDROID_AUTO -> aautoView
    }

    /**
     * Буфер касаний = геометрия, переданная в notifyMainSurfaceAttached
     * (гарантия capy: «the geometry the caller maps into must be the geometry
     * that was passed to notifyMainSurfaceAttached»).
     */
    private fun syncTouchBuffer(stack: ProjectionStack, width: Int, height: Int) {
        touchController.setBufferSize(stack, width, height)
        when (stack) {
            ProjectionStack.CARPLAY -> {
                carplayTouch.bufferWidth = width
                carplayTouch.bufferHeight = height
            }

            ProjectionStack.ANDROID_AUTO -> {
                aautoTouch.bufferWidth = width
                aautoTouch.bufferHeight = height
            }
        }
    }

    private fun publish(name: String, map: Map<String, Any?>) {
        runCatching { push(name, JSONObject(map).toString()) }
            .onFailure { Log.w(TAG, "publish $name failed", it) }
    }

    companion object {
        private const val TAG = "ProjectionRender"

        fun stackFromWire(name: String): ProjectionStack? = when (name) {
            "carplay" -> ProjectionStack.CARPLAY
            "androidAuto" -> ProjectionStack.ANDROID_AUTO
            else -> null
        }
    }
}
