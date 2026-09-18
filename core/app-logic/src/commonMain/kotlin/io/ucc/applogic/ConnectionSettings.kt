package io.ucc.applogic

import io.ucc.core.engine.CoreCapabilities
import io.ucc.core.engine.CoreStartOptions
import io.ucc.core.engine.RouteAction
import io.ucc.core.engine.RoutingRule
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
    /** Ordered user routing rules; first match wins in the core. */
    val rules: List<Rule> = emptyList(),
) {
    enum class PerAppMode { OFF, INCLUDE, EXCLUDE }

    /**
     * One user rule: a list of match items and an action. Items are classified
     * by [Rule.classify]: `1.2.3.0/24` / bare IP → CIDR, `*.x.y` or `.x.y` → suffix,
     * `keyword:foo` → keyword, otherwise exact domain.
     */
    @Serializable
    data class Rule(val id: String, val action: RouteAction, val items: List<String>, val enabled: Boolean = true) {
        fun toRoutingRule(): RoutingRule {
            val d = ArrayList<String>(); val sfx = ArrayList<String>(); val kw = ArrayList<String>(); val cidr = ArrayList<String>()
            for (raw in items) when (val c = classify(raw)) {
                is Item.Cidr -> cidr += c.value
                is Item.Suffix -> sfx += c.value
                is Item.Keyword -> kw += c.value
                is Item.Domain -> d += c.value
                Item.Invalid -> Unit
            }
            return RoutingRule(action, domains = d, domainSuffixes = sfx, domainKeywords = kw, ipCidrs = cidr)
        }

        sealed class Item {
            data class Cidr(val value: String) : Item()
            data class Suffix(val value: String) : Item()
            data class Keyword(val value: String) : Item()
            data class Domain(val value: String) : Item()
            data object Invalid : Item()
        }

        companion object {
            private val IPV4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})(/(\\d|[12]\\d|3[0-2]))?$")
            private val IPV6 = Regex("^[0-9a-fA-F:]+(/(\\d|[1-9]\\d|1[01]\\d|12[0-8]))?$")
            private val DOMAIN = Regex("^(?=.{1,253}$)([a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)*[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?$")

            fun classify(raw: String): Item {
                val s = raw.trim().lowercase()
                if (s.isEmpty()) return Item.Invalid
                if (s.startsWith("keyword:")) return s.removePrefix("keyword:").trim().let { if (it.isNotEmpty() && ' ' !in it) Item.Keyword(it) else Item.Invalid }
                IPV4.matchEntire(s)?.let { m ->
                    if ((1..4).all { m.groupValues[it].toInt() <= 255 }) return Item.Cidr(if ('/' in s) s else "$s/32") else return Item.Invalid
                }
                if (':' in s) return if (IPV6.matches(s)) Item.Cidr(if ('/' in s) s else "$s/128") else Item.Invalid
                val suffix = when {
                    s.startsWith("*.") -> s.removePrefix("*")
                    s.startsWith(".") -> s
                    else -> null
                }
                if (suffix != null) return if (DOMAIN.matches(suffix.removePrefix("."))) Item.Suffix(suffix) else Item.Invalid
                return if (DOMAIN.matches(s)) Item.Domain(s) else Item.Invalid
            }
        }
    }
    enum class LogLevel(val core: String) { ERROR("error"), WARN("warn"), INFO("info"), DEBUG("debug") }

    sealed class Problem {
        data object RemoteDnsInvalid : Problem()
        data object DirectDnsInvalid : Problem()
        data object MtuOutOfRange : Problem()
        /** Remote DNS is a plain IP/UDP inside a private range → would go through the proxy and fail. */
        data object RemoteDnsPrivate : Problem()
        /** Remote DNS equals direct DNS: bypass rules and tunnel share one resolver, defeating the split. */
        data object RemoteEqualsDirect : Problem()
        data class RuleItemInvalid(val ruleId: String, val item: String) : Problem()
        data class RuleEmpty(val ruleId: String) : Problem()
    }

    fun validate(): List<Problem> = buildList {
        if (!isValidDnsSpec(remoteDns) || remoteDns == "local") add(Problem.RemoteDnsInvalid)
        else if (isPrivateUdpDns(remoteDns)) add(Problem.RemoteDnsPrivate)
        if (directDns != null && !isValidDnsSpec(directDns)) add(Problem.DirectDnsInvalid)
        if (directDns != null && directDns.trim() == remoteDns.trim()) add(Problem.RemoteEqualsDirect)
        if (mtu !in MTU_RANGE) add(Problem.MtuOutOfRange)
        for (r in rules) {
            if (r.items.isEmpty()) add(Problem.RuleEmpty(r.id))
            r.items.filter { Rule.classify(it) == Rule.Item.Invalid }.forEach { add(Problem.RuleItemInvalid(r.id, it)) }
        }
    }

    /** Errors that make the settings unusable (as opposed to warnings the user may accept). */
    val blockingProblems: List<Problem>
        get() = validate().filter { it !is Problem.RemoteEqualsDirect }

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
            rules = rules.filter { it.enabled }.map { it.toRoutingRule() }.filter { !it.isEmpty },
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

        private val PRIVATE_V4 = Regex("^(10\\.|127\\.|192\\.168\\.|172\\.(1[6-9]|2\\d|3[01])\\.|169\\.254\\.)")

        /** A remote (through-tunnel) resolver on a LAN address can never be reached via the proxy. */
        fun isPrivateUdpDns(spec: String): Boolean {
            val s = spec.trim()
            val scheme = s.substringBefore("://", "")
            if (scheme.isNotEmpty() && scheme != "udp" && scheme != "tcp") return false
            val host = (if (scheme.isEmpty()) s else s.substringAfter("://")).substringBefore(':').substringBefore('/')
            return PRIVATE_V4.containsMatchIn(host)
        }

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
