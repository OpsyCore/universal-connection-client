package io.ucc.core.singbox

import io.ucc.core.engine.ConnectionError
import io.ucc.core.engine.CoreException
import io.ucc.core.engine.CoreStartOptions
import io.ucc.core.engine.RouteAction
import io.ucc.core.engine.RoutingRule
import io.ucc.core.model.Authentication
import io.ucc.core.model.ConnectionProfile
import io.ucc.core.model.Protocol
import io.ucc.core.model.TlsSettings
import io.ucc.core.model.Transport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Turns a [ConnectionProfile] + [CoreStartOptions] into a complete sing-box
 * configuration document (schema of the pinned tag, see [SingBoxCapabilities]).
 *
 * Design rules:
 *  - Deterministic output (same input → same JSON) so tests can snapshot it.
 *  - The proxy outbound is always tagged [PROXY_TAG]; `direct` is always present.
 *  - Routing/DNS documents contributed by later phases are merged verbatim via
 *    [CoreStartOptions.routingConfig] / [CoreStartOptions.dnsConfig]; when absent a
 *    safe default (all traffic → proxy, private IPs → direct, DNS hijacked) is used.
 *  - `coreSpecificOptions["singbox.outbound.<key>"]` are merged last into the outbound.
 */
public class SingBoxConfigGenerator(
    private val json: Json = Json { prettyPrint = false },
) {
    public companion object {
        public const val PROXY_TAG: String = "proxy"
        public const val DIRECT_TAG: String = "direct"
        public const val TUN_TAG: String = "tun-in"
        public const val DNS_REMOTE_TAG: String = "dns-remote"
        public const val DNS_DIRECT_TAG: String = "dns-direct"
        public const val DNS_FAKEIP_TAG: String = "dns-fakeip"
        public const val LAN_PROXY_TAG: String = "lan-in"
        /** Same reserved ranges sing-box documents for FakeIP (RFC 2544 benchmark block / ULA slice). */
        public const val FAKEIP_INET4_RANGE: String = "198.18.0.0/15"
        public const val FAKEIP_INET6_RANGE: String = "fc00::/18"
        /** mDNS / common LAN / special-use suffixes (RFC 6762, RFC 8375, RFC 6761) kept on the real resolver under FakeIP. */
        public val FAKEIP_EXCLUDED_SUFFIXES: List<String> = listOf(".local", ".lan", ".home", ".home.arpa", ".internal", ".localhost", ".localdomain")
        public const val DEFAULT_REMOTE_DNS: String = "https://1.1.1.1/dns-query"
        public const val DEFAULT_DIRECT_DNS: String = "local"
        private const val OUTBOUND_OVERRIDE_PREFIX = "singbox.outbound."
        /** sing-box default is also 500ms; stated explicitly so the generated config is self-describing. */
        public const val TLS_FRAGMENT_FALLBACK_DELAY: String = "500ms"
        private val FRAGMENTABLE_PROTOCOLS = setOf(Protocol.VLESS, Protocol.VMESS, Protocol.TROJAN, Protocol.HTTP)
    }

    public fun generate(profile: ConnectionProfile, options: CoreStartOptions): String {
        SingBoxCapabilities.unsupportedReason(profile)?.let {
            throw CoreException(ConnectionError.UnsupportedProtocol(it))
        }
        return json.encodeToString(JsonObject.serializer(), generateDocument(profile, options))
    }

    public fun generateDocument(profile: ConnectionProfile, options: CoreStartOptions): JsonObject {
        val outboundOrEndpoint = buildOutbound(profile, options)
        val isEndpoint = profile.protocol == Protocol.WIREGUARD
        return buildJsonObject {
            putJsonObject("log") {
                put("level", options.logLevel)
                put("timestamp", true)
            }
            put("dns", options.dnsConfig?.let(::parseObject) ?: defaultDns(profile, options))
            putJsonArray("inbounds") {
                add(tunInbound(options))
                if (options.lanProxy) add(lanProxyInbound(options))
            }
            putJsonArray("outbounds") {
                if (!isEndpoint) add(outboundOrEndpoint)
                add(buildJsonObject { put("type", "direct"); put("tag", DIRECT_TAG) })
            }
            if (isEndpoint) putJsonArray("endpoints") { add(outboundOrEndpoint) }
            put("route", options.routingConfig?.let(::parseObject) ?: defaultRoute(options))
            putJsonObject("experimental") {
                putJsonObject("cache_file") { put("enabled", true) }
            }
        }
    }

    // ------------------------------------------------------------------ pieces

    private fun tunInbound(options: CoreStartOptions): JsonObject = buildJsonObject {
        put("type", "tun")
        put("tag", TUN_TAG)
        putJsonArray("address") {
            add(JsonPrimitive("172.19.0.1/30"))
            if (options.ipv6) add(JsonPrimitive("fdfe:dcba:9876::1/126"))
        }
        put("mtu", options.mtu)
        put("auto_route", true)
        put("strict_route", options.strictRoute)
        put("stack", "mixed")
        if (options.includePackages.isNotEmpty()) {
            putJsonArray("include_package") { options.includePackages.forEach { add(JsonPrimitive(it)) } }
        }
        if (options.excludePackages.isNotEmpty()) {
            putJsonArray("exclude_package") { options.excludePackages.forEach { add(JsonPrimitive(it)) } }
        }
    }

    /**
     * "Allow LAN": a SOCKS5+HTTP (`mixed`) listener on every interface so other devices on the same
     * network can use this phone as a proxy. Deliberately unauthenticated and off by default; the UI
     * carries the warning. Traffic entering here follows the same route rules as TUN traffic.
     */
    private fun lanProxyInbound(options: CoreStartOptions): JsonObject = buildJsonObject {
        put("type", "mixed")
        put("tag", LAN_PROXY_TAG)
        put("listen", "0.0.0.0")
        put("listen_port", options.lanProxyPort.coerceIn(CoreStartOptions.LAN_PROXY_PORT_RANGE))
    }

    private fun defaultDns(profile: ConnectionProfile, options: CoreStartOptions): JsonObject = buildJsonObject {
        // Precedence: per-profile override → global setting → default (profile overrides are explicit user intent).
        val remote = profile.dns.remoteDns ?: options.remoteDns ?: DEFAULT_REMOTE_DNS
        val direct = options.directDns ?: DEFAULT_DIRECT_DNS
        putJsonArray("servers") {
            add(dnsServer(DNS_REMOTE_TAG, remote, detour = PROXY_TAG))
            add(dnsServer(DNS_DIRECT_TAG, direct, detour = null))
            if (options.fakeDns) add(buildJsonObject {
                put("type", "fakeip")
                put("tag", DNS_FAKEIP_TAG)
                put("inet4_range", FAKEIP_INET4_RANGE)
                // IPv6 off → no inet6 range at all, so FakeIP can never hand out an AAAA answer
                // (the tun has no inet6 address and the global strategy is ipv4_only).
                if (options.ipv6) put("inet6_range", FAKEIP_INET6_RANGE)
            })
        }
        putJsonArray("rules") {
            // Resolve the proxy server's own hostname directly so DNS is not a chicken-and-egg problem.
            add(buildJsonObject {
                putJsonArray("domain") { add(JsonPrimitive(profile.address)) }
                put("server", DNS_DIRECT_TAG)
            })
            if (options.fakeDns) {
                // Local-network names must never receive a fake address: they would be routed to the proxy
                // (fake IPs are not private) and become unreachable. Matched pre-resolution by suffix.
                if (options.bypassPrivate) add(buildJsonObject {
                    putJsonArray("domain_suffix") { FAKEIP_EXCLUDED_SUFFIXES.forEach { add(JsonPrimitive(it)) } }
                    put("server", DNS_DIRECT_TAG)
                })
                add(buildJsonObject {
                    putJsonArray("query_type") { add(JsonPrimitive("A")); if (options.ipv6) add(JsonPrimitive("AAAA")) }
                    put("server", DNS_FAKEIP_TAG)
                })
            }
        }
        put("final", DNS_REMOTE_TAG)
        // IPv6 off (Settings) → resolve A records only, so no AAAA answer can be chosen while the TUN has no inet6 address.
        put("strategy", if (options.ipv6) "prefer_ipv4" else "ipv4_only")
        put("independent_cache", true)
    }

    /**
     * Encodes a DNS server in the 1.12+ typed format. Accepts `local`, `udp://ip`,
     * `tcp://ip`, `https://host/path`, `tls://host`, `quic://host`, `h3://host`, or a bare IP (→ udp).
     */
    public fun dnsServer(tag: String, spec: String, detour: String?): JsonObject = buildJsonObject {
        put("tag", tag)
        val (type, hostPortPath) = when {
            spec == "local" -> "local" to null
            spec.startsWith("udp://") -> "udp" to spec.removePrefix("udp://")
            spec.startsWith("tcp://") -> "tcp" to spec.removePrefix("tcp://")
            spec.startsWith("tls://") -> "tls" to spec.removePrefix("tls://")
            spec.startsWith("quic://") -> "quic" to spec.removePrefix("quic://")
            spec.startsWith("https://") -> "https" to spec.removePrefix("https://")
            spec.startsWith("h3://") -> "h3" to spec.removePrefix("h3://")
            else -> "udp" to spec
        }
        put("type", type)
        if (hostPortPath != null) {
            val path = hostPortPath.substringAfter('/', "").let { if (it.isEmpty()) null else "/$it" }
            val hostPort = hostPortPath.substringBefore('/')
            val (host, port) = splitHostPort(hostPort)
            put("server", host)
            if (port != null) put("server_port", port)
            if (path != null && (type == "https" || type == "h3")) put("path", path)
        }
        if (detour != null) put("detour", detour)
    }

    private fun defaultRoute(options: CoreStartOptions): JsonObject = buildJsonObject {
        putJsonArray("rules") {
            add(buildJsonObject { put("action", "sniff") })
            add(buildJsonObject { put("protocol", "dns"); put("action", "hijack-dns") })
            // Block QUIC: sniffed QUIC (HTTP/3, UDP/443) is rejected so apps retry over TCP. No separate udp/443 rule.
            if (options.blockQuic) add(buildJsonObject { put("protocol", "quic"); put("action", "reject") })
            if (options.bypassPrivate) add(buildJsonObject { put("ip_is_private", true); put("outbound", DIRECT_TAG) })
            options.rules.filter { !it.isEmpty }.forEach { rule ->
                // domain matchers may share one rule (OR); ip_cidr must be separate or it would be AND-ed with domains.
                if (rule.domains.isNotEmpty() || rule.domainSuffixes.isNotEmpty() || rule.domainKeywords.isNotEmpty()) {
                    add(buildJsonObject {
                        if (rule.domains.isNotEmpty()) putJsonArray("domain") { rule.domains.forEach { add(JsonPrimitive(it)) } }
                        if (rule.domainSuffixes.isNotEmpty()) putJsonArray("domain_suffix") { rule.domainSuffixes.forEach { add(JsonPrimitive(it)) } }
                        if (rule.domainKeywords.isNotEmpty()) putJsonArray("domain_keyword") { rule.domainKeywords.forEach { add(JsonPrimitive(it)) } }
                        putAction(rule.action)
                    })
                }
                if (rule.ipCidrs.isNotEmpty()) {
                    add(buildJsonObject {
                        putJsonArray("ip_cidr") { rule.ipCidrs.forEach { add(JsonPrimitive(it)) } }
                        putAction(rule.action)
                    })
                }
            }
        }
        put("final", PROXY_TAG)
        put("auto_detect_interface", true)
        put("default_domain_resolver", DNS_DIRECT_TAG)
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putAction(action: RouteAction) {
        when (action) {
            RouteAction.DIRECT -> put("outbound", DIRECT_TAG)
            RouteAction.PROXY -> put("outbound", PROXY_TAG)
            RouteAction.BLOCK -> put("action", "reject")
        }
    }

    public fun buildOutbound(profile: ConnectionProfile): JsonObject = buildOutbound(profile, CoreStartOptions())

    public fun buildOutbound(profile: ConnectionProfile, options: CoreStartOptions): JsonObject {
        val base = when (profile.protocol) {
            Protocol.VLESS -> vless(profile)
            Protocol.VMESS -> vmess(profile)
            Protocol.TROJAN -> trojan(profile)
            Protocol.SHADOWSOCKS -> shadowsocks(profile)
            Protocol.HYSTERIA -> hysteria(profile)
            Protocol.HYSTERIA2 -> hysteria2(profile)
            Protocol.TUIC -> tuic(profile)
            Protocol.WIREGUARD -> wireguardEndpoint(profile)
            Protocol.SOCKS -> socks(profile)
            Protocol.HTTP -> http(profile)
        }
        val overrides = profile.coreSpecificOptions
            .filterKeys { it.startsWith(OUTBOUND_OVERRIDE_PREFIX) }
            .map { (k, v) -> k.removePrefix(OUTBOUND_OVERRIDE_PREFIX) to parseLoose(v) }
        val withFragment = if (options.tlsFragment) applyTlsFragment(base, profile) else base
        if (overrides.isEmpty()) return withFragment
        return JsonObject(withFragment.toMutableMap().apply { overrides.forEach { (k, v) -> put(k, v) } })
    }

    /**
     * sing-box ≥1.12 TLS fragmentation (`fragment`, `record_fragment`, `fragment_fallback_delay`).
     * Only meaningful for a TCP TLS handshake, so it is applied to vless/vmess/trojan/http outbounds
     * that actually carry a `tls` object; QUIC protocols (hysteria/hysteria2/tuic), plaintext
     * outbounds, WireGuard and REALITY are left untouched (REALITY's ClientHello must stay intact).
     */
    private fun applyTlsFragment(outbound: JsonObject, profile: ConnectionProfile): JsonObject {
        if (profile.protocol !in FRAGMENTABLE_PROTOCOLS) return outbound
        val tls = outbound["tls"] as? JsonObject ?: return outbound
        if (tls["enabled"]?.jsonPrimitive?.booleanOrNull != true) return outbound
        if ((tls["reality"] as? JsonObject)?.get("enabled")?.jsonPrimitive?.booleanOrNull == true) return outbound
        val fragmented = JsonObject(tls.toMutableMap().apply {
            put("fragment", JsonPrimitive(true))
            put("record_fragment", JsonPrimitive(true))
            put("fragment_fallback_delay", JsonPrimitive(TLS_FRAGMENT_FALLBACK_DELAY))
        })
        return JsonObject(outbound.toMutableMap().apply { put("tls", fragmented) })
    }

    private fun common(profile: ConnectionProfile, type: String, block: MutableMap<String, JsonElement>.() -> Unit): JsonObject {
        val m = linkedMapOf<String, JsonElement>()
        m["type"] = JsonPrimitive(type)
        m["tag"] = JsonPrimitive(PROXY_TAG)
        m["server"] = JsonPrimitive(profile.address)
        m["server_port"] = JsonPrimitive(profile.port)
        m.block()
        return JsonObject(m)
    }

    private fun vless(p: ConnectionProfile) = common(p, "vless") {
        val a = p.authentication as? Authentication.Vless ?: throw invalid("vless requires Vless credentials")
        put("uuid", JsonPrimitive(a.uuid))
        a.flow?.takeIf { it.isNotEmpty() }?.let { put("flow", JsonPrimitive(it)) }
        a.packetEncoding?.let { put("packet_encoding", JsonPrimitive(it)) }
        tls(p.tls)?.let { put("tls", it) }
        transport(p.transport)?.let { put("transport", it) }
    }

    private fun vmess(p: ConnectionProfile) = common(p, "vmess") {
        val a = p.authentication as? Authentication.Vmess ?: throw invalid("vmess requires Vmess credentials")
        put("uuid", JsonPrimitive(a.uuid))
        put("security", JsonPrimitive(a.security))
        put("alter_id", JsonPrimitive(a.alterId))
        a.packetEncoding?.let { put("packet_encoding", JsonPrimitive(it)) }
        tls(p.tls)?.let { put("tls", it) }
        transport(p.transport)?.let { put("transport", it) }
    }

    private fun trojan(p: ConnectionProfile) = common(p, "trojan") {
        val a = p.authentication as? Authentication.Trojan ?: throw invalid("trojan requires a password")
        put("password", JsonPrimitive(a.password))
        // Trojan is TLS by definition; honour explicit disable only if the link said so.
        tls(if (p.tls.enabled) p.tls else p.tls.copy(enabled = true))?.let { put("tls", it) }
        transport(p.transport)?.let { put("transport", it) }
    }

    private fun shadowsocks(p: ConnectionProfile) = common(p, "shadowsocks") {
        val a = p.authentication as? Authentication.Shadowsocks ?: throw invalid("shadowsocks requires method+password")
        put("method", JsonPrimitive(a.method))
        put("password", JsonPrimitive(a.password))
        a.plugin?.let { put("plugin", JsonPrimitive(it)) }
        a.pluginOptions?.let { put("plugin_opts", JsonPrimitive(it)) }
        if (a.udpOverTcp) put("udp_over_tcp", JsonPrimitive(true))
    }

    private fun hysteria(p: ConnectionProfile) = common(p, "hysteria") {
        val a = p.authentication as? Authentication.Hysteria ?: throw invalid("hysteria requires credentials")
        a.upMbps?.let { put("up_mbps", JsonPrimitive(it)) }
        a.downMbps?.let { put("down_mbps", JsonPrimitive(it)) }
        a.obfs?.let { put("obfs", JsonPrimitive(it)) }
        a.auth?.let { put("auth_str", JsonPrimitive(it)) }
        put("tls", tls(p.tls.copy(enabled = true, alpn = p.tls.alpn.ifEmpty { listOf("h3") }))!!)
    }

    private fun hysteria2(p: ConnectionProfile) = common(p, "hysteria2") {
        val a = p.authentication as? Authentication.Hysteria2 ?: throw invalid("hysteria2 requires a password")
        put("password", JsonPrimitive(a.password))
        a.upMbps?.let { put("up_mbps", JsonPrimitive(it)) }
        a.downMbps?.let { put("down_mbps", JsonPrimitive(it)) }
        if (!a.obfsType.isNullOrEmpty()) {
            put("obfs", buildJsonObject {
                put("type", a.obfsType)
                a.obfsPassword?.let { put("password", it) }
            })
        }
        a.ports?.let { ports ->
            // "20000-30000,40000-45000" → ["20000:30000", "40000:45000"]
            val ranges = ports.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                .map { r -> if (r.contains('-')) r.replace('-', ':') else "$r:$r" }
            if (ranges.isNotEmpty()) put("server_ports", JsonArray(ranges.map(::JsonPrimitive)))
        }
        put("tls", tls(p.tls.copy(enabled = true, alpn = p.tls.alpn.ifEmpty { listOf("h3") }))!!)
    }

    private fun tuic(p: ConnectionProfile) = common(p, "tuic") {
        val a = p.authentication as? Authentication.Tuic ?: throw invalid("tuic requires uuid+password")
        put("uuid", JsonPrimitive(a.uuid))
        put("password", JsonPrimitive(a.password))
        put("congestion_control", JsonPrimitive(a.congestionControl))
        put("udp_relay_mode", JsonPrimitive(a.udpRelayMode))
        if (a.zeroRttHandshake) put("zero_rtt_handshake", JsonPrimitive(true))
        a.heartbeatMs?.let { put("heartbeat", JsonPrimitive("${it}ms")) }
        put("tls", tls(p.tls.copy(enabled = true, alpn = p.tls.alpn.ifEmpty { listOf("h3") }))!!)
    }

    private fun wireguardEndpoint(p: ConnectionProfile): JsonObject {
        val a = p.authentication as? Authentication.WireGuard ?: throw invalid("wireguard requires keys and addresses")
        if (a.localAddresses.isEmpty()) throw invalid("wireguard requires at least one local address")
        return buildJsonObject {
            put("type", "wireguard")
            put("tag", PROXY_TAG)
            putJsonArray("address") { a.localAddresses.forEach { add(JsonPrimitive(it)) } }
            put("private_key", a.privateKey)
            put("mtu", a.mtu)
            putJsonArray("peers") {
                add(buildJsonObject {
                    put("address", p.address)
                    put("port", p.port)
                    put("public_key", a.peerPublicKey)
                    a.preSharedKey?.let { put("pre_shared_key", it) }
                    putJsonArray("allowed_ips") { add(JsonPrimitive("0.0.0.0/0")); add(JsonPrimitive("::/0")) }
                    if (a.reserved.isNotEmpty()) putJsonArray("reserved") { a.reserved.forEach { add(JsonPrimitive(it)) } }
                })
            }
        }
    }

    private fun socks(p: ConnectionProfile) = common(p, "socks") {
        put("version", JsonPrimitive("5"))
        (p.authentication as? Authentication.UserPassword)?.let { a ->
            a.username?.let { put("username", JsonPrimitive(it)) }
            a.password?.let { put("password", JsonPrimitive(it)) }
        }
    }

    private fun http(p: ConnectionProfile) = common(p, "http") {
        (p.authentication as? Authentication.UserPassword)?.let { a ->
            a.username?.let { put("username", JsonPrimitive(it)) }
            a.password?.let { put("password", JsonPrimitive(it)) }
        }
        tls(p.tls)?.let { put("tls", it) }
    }

    public fun tls(t: TlsSettings): JsonObject? {
        if (!t.enabled) return null
        return buildJsonObject {
            put("enabled", true)
            if (t.disableSni) put("disable_sni", true)
            t.serverName?.takeIf { it.isNotEmpty() }?.let { put("server_name", it) }
            if (t.allowInsecure) put("insecure", true)
            if (t.alpn.isNotEmpty()) putJsonArray("alpn") { t.alpn.forEach { add(JsonPrimitive(it)) } }
            t.certificatePem?.let { put("certificate", it) }
            val fp = t.fingerprint?.takeIf { it.isNotEmpty() }
            if (fp != null || t.reality != null) {
                putJsonObject("utls") {
                    put("enabled", true)
                    put("fingerprint", fp ?: "chrome")
                }
            }
            t.reality?.let { r ->
                putJsonObject("reality") {
                    put("enabled", true)
                    put("public_key", r.publicKey)
                    put("short_id", r.shortId)
                }
            }
        }
    }

    public fun transport(t: Transport): JsonObject? = when (t) {
        Transport.Tcp, Transport.None -> null
        is Transport.WebSocket -> buildJsonObject {
            put("type", "ws")
            put("path", t.path.ifEmpty { "/" })
            val headers = t.headers.toMutableMap()
            t.host?.takeIf { it.isNotEmpty() }?.let { headers["Host"] = it }
            if (headers.isNotEmpty()) putJsonObject("headers") { headers.forEach { (k, v) -> put(k, v) } }
            t.maxEarlyData?.let { put("max_early_data", it) }
            t.earlyDataHeaderName?.let { put("early_data_header_name", it) }
        }
        is Transport.Grpc -> buildJsonObject {
            put("type", "grpc")
            put("service_name", t.serviceName)
        }
        is Transport.HttpUpgradeOrH2 -> buildJsonObject {
            if (t.upgrade) {
                put("type", "httpupgrade")
                t.host.firstOrNull()?.let { put("host", it) }
            } else {
                put("type", "http")
                if (t.host.isNotEmpty()) putJsonArray("host") { t.host.forEach { add(JsonPrimitive(it)) } }
            }
            put("path", t.path.ifEmpty { "/" })
            if (t.headers.isNotEmpty()) putJsonObject("headers") { t.headers.forEach { (k, v) -> put(k, v) } }
        }
        is Transport.Unsupported -> throw CoreException(ConnectionError.UnsupportedProtocol("transport ${t.name}"))
    }

    // ---------------------------------------------------------------- helpers

    private fun invalid(detail: String) = CoreException(ConnectionError.InvalidConfiguration(detail))

    private fun parseObject(text: String): JsonObject = try {
        json.parseToJsonElement(text).jsonObject
    } catch (e: Exception) {
        throw CoreException(ConnectionError.InvalidConfiguration("embedded JSON fragment is not an object: ${e.message}"), e)
    }

    /** Parses a core-specific override value: JSON if it looks like JSON, else a string. */
    private fun parseLoose(v: String): JsonElement = try {
        val t = v.trim()
        if (t.startsWith("{") || t.startsWith("[") || t == "true" || t == "false" || t.toLongOrNull() != null) {
            json.parseToJsonElement(t)
        } else {
            JsonPrimitive(v)
        }
    } catch (e: Exception) {
        JsonPrimitive(v)
    }

    private fun splitHostPort(hp: String): Pair<String, Int?> {
        if (hp.startsWith("[")) {
            val end = hp.indexOf(']')
            if (end > 0) {
                val host = hp.substring(1, end)
                val port = hp.substring(end + 1).removePrefix(":").toIntOrNull()
                return host to port
            }
        }
        val idx = hp.lastIndexOf(':')
        if (idx > 0 && hp.indexOf(':') == idx) {
            return hp.substring(0, idx) to hp.substring(idx + 1).toIntOrNull()
        }
        return hp to null
    }
}
