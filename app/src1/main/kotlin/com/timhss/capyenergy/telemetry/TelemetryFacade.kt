package com.timhss.capyenergy.telemetry

import com.timhss.capyenergy.profile.GeelyProfile
import com.timhss.capyenergy.profile.VehicleProfile

/**
 * The read side of the telemetry engine, shaped for the method channel.
 *
 * Every function here answers one Flutter call and returns the wire map for it.
 * It was split out of `TelemetryRuntime` so the presentation surface is a
 * distinct object from the wiring ([TelemetryGraph]) and the lifecycle
 * ([TelemetryRuntime]).
 *
 * These maps are the hand-written wire format the Pigeon migration replaces.
 * Keep new keys out of the repositories and inside this file, so the eventual
 * generated DTOs have one place to take over from.
 */
internal class TelemetryFacade(
    private val graph: TelemetryGraph,
    private val profile: VehicleProfile = GeelyProfile,
    // Wall clock for the registered-claim window; injected so tests freeze it.
    private val nowMillis: () -> Long = System::currentTimeMillis,
    // Last, so the trailing-lambda call site stays readable.
    private val isRunning: () -> Boolean,
) {
    fun status(): CollectorStatus {
        val snapshot = graph.store.snapshot()
        val lastUpdate = snapshot.values.maxOfOrNull { it.timestampMillis } ?: 0L
        val running = isRunning()
        return CollectorStatus(
            running = running,
            collectorStatus = if (running) "running" else "stopped",
            vehicleActivity = graph.vehicleActivityDetector.classify(snapshot),
            tripState = TripState.valueOf(
                graph.tripSessionDetector.statusMap()["tripState"].toString()
            ),
            chargeState = ChargeSessionState.valueOf(
                graph.chargeSessionDetector.statusMap()["chargeState"].toString()
            ),
            callbackSignals = graph.vhalSubscriptionManager.callbackSignalCount(),
            pollingSignals = graph.vhalSubscriptionManager.pollingSignalCount(),
            lastUpdateMillis = lastUpdate,
            signalCount = snapshot.size
        )
    }

    /**
     * What this vehicle can do, as opposed to what it is measuring now.
     *
     * The shell reads it to decide which destinations exist. It is a profile
     * fact and never changes while the process runs, so it carries no
     * timestamp: a capability that came and went would be a fault report, and
     * this is not one.
     *
     * The names on the wire are the enum names. A side that does not know one
     * must ignore it and keep the rest, never drop the whole answer — that is
     * the 2026-08-15 range-estimate defect, where an unknown source name
     * dropped a whole estimate.
     */
    fun vehicleCapabilitiesMap(): Map<String, Any?> = mapOf(
        "profileId" to profile.id,
        "capabilities" to profile.capabilities.map { it.name }.sorted()
    )

    fun collectorStatusMap(): Map<String, Any?> = status().toMap() + mapOf(
        "persistence" to mapOf(
            "writer" to TelemetryWriteCoordinator.executor.statusMap(),
            "frames" to graph.frameRepository.statusMap(),
            "events" to graph.eventRepository.statusMap(),
            "sessions" to graph.sessionRepository.statusMap(),
            "retention" to graph.retentionManager.statusMap(),
            "database" to graph.databaseHealthReporter.statusMap()
        )
    )

    fun liveMetadataMap(): Map<String, Any?> = mapOf(
        "status" to status().toMap(),
        "trip" to graph.tripSessionDetector.statusMap(),
        "charge" to graph.chargeSessionDetector.statusMap(),
        "location" to graph.locationSignalProvider.statusMap() + mapOf(
            "gpsEnabled" to graph.settings.gpsEnabled()
        ),
        "sessions" to graph.sessionRepository.statusMap(),
        "frames" to graph.frameRepository.statusMap(),
        "events" to graph.eventRepository.statusMap(),
        "retention" to graph.retentionManager.statusMap(),
        "database" to graph.databaseHealthReporter.statusMap(),
        "persistenceWriter" to TelemetryWriteCoordinator.executor.statusMap(),
        "helpers" to graph.temperatureModeHelperMonitor.statusMap(),
        "roadcast" to roadcastStatusMap(),
        "recentEvents" to graph.eventRepository.recent(20).map { it.toMap() }
    )

    fun latestSnapshotMap(): Map<String, Any?> {
        val snapshot = graph.store.snapshot()
        return mapOf(
            "timestampMillis" to System.currentTimeMillis(),
            "signals" to snapshot.values.map { it.toMap() },
        ) + liveMetadataMap()
    }

    fun roadcastStatusMap(): Map<String, Any?> {
        val client = graph.roadcastRepository.statusMap()
        return (graph.state.roadcastStatus ?: graph.roadcastDaemon.status()).toMap() +
            mapOf(
                "signalCount" to client["signalCount"],
                "frameCount" to client["frameCount"],
                "hz" to client["hz"],
                "client" to client
            )
    }

    fun roadcastUpdateStatusMap(): Map<String, Any?> =
        graph.roadcastUpdateManager.localStatus(installedRoadcastSha256()).toMap()

    fun checkRoadcastUpdate(): Map<String, Any?> =
        graph.roadcastUpdateManager.check(installedRoadcastSha256()).toMap()

    fun installedRoadcastSha256(): String? =
        runCatching { graph.roadcastDaemon.installedSha256() }.getOrNull()

    /** Typed: the bridge spells it, against the generated class. */
    fun rangeEstimate(): NativeRangeEstimate = graph.rangeEstimateMonitor.snapshot()

    /**
     * The GNSS course over ground. Typed, and it reads no database, so it
     * answers on the platform thread.
     */
    fun heading(): VehicleHeading =
        graph.locationSignalProvider.heading(graph.settings.gpsEnabled())

    fun recentEventsMap(limit: Int): Map<String, Any?> =
        graph.eventRepository.recentMap(limit)

    // The session lists answer typed; the bridge spells them.
    fun recentChargeSessions(limit: Int): ChargeSessionPage =
        graph.sessionRepository.recentChargeSessions(limit)

    fun chargeMergeCandidates(limit: Int): ChargeMergeCandidatePage =
        graph.sessionRepository.chargeMergeCandidates(limit)

    fun recentTripSessions(limit: Int): TripSessionPage =
        graph.sessionRepository.recentTripSessions(limit)

    /**
     * Closed trips the Insights engine may compare. Session aggregates and
     * minute-bucket presence only — never frames.
     */
    fun insightTrips(subjectId: String?): InsightTripsPage =
        graph.insightRepository.trips(subjectId)

    fun insightPlaces(): List<InsightPlaceRow> = graph.insightRepository.places()

    fun saveInsightPlace(
        id: String?,
        name: String,
        latitude: Double,
        longitude: Double,
        radiusM: Double,
        autoName: String? = null,
        autoNameUpdatedAtUtcMillis: Long? = null,
        autoNameSource: String? = null,
    ): InsightPlaceRow = graph.insightRepository.savePlace(
        id = id,
        name = name,
        latitude = latitude,
        longitude = longitude,
        radiusM = radiusM,
        autoName = autoName,
        autoNameUpdatedAtUtcMillis = autoNameUpdatedAtUtcMillis,
        autoNameSource = autoNameSource,
    )

    fun deleteInsightPlace(id: String) = graph.insightRepository.deletePlace(id)

    /**
     * The battery cycles, newest first. The read folds any new sessions first,
     * so the open cycle is current.
     */
    fun batteryCycles(limit: Int): BatteryCyclePage =
        graph.batteryCycleRepository.cycles(limit)

    /**
     * What one cycle is made of, oldest session first.
     *
     * Two reads, joined here rather than in either repository: the membership
     * is the fold's own attribution and belongs to the cycles, the sessions
     * belong to the session repository, and neither may reach into the other.
     *
     * A session the retention has deleted keeps its row, marked deleted, with
     * the window the fold stored. It is still part of what the cycle counted,
     * and dropping it would make a short list look like a whole one.
     */
    fun batteryCycleSessions(ordinal: Long): BatteryCycleSessionsPage {
        val sessions = graph.batteryCycleRepository.sessions(ordinal).map { member ->
            when (member.kind) {
                "TRIP" -> graph.sessionRepository.tripSession(member.sessionId).let { trip ->
                    member.copy(trip = trip, deleted = trip == null)
                }

                "CHARGE" -> graph.sessionRepository.chargeSession(member.sessionId)
                    .let { charge -> member.copy(charge = charge, deleted = charge == null) }

                // A parked session has no list row, so whether it is still
                // there is all there is to say about it.
                else -> member.copy(
                    deleted = !graph.sessionRepository.parkedSessionExists(member.sessionId)
                )
            }
        }
        return BatteryCycleSessionsPage(ordinal = ordinal, sessions = sessions)
    }

    // The energy series answer typed, not as wire maps: they are spelled by
    // the bridge, against the generated classes.
    fun liveEnergySeries(): LiveEnergyBuckets? = graph.frameRepository.liveEnergySeries()

    fun liveEfficiencySeries(): LiveEnergyBuckets? =
        graph.frameRepository.liveEfficiencySeries()

    fun liveChargeEnergySeries(): LiveEnergyBuckets? =
        graph.frameRepository.liveChargeEnergySeries()

    fun liveContinuousEnergySeries(): LiveEnergyBuckets? =
        graph.frameRepository.liveContinuousEnergySeries()

    fun efficiencyBucketMillis(): Long = graph.frameRepository.efficiencyBucketMillis()

    fun energySeriesInWindow(
        startUtcMillis: Long,
        endUtcMillis: Long
    ): WindowEnergySeries =
        graph.frameRepository.energySeriesInWindow(startUtcMillis, endUtcMillis)

    fun parkedEnergySeriesInWindow(
        startUtcMillis: Long,
        endUtcMillis: Long
    ): WindowEnergySeries =
        graph.frameRepository.parkedEnergySeriesInWindow(startUtcMillis, endUtcMillis)

    fun settingsMap(): Map<String, Any?> = graph.settings.toMap()

    // GeelyTools deviation: Capy Companion support (phone pairing, BLE live
    // stream, Supabase cloud sync) is excluded — telemetry is strictly local.
}
