package io.ucc.app.data

import io.ucc.core.engine.CoreCapabilities
import io.ucc.core.engine.CoreStartOptions
import kotlinx.serialization.Serializable

/**
 * User-facing connection settings (Settings screen). Pure data; validated by
 * [ConnectionSettings.validate] and mapped to core options by [toStartOptions].
 *
 * Only options every supported core can honour appear here; capability-gated
 * ones (per-app routing) are hidden by the UI when the active core lacks them.
 */
@Serializable
data class ConnectionSettings(
    val remoteDns: String = DEFAULT_REMOTE_DNS,
    /** null = system resolver ("local"). */
    val directDns: String? = null,
    val bypassPrivate: Boolean = true,
    val ipv6: Boolean = true,
    /** In-tunnel leak protection (sing-box strict_route). Not a kill switch when the VPN is down. */
    val strictRoute: Boolean = true,
    val mtu: Int = 9000,
    val perAppMode: PerAppMode = PerAppMode.OFF,
    val perAppPackages: Set<String> = emptySet(),
    val logLevel: LogLevel = LogLevel.INFO,
) {
    enum class PerAppMode { OFF, INCLUDE, EXCLUDE }
    enum class LogLevel(val core: String) { ERROR("error"), WARN("warn"), INFO("info"), DEBUG("debug") }

    sealed class Problem {
        data object RemoteDnsInvalid : Problem()
        data object DirectDnsInvalid : Problem()
        data object MtuOutOfRange : Problem()
    }

    fun validate(): List<Problem> = buildList {
        if (!isValidDnsSpec(remoteDns) || remoteDns == "local") add(Problem.RemoteDnsInvalid)
        if (directDns != null && !isValidDnsSpec(directDns)) add(Problem.DirectDnsInvalid)
        if (mtu !in MTU_RANGE) add(Problem.MtuOutOfRange)
    }

    fun toStartOptions(capabilities: CoreCapabilities): CoreStartOptions {
        val perApp = capabilities.perAppRouting && perAppMode != PerAppMode.OFF && perAppPackages.isNotEmpty()
        return CoreStartOptions(
            mtu = mtu.coerceIn(MTU_RANGE),
            ipv6 = ipv6,
            strictRoute = strictRoute,
            includePackages = if (perApp && perAppMode == PerAppMode.INCLUDE) perAppPackages.sorted() else emptyList(),
            excludePackages = if (perApp && perAppMode == PerAppMode.EXCLUDE) perAppPackages.sorted() else emptyList(),
            logLevel = logLevel.core,
            remoteDns = remoteDns.takeIf { isValidDnsSpec(it) && it != "local" },
            directDns = directDns?.takeIf { isValidDnsSpec(it) },
            bypassPrivate = bypassPrivate,
        )
    }

    companion object {
        const val DEFAULT_REMOTE_DNS = "https://1.1.1.1/dns-query"
        val MTU_RANGE: IntRange = 1280..9000

        /** Presets shown as chips; anything else is typed by the user and validated. */
        val REMOTE_DNS_PRESETS: List<Pair<String, String>> = listOf(
            "Cloudflare (DoH)" to "https://1.1.1.1/dns-query",
            "Google (DoH)" to "https://dns.google/dns-query",
            "Quad9 (DoT)" to "tls://dns.quad9.net",
            "AdGuard (DoH)" to "https://dns.adguard-dns.com/dns-query",
        )

        private val SCHEMES = setOf("udp", "tcp", "tls", "quic", "https", "h3")
        private val HOST = Regex("^(\\[[0-9a-fA-F:.]+]|[A-Za-z0-9.-]+)(:\\d{1,5})?$")

        /** Same grammar the generator's `dnsServer` accepts: `local`, `<scheme>://host[:port][/path]`, or bare IP/host. */
        fun isValidDnsSpec(spec: String): Boolean {
            val s = spec.trim()
            if (s.isEmpty()) return false
            if (s == "local") return true
            val scheme = s.substringBefore("://", "")
            val rest = if (scheme.isEmpty()) s else s.substringAfter("://")
            if (scheme.isNotEmpty() && scheme !in SCHEMES) return false
            val hostPort = rest.substringBefore('/')
            if (rest.contains('/') && scheme !in setOf("https", "h3")) return false
            return HOST.matches(hostPort) && hostPort.substringAfterLast(':', "").let { it.isEmpty() || hostPort.startsWith("[") && !hostPort.endsWith("]") || it.toIntOrNull() in 1..65535 }
        }
    }
}
