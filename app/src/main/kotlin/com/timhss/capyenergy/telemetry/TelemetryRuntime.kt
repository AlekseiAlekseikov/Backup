package com.timhss.capyenergy.telemetry


import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.timhss.capyenergy.roadcast.RoadcastClient
import com.timhss.capyenergy.roadcast.RoadcastDaemon
import java.util.concurrent.Callable
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Lifecycle and commands for the telemetry engine.
 *
 * Construction and wiring live in [TelemetryGraph]; the read surface the method
 * channel calls lives in [TelemetryFacade]; the start and stop order lives in
 * [CollectionSequence]. This class owns what is left: the schedules, the
 * mutating commands, and the process-wide instance.
 *
 * The read functions are delegated rather than moved, so `TelemetryBridge` and
 * the Dart side see the same surface they saw before the split.
 */
class TelemetryRuntime internal constructor(
    context: Context,
    injectedNow: () -> Long = System::currentTimeMillis,
) {

    /**
     * Clock for boot registration timestamps. Injected for tests so a test
     * can prove that a registration that failed before the VIN landed is
     * re-keyed under the VIN the moment the upgrade happened — the B1 race
     * the runtime wiring must survive. Defaults to wall time.
     */
    private var bootRegistrationClock: () -> Long = injectedNow

    private val graph = TelemetryGraph(context)
    private val sequence = CollectionSequence(Participants())
    private val facade = TelemetryFacade(graph) { sequence.running }

    // A proposal acceptance runs the car's write path, which lives on this
    // object (it owns the settings and the cycle refold). The repository
    // cannot reach it, so the handler is set after construction.
    init {
        graph.preferenceRepository.setProposalAcceptedHandler { key, value ->
            applyPreferenceWrite(key, value)
        }
        // GeelyTools deviation: Capy Companion support (phone pairing, BLE live
    // stream, Supabase cloud sync) is excluded — telemetry is strictly local.
    }

    private var collectorTick: ScheduledFuture<*>? = null

    /**
     * Finding 10: retention otherwise runs only at startup, so a run that
     * starts with an active session never gets another chance. The daily
     * timer retries it; [runRetention][com.timhss.capyenergy.telemetry.TelemetryRetentionManager.runRetention]
     * itself skips while a session is active and is cooldown-gated, so an
     * idle tick is a cheap no-op.
     */
    @Volatile
    private var retentionTick: ScheduledFuture<*>? = null

    @Volatile
    private var rangeRefresh: ScheduledFuture<*>? = null

    val publisher = LiveTelemetryPublisher(graph.store) { facade.liveMetadataMap() }

    // GeelyTools deviation: the WebView shell (navigation / quick access /
    // body callouts) keeps a thin live feed of its own. The Flutter telemetry
    // app owns `publisher` through its EventChannel, and the publisher holds a
    // single sink, so the shell gets a dedicated publisher instance over the
    // same signal store instead of stealing the Flutter subscription.
    fun newLivePublisher(): LiveTelemetryPublisher =
        LiveTelemetryPublisher(graph.store) { facade.liveMetadataMap() }

    // GeelyTools deviation: GPS fix for the shell map/navigation feed
    // (the same statusMap the live frame metadata carries under "location").
    fun locationStatusMap(): Map<String, Any?> = graph.locationSignalProvider.statusMap()

    /**
     * The account the car is currently paired to, or null when it is not
     * approved. Consumed by the read surface so the history lists can apply
     * the local ownership stamp without any hiding behaviour — the stamping
     * and adoption rules live in the DAO queries.
     */
    fun partialCurrentAccountId(): String? = graph.partialCurrentAccountId()

    /** Session writes, for the bridge to push to Flutter. */
    val sessionChanges: SessionChangeBroadcaster get() = graph.sessionChanges

    /** Annotation writes, for the bridge to push to Flutter. */
    val annotationChanges: AnnotationChangeBroadcaster get() = graph.annotationChanges
    val vehicleSpeedPublisher = VehicleSpeedPublisher(graph.store)
    val database: com.timhss.capyenergy.telemetry.db.TelemetryDatabase get() = graph.database

    init {
        graph.chargeControlIpcClient.start()
    }

    fun start() = sequence.start()

    fun stop() = sequence.stop()

    /**
     * The lifecycle calls, kept off the public surface.
     *
     * They are steps of [CollectionSequence], not operations a caller may invoke
     * on their own: starting the Roadcast repository without its trip-metrics
     * monitor is exactly the state that produced four days of null CAN columns.
     */
    private inner class Participants : CollectionParticipants {
        override var collectionRunning: Boolean
            get() = graph.state.running
            set(value) {
                graph.state.running = value
            }

        override fun markCollectionStarted() {
            graph.state.startedAtElapsedNanos = SystemClock.elapsedRealtimeNanos()
        }

        override fun markCollectionStopped() {
            graph.state.startedAtElapsedNanos = null
        }

        override fun startRangeRefresh() {
            rangeRefresh = graph.rangeExecutor.scheduleWithFixedDelay(
                { runCatching { graph.rangeEstimateMonitor.refreshEfficiency() } },
                // Immediate first refresh, then fixed delay: the efficiency behind
                // the estimate must not sit at EFFICIENCY_LOADING for a minute.
                0L,
                RANGE_EFFICIENCY_REFRESH_SECONDS,
                TimeUnit.SECONDS
            )
        }

        override fun stopRangeRefresh() {
            rangeRefresh?.cancel(false)
            rangeRefresh = null
        }

        override fun startVhalSubscriptions() {
            graph.vhalSubscriptionManager.start()
        }

        override fun stopVhalSubscriptions() {
            graph.vhalSubscriptionManager.stop()
        }

        override fun startRoadcastRepository() {
            graph.roadcastRepository.start()
        }

        override fun stopRoadcastRepository() {
            graph.roadcastRepository.stop()
        }

        override fun startRoadcastTripMetrics() {
            graph.roadcastTripMetricsMonitor.start()
        }

        override fun stopRoadcastTripMetrics() {
            graph.roadcastTripMetricsMonitor.stop()
        }

        override fun startParkedSessionDetector() {
        }

        override fun stopParkedSessionDetector() {
        }

        override fun startContinuousSessionDetector() {
        }

        override fun stopContinuousSessionDetector() {
            graph.continuousSessionDetector.stop()
        }

        override fun gpsEnabled(): Boolean = graph.settings.gpsEnabled()

        override fun startLocation() {
            graph.locationSignalProvider.start()
        }

        override fun stopLocation() {
            graph.locationSignalProvider.stop()
        }

        override fun startCollectorTick() {
            collectorTick = graph.maintenanceExecutor.scheduleAtFixedRate(
                { runCatching { onCollectorTick() } },
                COLLECTOR_TICK_SECONDS,
                COLLECTOR_TICK_SECONDS,
                TimeUnit.SECONDS
            )
            // GeelyTools deviation: Capy Companion support (phone pairing, BLE live
    // stream, Supabase cloud sync) is excluded — telemetry is strictly local.
            // Finding 10: startup retention skips while a session is active
            // and never retries within the process. This re-runs it daily;
            // on the job executor so a long sweep never blocks the scheduler.
            retentionTick = graph.maintenanceExecutor.scheduleWithFixedDelay(
                {
                    graph.jobExecutor.execute {
                        runCatching { graph.retentionManager.runRetention(force = false) }
                            .onFailure { error -> Log.w(TAG, "Scheduled retention failed", error) }
                    }
                },
                RETENTION_INTERVAL_SECONDS,
                RETENTION_INTERVAL_SECONDS,
                TimeUnit.SECONDS
            )
        }

        override fun stopCollectorTick() {
            collectorTick?.cancel(false)
            collectorTick = null
            retentionTick?.cancel(false)
            retentionTick = null
        }

        override fun runStartupMaintenance() {
            graph.jobExecutor.execute {
                // GeelyTools deviation: Capy Companion support (phone pairing, BLE live
    // stream, Supabase cloud sync) is excluded — telemetry is strictly local.
                // Backfill first and unconditionally: retention is rate limited to
                // once a day, so sessions left without permanent metrics —
                // everything that predates session_aggregates — would otherwise
                // wait for a run that may not happen today, and the lists would
                // read null energy until it did.
                runCatching { graph.retentionManager.backfillAggregates() }
                    .onFailure { error -> Log.w(TAG, "Aggregate backfill failed", error) }
                runCatching { graph.retentionManager.runRetention(force = false) }
            }
            graph.maintenanceExecutor.execute { graph.databaseHealthReporter.refreshIfStale() }
        }

        override fun ensureRoadcastDaemon() {
            // Daemon installation and local ADB I/O stay off the service start thread.
            graph.jobExecutor.execute {
                graph.state.roadcastStatus = runCatching { graph.roadcastDaemon.ensureRunning() }
                    .getOrElse { error ->
                        RoadcastDaemon.Status(
                            running = false,
                            socketReachable = false,
                            error = error.message ?: error::class.java.simpleName
                        )
                    }
            }
        }


        override fun startBleServer() {
            // Started here rather than with the server: the v2 shell shows the
            // CarPlay and Android Auto destinations from this monitor, and one
            // that has never bound answers UNKNOWN forever.
            graph.projectionPresenceMonitor?.start()
            // GeelyTools deviation: Capy Companion support (phone pairing, BLE live
    // stream, Supabase cloud sync) is excluded — telemetry is strictly local.
        }

        override fun stopBleServer() {
            // GeelyTools deviation: Capy Companion support (phone pairing, BLE live
    // stream, Supabase cloud sync) is excluded — telemetry is strictly local.
        }

        override fun restoreKeyserver() {
            graph.temperatureModeHelperMonitor.ensureKeyserverRestored()
        }
    }

    private fun onCollectorTick() {
        if (!sequence.running) return
        graph.databaseHealthReporter.refreshIfStale()
        val snapshot = graph.store.snapshot()
        if (snapshot.isEmpty()) return
        val timestamp = SignalTimestamp(
            receivedAtUtcMillis = System.currentTimeMillis(),
            receivedAtElapsedNanos = SystemClock.elapsedRealtimeNanos(),
            sourceTimestampNanos = null,
            accuracy = TimestampAccuracy.INFERRED,
            uncertaintyMillis = COLLECTOR_TICK_SECONDS * 1_000L
        )
        graph.detectorExecutor.execute {
            graph.tripSessionDetector.onCollectorTick(timestamp, snapshot)
            graph.frameRepository.persistActiveSnapshot(timestamp, snapshot)
            graph.roadcastTripMetricsMonitor.syncCadence()

            // GeelyTools deviation: Capy Companion support (phone pairing, BLE live
    // stream, Supabase cloud sync) is excluded — telemetry is strictly local.
        }
    }

    // --- read surface, delegated to the facade -------------------------------

    fun status(): CollectorStatus = facade.status()

    fun collectorStatusMap(): Map<String, Any?> = facade.collectorStatusMap()

    fun vehicleCapabilitiesMap(): Map<String, Any?> = facade.vehicleCapabilitiesMap()

    fun latestSnapshotMap(): Map<String, Any?> = facade.latestSnapshotMap()

    fun roadcastStatusMap(): Map<String, Any?> = facade.roadcastStatusMap()

    fun roadcastUpdateStatusMap(): Map<String, Any?> = facade.roadcastUpdateStatusMap()

    fun checkRoadcastUpdate(): Map<String, Any?> = facade.checkRoadcastUpdate()

    fun rangeEstimate(): NativeRangeEstimate = facade.rangeEstimate()

    fun heading(): VehicleHeading = facade.heading()

    fun recentEventsMap(limit: Int): Map<String, Any?> = facade.recentEventsMap(limit)

    fun recentChargeSessions(limit: Int): ChargeSessionPage =
        facade.recentChargeSessions(limit)

    fun chargeMergeCandidates(limit: Int): ChargeMergeCandidatePage =
        facade.chargeMergeCandidates(limit)

    fun recentTripSessions(limit: Int): TripSessionPage =
        facade.recentTripSessions(limit)

    fun insightTrips(subjectId: String?): InsightTripsPage =
        facade.insightTrips(subjectId)

    fun insightPlaces(): List<InsightPlaceRow> = facade.insightPlaces()

    fun saveInsightPlace(
        id: String?,
        name: String,
        latitude: Double,
        longitude: Double,
        radiusM: Double,
        autoName: String? = null,
        autoNameUpdatedAtUtcMillis: Long? = null,
        autoNameSource: String? = null,
    ): InsightPlaceRow = facade.saveInsightPlace(id, name, latitude, longitude, radiusM, autoName, autoNameUpdatedAtUtcMillis, autoNameSource)

    fun deleteInsightPlace(id: String) = facade.deleteInsightPlace(id)

    fun preferenceRows(): List<com.timhss.capyenergy.telemetry.db.PreferenceEntity> =
        graph.preferenceRepository.activeRows()

    fun savePreferenceFromCar(
        scope: String,
        key: String,
        value: String?
    ): com.timhss.capyenergy.telemetry.db.PreferenceEntity? =
        graph.preferenceRepository.saveFromCar(scope, key, value)

    fun pendingPreferenceProposals(): List<com.timhss.capyenergy.telemetry.db.PreferenceProposalEntity> =
        graph.preferenceRepository.pendingProposals()

    fun proposePreference(key: String, value: String?): com.timhss.capyenergy.telemetry.db.PreferenceProposalEntity? =
        graph.preferenceRepository.propose(key, value)

    fun decidePreferenceProposal(
        id: String,
        accept: Boolean
    ): com.timhss.capyenergy.telemetry.db.PreferenceProposalEntity? =
        graph.preferenceRepository.decide(id, accept)

    /**
     * The car's write path for an accepted proposal.
     *
     * A proposal is inert until a person on the car accepts it. Acceptance
     * runs the same code the settings screen runs, so the cycle refold a new
     * pack capacity causes happens exactly once, at the moment of
     * confirmation. Answers false when the value cannot be written.
     */
    fun applyPreferenceWrite(key: String, value: String?): Boolean = when (key) {
        "pack_capacity_wh" -> {
            val capacity = value?.toDoubleOrNull()
                ?.takeIf { it.isFinite() && it > 0.0 }
            if (capacity == null) return false
            graph.settings.setPackCapacityWh(capacity)
            runCatching { graph.batteryCycleRepository.refresh() }
                .onFailure { error -> Log.w(TAG, "Battery cycle refresh after capacity proposal failed", error) }
            true
        }
        "default_charge_cost_per_kwh" -> {
            val rate = value?.toDoubleOrNull()
                ?.takeIf { it.isFinite() && it >= 0.0 }
            if (rate == null) return false
            graph.settings.setDefaultChargeCostPerKwh(rate)
            true
        }
        else -> false
    }

    fun batteryCycles(limit: Int): BatteryCyclePage = facade.batteryCycles(limit)

    fun batteryCycleSessions(ordinal: Long): BatteryCycleSessionsPage =
        facade.batteryCycleSessions(ordinal)

    fun liveEnergySeries(): LiveEnergyBuckets? = facade.liveEnergySeries()

    fun liveEfficiencySeries(): LiveEnergyBuckets? = facade.liveEfficiencySeries()

    fun liveChargeEnergySeries(): LiveEnergyBuckets? = facade.liveChargeEnergySeries()

    fun liveContinuousEnergySeries(): LiveEnergyBuckets? = facade.liveContinuousEnergySeries()

    fun efficiencyBucketMillis(): Long = facade.efficiencyBucketMillis()

    fun energySeriesInWindow(
        startUtcMillis: Long,
        endUtcMillis: Long
    ): WindowEnergySeries = facade.energySeriesInWindow(startUtcMillis, endUtcMillis)

    fun parkedEnergySeriesInWindow(
        startUtcMillis: Long,
        endUtcMillis: Long
    ): WindowEnergySeries = facade.parkedEnergySeriesInWindow(startUtcMillis, endUtcMillis)

    fun settingsMap(): Map<String, Any?> = facade.settingsMap()


    // ── Cloud device pairing (issue #227) ──────────────────────────────
    // GeelyTools deviation: Capy Companion support (phone pairing, BLE live
    // stream, Supabase cloud sync) is excluded — telemetry is strictly local.

    fun activeSessionType(): String? = graph.activeSessionType()

    // --- Roadcast commands ---------------------------------------------------

    fun restartRoadcast(): Map<String, Any?> {
        graph.roadcastTripMetricsMonitor.stop()
        graph.roadcastRepository.stop()
        val status = runCatching { graph.roadcastDaemon.restart() }
            .getOrElse { error ->
                RoadcastDaemon.Status(
                    running = false,
                    socketReachable = false,
                    error = error.message ?: error::class.java.simpleName
                )
            }
        graph.state.roadcastStatus = status
        graph.roadcastRepository.start()
        graph.roadcastTripMetricsMonitor.start()
        return facade.roadcastStatusMap()
    }

    /**
     * Makes the external Roadcast service current before an app APK is installed.
     * The app update must stop if the edge service cannot be verified or applied.
     */
    fun ensureRoadcastUpdatedForAppInstall(): Map<String, Any?> {
        activeSessionType()?.let { type ->
            error("Roadcast cannot update while a $type session is active")
        }
        val status = graph.roadcastUpdateManager.check(facade.installedRoadcastSha256())
        check(status.compatible) {
            "Roadcast update required by the app is incompatible: " +
                (status.error ?: "unknown compatibility error")
        }
        return if (status.updateAvailable) updateRoadcast() else status.toMap()
    }

    fun updateRoadcast(): Map<String, Any?> {
        activeSessionType()?.let { type ->
            error("Roadcast cannot update while a $type session is active")
        }
        val prepared = graph.roadcastUpdateManager.prepare(facade.installedRoadcastSha256())
        activeSessionType()?.let { type ->
            graph.roadcastUpdateManager.discard(prepared)
            error("Roadcast cannot update while a $type session is active")
        }

        graph.roadcastTripMetricsMonitor.stop()
        graph.roadcastRepository.stop()
        try {
            val updated = try {
                graph.roadcastDaemon.installAndRestart(
                    prepared.release.daemonFile,
                    prepared.release.manifest.daemon.sha256
                )
            } catch (error: Throwable) {
                graph.roadcastUpdateManager.discard(prepared)
                val rollback = runCatching { graph.roadcastDaemon.reinstallPreferred() }.getOrNull()
                graph.state.roadcastStatus = rollback
                throw IllegalStateException(
                    "Roadcast update activation failed; " +
                        if (rollback?.running == true) {
                            "previous release restored"
                        } else {
                            "rollback failed: ${rollback?.error ?: "unknown error"}"
                        },
                    error
                )
            }
            if (!updated.running) {
                graph.roadcastUpdateManager.discard(prepared)
                graph.state.roadcastStatus = graph.roadcastDaemon.reinstallPreferred()
                error(updated.error ?: "Roadcast update failed to start")
            }
            validateUpdatedRoadcast()?.let { validationError ->
                graph.roadcastUpdateManager.discard(prepared)
                val rollback = graph.roadcastDaemon.reinstallPreferred()
                graph.state.roadcastStatus = rollback
                check(rollback.running) {
                    "Roadcast update validation failed: $validationError; rollback failed: " +
                        (rollback.error ?: "unknown error")
                }
                error("Roadcast update validation failed: $validationError; previous release restored")
            }
            try {
                graph.roadcastUpdateManager.activate(prepared)
            } catch (error: Throwable) {
                runCatching { graph.roadcastUpdateManager.restorePrevious(prepared) }
                graph.roadcastUpdateManager.discard(prepared)
                graph.state.roadcastStatus = graph.roadcastDaemon.reinstallPreferred()
                throw error
            }
            graph.state.roadcastStatus = updated
            return graph.roadcastUpdateManager.completed(prepared).toMap()
        } finally {
            graph.roadcastRepository.start()
            graph.roadcastTripMetricsMonitor.start()
        }
    }

    private fun validateUpdatedRoadcast(): String? {
        val client = RoadcastClient.connect().getOrElse { error ->
            return error.message ?: "Roadcast client handshake failed"
        }
        return try {
            val status = client.status()
                ?: return "Roadcast client returned no status after handshake"
            if (!status.connected) {
                "Roadcast client disconnected after handshake"
            } else if (status.signalCount <= 0 || status.frameCount <= 0) {
                "Roadcast returned an empty schema after handshake"
            } else {
                val schema = runCatching { client.schema() }.getOrElse { error ->
                    return error.message ?: "Roadcast schema validation failed"
                }
                if (schema.size != status.signalCount) {
                    "Roadcast schema count does not match client status"
                } else {
                    null
                }
            }
        } finally {
            client.close()
        }
    }

    // --- session commands ----------------------------------------------------

    fun mergeChargeSessions(ids: List<String>): ChargeMergeOutcome =
        graph.sessionRepository.mergeChargeSessions(ids)

    fun updateChargeSessionCost(
        sessionId: String,
        costPerKwh: Double?,
        paidAmount: Double?,
        currency: String?
    ): ChargeCostUpdate {
        val update = graph.sessionRepository.updateChargeSessionCost(
            sessionId,
            costPerKwh,
            paidAmount,
            currency
        )
        // Pricing a charge changes the blend of the pack from that charge
        // forward, so every cycle that ends at or after it is wrong. The
        // repository rebuilds only that tail; the cycles before it are
        // untouched, because their blend cannot have changed.
        val priced = update.session?.session
        if (update.ok && priced != null) {
            val chargeStart = priced.chargeStartedAtUtcMillis
                ?: priced.startedAtUtcMillis
            runCatching { graph.batteryCycleRepository.invalidateFrom(chargeStart) }
                .onFailure { error ->
                    Log.w(TAG, "Battery cycle invalidation failed", error)
                }
        }
        return update
    }

    /**
     * Prices every unpriced past charge at the default rate.
     *
     * The cycles are rebuilt from the oldest charge that changed, for the same
     * reason one pricing edit rebuilds a tail: a charge's price moves the pack
     * blend from that charge forward.
     */
    fun applyDefaultChargeCostToUnpriced(): DefaultChargeCostApplication {
        val outcome = graph.sessionRepository.applyDefaultCostToUnpricedCharges()
        val from = outcome.oldestPricedStartUtcMillis
        if (outcome.ok && from != null) {
            runCatching { graph.batteryCycleRepository.invalidateFrom(from) }
                .onFailure { error -> Log.w(TAG, "Battery cycle invalidation failed", error) }
        }
        return outcome
    }

    // --- database commands ---------------------------------------------------

    fun clearTelemetryDatabase(): Map<String, Any?> {
        stop()
        return graph.jobExecutor.submit(Callable {
            graph.frameRepository.awaitPendingWrites()
            val eventCount = graph.database.telemetryEventDao().count()
            val sessionCount = graph.database.sessionDao().count()
            val intervalCount = graph.database.intervalDao().count()
            // Through the queue, not around it.
            TelemetryWriteCoordinator.executor.call("clear_database", trackAsWrite = true) {
                graph.database.clearAllTables()
            }
            resetInMemoryState()
            mapOf(
                "ok" to true,
                "telemetryEventsDeleted" to eventCount,
                "sessionsDeleted" to sessionCount,
                "samplesDeleted" to 0L,
                "telemetryFramesDeleted" to 0L,
                "intervalsDeleted" to intervalCount,
                "timestampMillis" to System.currentTimeMillis()
            )
        }).get()
    }

    fun runTelemetryRetention(): Map<String, Any?> =
        graph.jobExecutor.submit(Callable {
            graph.retentionManager.runRetention(force = true)
        }).get()

    /** How much disk the app's stored history occupies, without scanning any row. */
    fun storageUsage(): Map<String, Any?> = graph.databaseHealthReporter.storageUsage()

    fun awaitCollectionQuiescence() {
        graph.maintenanceExecutor.submit {}.get()
        graph.detectorExecutor.submit {}.get()
        graph.frameRepository.awaitPendingWrites()
    }

    /**
     * Drops every in-memory copy of what the database just lost.
     *
     * A wipe and a restore both replace the rows underneath the detectors, so
     * both have to forget the same five things. They were written out twice
     * before the split, which is exactly the kind of pair that drifts.
     */
    private fun resetInMemoryState() {
        graph.eventRepository.resetInMemory()
        graph.tripSessionDetector.reset()
        graph.chargeSessionDetector.reset()
        graph.sessionRepository.resetInMemory()
        graph.frameRepository.resetInMemory()
        TelemetryWriteCoordinator.executor.resetMetrics()
    }

    // --- settings ------------------------------------------------------------

    fun setAutoStartOnBoot(enabled: Boolean): Map<String, Any?> {
        graph.settings.setAutoStartOnBoot(enabled)
        return graph.settings.toMap()
    }

    /**
     * Persists the setting, then asks for the radio at once rather than at the
     * next adapter event, so the switch has a visible effect.
     */
    fun setKeepBluetoothOnEnabled(enabled: Boolean): Map<String, Any?> {
        graph.settings.setKeepBluetoothOnEnabled(enabled)
        // GeelyTools deviation: the BLE live-stream server (Companion-only) is
        // excluded, so the preference is persisted without the radio apply.
        return graph.settings.toMap()
    }

    fun setGpsEnabled(enabled: Boolean): Map<String, Any?> {
        graph.settings.setGpsEnabled(enabled)
        if (enabled && sequence.running) {
            graph.locationSignalProvider.start()
        } else if (!enabled) {
            graph.locationSignalProvider.stop()
        }
        return graph.settings.toMap() +
            mapOf("location" to graph.locationSignalProvider.statusMap())
    }

    fun setDebugEventFileEnabled(enabled: Boolean): Map<String, Any?> {
        graph.settings.setDebugEventFileEnabled(enabled)
        if (!enabled) {
            graph.eventRepository.deleteEventFiles()
        }
        return graph.settings.toMap()
    }

    fun setTemperatureModeHelperEnabled(enabled: Boolean): Map<String, Any?> {
        graph.settings.setTemperatureModeHelperEnabled(enabled)
        graph.temperatureModeHelperMonitor.setEnabled(enabled)
        return graph.settings.toMap() +
            mapOf("helpers" to graph.temperatureModeHelperMonitor.statusMap())
    }

    fun setReplaceOemChargingEnabled(enabled: Boolean): Map<String, Any?> {
        graph.settings.setReplaceOemChargingEnabled(enabled)
        return graph.settings.toMap()
    }

    fun setExternalChargeControlEnabled(enabled: Boolean): Map<String, Any?> {
        graph.settings.setExternalChargeControlEnabled(enabled)
        // Turning the switch off ends the link with Geely Charge Control at
        // once. Leaving it registered would keep a screen the reader has
        // hidden in step with a car they asked this app to stop steering.
        graph.chargeControlIpcClient.syncToSetting()
        graph.chargeControlStateBroadcaster?.invoke(graph.chargeControlIpcClient.getState())
        return graph.settings.toMap()
    }

    /**
     * Finding 14: flips the opt-in `CONTINUOUS` recording switch and reads it
     * back. Fully wired: [com.timhss.capyenergy.telemetry.ContinuousSessionDetector]
     * reads the stored preference, cuts one wall-clock minute per awake minute
     * through `FrameRepository`, and persists it as `CONTINUOUS` sessions and
     * `interval` rows. Off by default; with it off recording is unchanged.
     */
    fun setContinuousModeEnabled(enabled: Boolean): Map<String, Any?> {
        graph.settings.setContinuousModeEnabled(enabled)
        return graph.settings.toMap()
    }

    fun getChargeControlAppStatus(): Map<String, Any?> =
        graph.chargeControlAppManager.status().toMap()

    fun checkChargeControlAppUpdate(): Map<String, Any?> =
        graph.chargeControlAppManager.checkStatus().toMap()

    fun installChargeControlApp(onProgress: ((Float) -> Unit)? = null): Map<String, Any?> =
        graph.chargeControlAppManager.downloadAndInstall(onProgress).toMap()

    var onTargetSocChangedListener: ((Int) -> Unit)?
        get() = graph.targetSocChangeBroadcaster
        set(value) {
            graph.targetSocChangeBroadcaster = value
        }

    var onChargeControlStateChanged: ((com.timhss.capyenergy.ipc.ChargeControlIpcState) -> Unit)?
        get() = graph.chargeControlStateBroadcaster
        set(value) {
            graph.chargeControlStateBroadcaster = value
        }

    fun setChargeTargetSoc(percent: Int): Map<String, Any?> {
        graph.chargeControlIpcClient.setTargetSoc(percent)
        return graph.settings.toMap()
    }

    fun getChargeTargetSoc(): Int = graph.settings.chargeTargetSocPercent()

    fun getChargeControlState(): Map<String, Any?> {
        graph.chargeControlIpcClient.start()
        graph.chargeControlIpcClient.queryState()
        return graph.chargeControlIpcClient.getState().toMap()
    }

    fun setChargingAmperage(amps: Int): Map<String, Any?> {
        graph.chargeControlIpcClient.setAmperage(amps)
        return graph.chargeControlIpcClient.getState().toMap()
    }

    fun setForceCharging(force: Boolean): Map<String, Any?> {
        graph.chargeControlIpcClient.setForceCharging(force)
        return graph.chargeControlIpcClient.getState().toMap()
    }

    fun stopCharging(): Map<String, Any?> {
        graph.chargeControlIpcClient.stopCharging()
        return mapOf("ok" to true)
    }

    fun launchChargeControlApp(): Map<String, Any?> =
        mapOf("ok" to graph.chargeControlAppManager.launchApp())

    fun setDefaultChargeCostPerKwh(value: Double?): Map<String, Any?> {
        graph.settings.setDefaultChargeCostPerKwh(value)
        return graph.settings.toMap()
    }

    /**
     * States the pack capacity. Every energy figure the app reports is scaled
     * by it, so the cycle ledger is refolded rather than left showing totals
     * that were computed against the old pack.
     */
    fun setPackCapacityWh(value: Double?): Map<String, Any?> {
        graph.settings.setPackCapacityWh(value)
        // From zero: capacity scales every cycle, not a tail of them.
        runCatching { graph.batteryCycleRepository.invalidateFrom(0L) }
            .onFailure { error -> Log.w(TAG, "Battery cycle rebuild failed", error) }
        return graph.settings.toMap()
    }

    companion object {
        private const val TAG = "TelemetryRuntime"
        private const val COLLECTOR_TICK_SECONDS = 1L
        private const val RANGE_EFFICIENCY_REFRESH_SECONDS = 60L
        /** Finding 10: retry startup-skipped retention once a day. */
        private const val RETENTION_INTERVAL_SECONDS = 86_400L
        @Volatile
        private var instance: TelemetryRuntime? = null

        fun get(context: Context): TelemetryRuntime =
            instance ?: synchronized(this) {
                instance ?: TelemetryRuntime(context.applicationContext).also { instance = it }
            }
    }
}
