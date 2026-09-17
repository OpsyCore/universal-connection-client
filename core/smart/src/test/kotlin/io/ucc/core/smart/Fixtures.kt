package io.ucc.core.smart

import io.ucc.core.model.ConnectionProfile
import io.ucc.core.model.Protocol

internal fun profile(id: String, protocol: Protocol = Protocol.VLESS, address: String = "$id.example.net", port: Int = 443) =
    ConnectionProfile(id = id, name = "Server $id", protocol = protocol, address = address, port = port)

internal const val NOW = 1_700_000_000_000L
internal const val MIN = 60_000L

internal fun healthy(latency: Long, at: Long = NOW - MIN, successes: Int = 5, failures: Int = 0) = ServerHealth(
    latencyMs = latency, rollingLatencyMs = latency, latencySampleCount = successes,
    lastSuccessAtEpochMs = at, successCount = successes, failureCount = failures,
)

internal fun failing(streak: Int, at: Long = NOW - MIN, lastSuccess: Long? = NOW - 10 * MIN) = ServerHealth(
    latencyMs = lastSuccess?.let { 80 }, rollingLatencyMs = lastSuccess?.let { 80 },
    lastSuccessAtEpochMs = lastSuccess, lastFailureAtEpochMs = at, lastFailure = TestFailure.TIMEOUT,
    consecutiveFailures = streak, successCount = if (lastSuccess != null) 3 else 0, failureCount = streak,
)

internal fun cand(id: String, h: ServerHealth = ServerHealth.EMPTY, supported: Boolean = true, protocol: Protocol = Protocol.VLESS) =
    Candidate(profile(id, protocol), h, supported)
