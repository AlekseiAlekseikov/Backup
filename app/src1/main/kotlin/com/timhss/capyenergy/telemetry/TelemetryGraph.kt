package com.timhss.capyenergy.telemetry

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.timhss.capyenergy.concurrent.namedSingleThreadExecutor
import com.timhss.capyenergy.concurrent.namedSingleThreadScheduledExecutor
import com.timhss.capyenergy.helpers.AudioTrackTemperatureModeFeedback
import com.timhss.capyenergy.helpers.MediaKeyserverController
import com.timhss.capyenergy.helpers.TemperatureModeHelperMonitor
import com.timhss.capyenergy.profile.SignalKey
import com.timhss.capyenergy.roadcast.RoadcastDaemon
import com.timhss.capyenergy.roadcast.RoadcastRepository
import com.timhss.capyenergy.roadcast.RoadcastUpdateManager
import com.timhss.capyenergy.service.AutoOpenLauncher
import com.timhss.capyenergy.telemetry.db.RoomVehicleIdAliasStore
import com.timhss.capyenergy.telemetry.db.TelemetryDatabase
import com.timhss.capyenergy.update.ChargeControlAppManager
import com.timhss.capyenergy.vehicle.HvacClimateController
import com.timhss.capyenergy.vehicle.VehiclePropertyHelper
import java.util.concurrent.TimeUnit
import com.timhss.capyenergy.projection.PresenceState
import com.timhss.capyenergy.projection.ProjectionPresenceMonitor
import com.timhss.capyenergy.projection.ProjectionPresenceSnapshot

/**
 * State the collaborators read but the lifecycle owns.
 *
 * [RangeEstimateMonitor] needs to know whether collection is running and when
 * this run began, and it is constructed long before either is true. A small
 * shared holder keeps those two facts in one place instead of threading two
 * lambdas back into the object that builds it.
 */
internal class TelemetryCollectionState {
    /**
     * When the current telemetry run began, in `elapsedRealtimeNanos`. Cleared
     * on stop so a value left in [SignalStateStore] by an earlier run cannot
     * appear during a restart before the new initial read.
     */
    @Volatile
    var startedAtElapsedNanos: Long? = null

    @Volatile
    var running = false

    /** Last known daemon status, or null before the first attempt. */
    @Volatile
    var roadcastStatus: RoadcastDaemon.Status? = null
}

/**
 * Construction and wiring for the telemetry engine.
 *
 * This is the composition root and nothing else. It holds no lifecycle (see
 * [TelemetryRuntime]) and answers no Flutter call (see [TelemetryFacade]). It
 * was split out of `TelemetryRuntime`, which had grown to 759 lines and was
 * doing all three jobs at once: the collaborators were each small and tested,
 * while the wiring that decides how they see each other was neither.
 *
 * Declaration order matters here. Several collaborators take lambdas over
 * others, so a property moved above its dependency compiles and then reads null
 * at run time.
 */
internal class TelemetryGraph(
    context: Context,
    injectedProjectionPresence: (() -> ProjectionPresenceSnapshot)? = null,
    // GeelyTools deviation: Capy Companion support (phone pairing, BLE live
    // stream, Supabase cloud sync) is excluded — telemetry is strictly local.
) {
    val appContext: Context = context.applicationContext
    val database = TelemetryDatabase.get(appContext)
    val state = TelemetryCollectionState()

    val maintenanceExecutor = namedSingleThreadScheduledExecutor("maintenance")

    /**
     * Long jobs, off the scheduler thread.
     *
     * The collector tick is what drives 1 Hz frame persistence, and it used to
     * share one thread with retention, export and the Roadcast install. A
     * streaming export of a full database holds that thread for minutes, so the
     * cadence it was supposed to protect stopped for the whole export and the
     * missed fixed-rate ticks then fired back to back. Jobs still share one
     * thread with each other, because export, retention and wipe must not
     * overlap.
     */
    val jobExecutor = namedSingleThreadExecutor("telemetry-job")
    val detectorExecutor = namedSingleThreadExecutor("detectors")
    val identityExecutor = namedSingleThreadScheduledExecutor("veh-identity")

    /**
     * Dedicated single-thread scheduled executor for the closed-trip efficiency
     * behind the range estimate. It stays alive across start/stop cycles; only
     * the scheduled task is cancelled on stop, so a restart can schedule again.
     */
    val rangeExecutor = namedSingleThreadScheduledExecutor("range-refresh")

    val policy = TelemetryPolicy()
    val normalizer = SignalNormalizer()
    val store = SignalStateStore(detectorExecutor)
    val settings = TelemetrySettings(appContext)

    // GeelyTools deviation: Capy Companion support (phone pairing, BLE live
    // stream, Supabase cloud sync) is excluded — telemetry is strictly local.

    /**
     * Layered vehicle identity, resolved before the first session of a boot
     * is recorded. See [VehicleIdentityResolver] for the anchor order.
     */
    val vehicleIdentity = VehicleIdentityResolver(
        settings = settings,
        aliases = RoomVehicleIdAliasStore(database.vehicleIdAliasDao(), database.sessionDao()),
        identityFile = defaultIdentityFile(),
        vinReader = { vehiclePropertyHelper.readVin() },
        androidIdProvider = {
            Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
        },
        hasRecordedSessions = { database.sessionDao().count() > 0 },
        legacyVehicleIdsProvider = { database.sessionDao().distinctVehicleIds() }
    )

    /**
     * Presence of a projection session, for the sync gate alone.
     *
     * This is a **second** monitor. `ProjectionPresenceBridge` owns one for the
     * Flutter screens, and that one lives with the activity and starts on the
     * first Dart listener. The sync server runs in the foreground service, which
     * outlives every activity, so it cannot read that instance. Both are cheap:
     * one receiver and two probe bindings each, and `start()` is idempotent.
     *
     * Null when a caller injects its own reading, which is how a test states a
     * presence without binding to an OEM service that a JVM does not have.
     */
    val projectionPresenceMonitor: ProjectionPresenceMonitor? =
        if (injectedProjectionPresence == null) {
            ProjectionPresenceMonitor(appContext) { /* the gate polls; it does not listen */ }
        } else {
            null
        }

    /**
     * What the sync gate reads. `UNKNOWN` permits: by design: this head unit answers `UNKNOWN`
     * permanently when the OEM binder is absent, and a gate that waited for
     * `DISCONNECTED` would never sync on such a car.
     */
    val projectionPresenceSnapshotProvider: () -> ProjectionPresenceSnapshot =
        injectedProjectionPresence
            ?: { projectionPresenceMonitor?.snapshot() ?: ProjectionPresenceSnapshot.unknown }

    // GeelyTools deviation: Capy Companion support (phone pairing, BLE live
    // stream, Supabase cloud sync) is excluded — telemetry is strictly local.
    val eventRepository = EventRepository(
        appContext,
        { settings.debugEventFileEnabled() },
        accountIdProvider = {
            AccountIdProvider.of(
                cloudSyncEnabled,
                settings.pairingStatus(),
                settings.accountId()
            )
        },
    )
    /** Told after every session write; the bridge turns it into an event. */
    val sessionChanges = SessionChangeBroadcaster()

    val sessionRepository = SessionRepository(
        context = appContext,
        capacityWhProvider = { resolveCapacityWh() },
        vehicleIdProvider = { resolveVehicleId() },
        defaultChargeCostPerKwhProvider = { settings.defaultChargeCostPerKwh() },
        chargeCostCurrencyProvider = { settings.chargeCostCurrency() },
        changes = sessionChanges,
        accountIdProvider = {
            AccountIdProvider.of(
                cloudSyncEnabled,
                settings.pairingStatus(),
                settings.accountId()
            )
        },
    )

    val batteryCycleRepository = BatteryCycleRepository(
        context = appContext,
        capacityWhProvider = { resolveCapacityWh() },
        accountIdProvider = {
            AccountIdProvider.of(
                cloudSyncEnabled,
                settings.pairingStatus(),
                settings.accountId()
            )
        },
    )

    val annotationChanges = AnnotationChangeBroadcaster()
    val insightRepository = InsightRepository(
        appContext,
        accountIdProvider = {
            AccountIdProvider.of(
                cloudSyncEnabled,
                settings.pairingStatus(),
                settings.accountId()
            )
        },
    )
    val journeyRepository = JourneyRepository(
        context = appContext,
        annotationChanges = annotationChanges,
        accountIdProvider = {
            AccountIdProvider.of(
                cloudSyncEnabled,
                settings.pairingStatus(),
                settings.accountId()
            )
        },
    )
    val preferenceRepository = PreferenceRepository(
        context = appContext,
        annotationChanges = annotationChanges,
        accountIdProvider = {
            AccountIdProvider.of(
                cloudSyncEnabled,
                settings.pairingStatus(),
                settings.accountId()
            )
        },
    )
    val retentionManager = TelemetryRetentionManager(appContext, capacityWhProvider = { resolveCapacityWh() })

    /**
     * GeelyTools deviation: Capy Companion support (phone pairing, BLE live
     * stream, Supabase cloud sync) is excluded — telemetry is strictly local.
     * The flag stays so every repository keeps Capy's exact account stamping
     * path; with the cloud gone it always resolves to a null account id.
     */
    val cloudSyncEnabled: Boolean get() = false

    val databaseHealthReporter = DatabaseHealthReporter(appContext, database)
    val locationSignalProvider = LocationSignalProvider(appContext)
    val vehicleActivityDetector = VehicleActivityDetector()
    val vehiclePropertyHelper = VehiclePropertyHelper(appContext) { settings.packCapacityWh() }
    val chargeControlAppManager = ChargeControlAppManager(appContext)
    var targetSocChangeBroadcaster: ((Int) -> Unit)? = null
    var chargeControlStateBroadcaster: ((com.timhss.capyenergy.ipc.ChargeControlIpcState) -> Unit)? = null
    val chargeControlIpcClient = com.timhss.capyenergy.ipc.ChargeControlIpcClient(
        context = appContext,
        settings = settings,
        onTargetSocChanged = { targetSoc ->
            targetSocChangeBroadcaster?.invoke(targetSoc)
        },
        onStateChanged = { state ->
            chargeControlStateBroadcaster?.invoke(state)
        }
    )
    val temperatureModeHelperMonitor = TemperatureModeHelperMonitor(
        keyserverController = MediaKeyserverController(appContext),
        hvacClimateController = HvacClimateController(vehiclePropertyHelper),
        audioFeedback = AudioTrackTemperatureModeFeedback(),
        enabledProvider = { settings.temperatureModeHelperEnabled() }
    )
    val tripSessionDetector = TripSessionDetector(
        eventRepository,
        sessionRepository,
        { locationSignalProvider.latestSnapshot() }
    )

    /**
     * Um só para os dois consumidores: o detector e o repositório de frames
     * precisam concordar sobre quando a leitura do carregador congelou, senão
     * um encerra a sessão enquanto o outro ainda grava potência. Os dois rodam
     * no `detectorExecutor`, que é de uma thread só.
     */
    val chargerReadingGuard = ChargerReadingGuard()

    /** Mesmo motivo, para a potência DC. Ver [SignalFreezeGuard]. */
    val dcChargePowerGuard = SignalFreezeGuard(SignalKey.EV_DC_CHARGE_POWER)
    /**
     * Opens the app on the plug, in place of the factory charging screen.
     *
     * It reads nothing: the detector below tells it when the plug went in.
     */
    val chargingAutoOpenLauncher = AutoOpenLauncher(
        context = appContext,
        tag = AutoOpenLauncher.TAG_CHARGING,
        destination = AutoOpenLauncher.DESTINATION_CHARGING,
        enabled = { settings.replaceOemChargingEnabled() },
        restingModeActive = { ParkedSessionDetector.someoneIsRestingInside(store.snapshot()) }
    )

    val chargeSessionDetector = ChargeSessionDetector(
        eventRepository,
        sessionRepository,
        chargerReadingGuard,
        dcChargePowerGuard,
        { locationSignalProvider.latestSnapshot() },
        onChargingBegan = chargingAutoOpenLauncher::requestOpen,
    )

    val parkedSessionDetector = ParkedSessionDetector(
        sessionRepository = sessionRepository,
        tripActiveProvider = { tripSessionDetector.activeFrameSession() != null },
        chargeActiveProvider = { chargeSessionDetector.activeFrameSession() != null },
        capacityWhProvider = { resolveCapacityWh() }
    )

    val continuousSessionDetector = ContinuousSessionDetector(
        sessionRepository = sessionRepository,
        enabledProvider = { settings.continuousModeEnabled() }
    )

    // A signal event now knows which session it happened in. It is wired here,
    // after the detectors exist, because the repository is built before them.
    init {
        eventRepository.activeSessionProvider = {
            chargeSessionDetector.activeFrameSession()
                ?: tripSessionDetector.activeFrameSession()
                ?: parkedSessionDetector.activeFrameSession()
        }
    }

    // Explicit type: this and roadcastTripMetricsMonitor each hold a lambda over
    // the other, which leaves inference chasing its own tail.
    val frameRepository: FrameRepository = FrameRepository(
        context = appContext,
        activeSessionProvider = {
            chargeSessionDetector.activeFrameSession() ?: tripSessionDetector.activeFrameSession()
        },
        parkedSessionProvider = {
            parkedSessionDetector.activeFrameSession()
        },
        continuousSessionProvider = {
            continuousSessionDetector.activeFrameSession()
        },
        locationProvider = { locationSignalProvider.latestSnapshot() },
        roadcastTripMetricsProvider = { roadcastTripMetricsMonitor.latest() },
        capacityWhProvider = { resolveCapacityWh() },
        chargerReadingGuard = chargerReadingGuard,
        dcChargePowerGuard = dcChargePowerGuard,
        activeAccountIdProvider = {
            AccountIdProvider.of(
                cloudSyncEnabled,
                settings.pairingStatus(),
                settings.accountId()
            )
        },
    )

    val ecarxSignalProvider = EcarxSignalProvider(appContext)

    // Roadcast stays independent from Flutter so native collection continues when
    val roadcastDaemon = RoadcastDaemon(appContext)
    val roadcastUpdateManager = RoadcastUpdateManager(appContext)
    val roadcastRepository = RoadcastRepository()
    val roadcastTripMetricsMonitor = RoadcastTripMetricsMonitor(
        roadcastRepository,
        // A trip and a charge, and deliberately **not** a parked session.
        // `ParkedSessionDetector` opens one after 30 s in P and keeps it open
        // for as long as the car sits there, so counting it would leave the
        // garage at 60 Hz — which is the whole case this cadence exists to
        // cut. The bus-rate argument does not hold parked either: traction
        // power is zero and pack current is small and slow, so `pack - drive`
        // is no longer a small remainder between two large signals.
        // `liveParkedEnergyBuckets` therefore integrates at 1 Hz.
        sessionActive = {
            tripSessionDetector.activeFrameSession() != null ||
                chargeSessionDetector.activeFrameSession() != null
        },
        onMetrics = { frameRepository.foldCanSample(it) },
    )

    val vhalSubscriptionManager = VhalSubscriptionManager(
        context = appContext,
        policy = policy,
        normalizer = normalizer,
        store = store,
        ecarxSignalProvider = ecarxSignalProvider
    )

    val rangeEfficiencyRepository = RangeEfficiencyRepository(appContext)
    val rangeEstimateMonitor = RangeEstimateMonitor(
        store = store,
        efficiencyProvider = { rangeEfficiencyRepository.snapshot() },
        capacityWhProvider = { settings.packCapacityWh() },
        collectionStartedAtElapsedNanos = { state.startedAtElapsedNanos },
        isCollecting = { state.running }
    )

    // GeelyTools deviation: Capy Companion support (phone pairing, BLE live
    // stream, Supabase cloud sync) is excluded — telemetry is strictly local.

    init {
        sessionRepository.setFrameWriteBarrier { frameRepository.awaitPendingWrites() }
        retentionManager.setActiveSessionProvider {
            chargeSessionDetector.activeFrameSession() != null ||
                tripSessionDetector.activeFrameSession() != null
        }

        temperatureModeHelperMonitor.ensureKeyserverRestored()
        // Recovery blocks on the write queue and replays every pending session's
        // frames, so it cannot run where this object is built: TelemetryRuntime.get()
        // is reached from configureFlutterEngine and from the service, both on the
        // main thread. detectorExecutor is single-threaded, so queuing it first
        // still guarantees recovery completes before the detectors restore.
        detectorExecutor.execute {
            runCatching { vehicleIdentity.bootstrap() }
                .onFailure { error ->
                    Log.w(TAG, "Vehicle identity bootstrap failed", error)
                }
        }
        scheduleVinRetry(0)
        detectorExecutor.execute {
            runCatching { sessionRepository.recoverPendingFinalizations() }
                .onFailure { error ->
                    Log.w(TAG, "Pending session finalization recovery failed", error)
                }
        }
        detectorExecutor.execute { tripSessionDetector.restoreIfNeeded() }
        detectorExecutor.execute { chargeSessionDetector.restoreIfNeeded() }
        detectorExecutor.execute { parkedSessionDetector.restoreIfNeeded() }
        detectorExecutor.execute { continuousSessionDetector.restoreIfNeeded() }
        store.addListener(eventRepository)
        store.addListener(temperatureModeHelperMonitor)
        store.addListener(tripSessionDetector)
        store.addListener(chargeSessionDetector)
        store.addListener(parkedSessionDetector)
        store.addListener(continuousSessionDetector)
        store.addListener(frameRepository)
        chargeControlIpcClient.start()
    }

    fun activeSessionType(): String? =
        chargeSessionDetector.activeFrameSession()?.type
            ?: tripSessionDetector.activeFrameSession()?.type

    /**
     * The pairing account for the read surface, or null when the car is not
     * approved. The same authority the writers use, so a list filters by the
     * same account that stamped the rows; unowned rows stay visible to
     * whoever reads (the DAO applies the adoption rule).
     */
    fun partialCurrentAccountId(): String? =
        AccountIdProvider.of(
            cloudSyncEnabled,
            settings.pairingStatus(),
            settings.accountId()
        )

    /**
     * Pack capacity for every consumer, in Wh.
     *
     * One route, and it does not touch the car: the reader states the pack in
     * Settings. See [GeelyProfile.battery] for why the vehicle property is not
     * read here, or anywhere.
     */
    fun resolveCapacityWh(): Double = settings.packCapacityWh()

    fun resolveVehicleId(): String = settings.vehicleId() ?: "unassigned"

    /**
     * The VIN is the best anchor, so a boot that could not read it retries
     * with backoff instead of settling for a lesser anchor forever. Once the
     * VIN is adopted the upgrade has already written the alias row; the loop
     * stops.
     */
    private fun scheduleVinRetry(attempt: Int) {
        if (attempt >= VIN_RETRY_MAX_ATTEMPTS) {
            Log.i(TAG, "VIN still unreadable after retries; will try again on next boot")
            return
        }
        identityExecutor.schedule({
            runCatching { vehicleIdentity.tryVinUpgrade() }
                .onFailure { error -> Log.w(TAG, "VIN retry failed", error) }
            if (settings.vehicleIdAnchor() != VehicleIdAnchor.VIN) {
                scheduleVinRetry(attempt + 1)
            }
        }, vinRetryDelayMillis(attempt), TimeUnit.MILLISECONDS)
    }

    private fun vinRetryDelayMillis(attempt: Int): Long =
        minOf(VIN_RETRY_BASE_DELAY_MILLIS shl attempt, VIN_RETRY_MAX_DELAY_MILLIS)

    private companion object {
        const val TAG = "TelemetryGraph"
        private const val VIN_RETRY_BASE_DELAY_MILLIS = 5_000L
        private const val VIN_RETRY_MAX_DELAY_MILLIS = 60_000L
        private const val VIN_RETRY_MAX_ATTEMPTS = 20
    }
}
