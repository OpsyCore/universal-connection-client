package io.ucc.core.config.parser

import io.ucc.core.config.ConfigError
import io.ucc.core.config.IdGenerator
import io.ucc.core.config.ImportFailure
import io.ucc.core.config.ImportReport
import io.ucc.core.config.InputFormat
import io.ucc.core.config.TimeSource
import io.ucc.core.model.Authentication
import io.ucc.core.model.ConnectionProfile
import io.ucc.core.model.ProfileSource
import io.ucc.core.model.Protocol
import io.ucc.core.model.TlsSettings
import io.ucc.core.model.Transport
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Extracts proxy outbounds from a sing-box configuration document (or a bare
 * outbound object). Route/DNS sections of the document are **not** imported —
 * the app owns routing; only server definitions become profiles.
 */
public class SingBoxJsonImporter(
    private val ids: IdGenerator = IdGenerator.Random,
    private val time: TimeSource = TimeSource.System,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val proxyTypes = mapOf(
        "vless" to Protocol.VLESS, "vmess" to Protocol.VMESS, "trojan" to Protocol.TROJAN, "shadowsocks" to Protocol.SHADOWSOCKS,
        "hysteria" to Protocol.HYSTERIA, "hysteria2" to Protocol.HYSTERIA2, "tuic" to Protocol.TUIC, "wireguard" to Protocol.WIREGUARD,
        "socks" to Protocol.SOCKS, "http" to Protocol.HTTP,
    )

    public fun import(text: String, source: ProfileSource): ImportReport {
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: SerializationException) {
            return ImportReport(emptyList(), listOf(ImportFailure("{…}", ConfigError.InvalidJson(e.message ?: "invalid JSON"))), InputFormat.SING_BOX_JSON)
        } catch (e: IllegalArgumentException) {
            return ImportReport(emptyList(), listOf(ImportFailure("{…}", ConfigError.InvalidJson("document is not a JSON object"))), InputFormat.SING_BOX_JSON)
        }
        val candidates: List<JsonObject> = when {
            root["outbounds"] is JsonArray -> root["outbounds"]!!.jsonArray.mapNotNull { it as? JsonObject } +
                (root["endpoints"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
            root["type"] != null -> listOf(root)
            else -> return ImportReport(emptyList(), listOf(ImportFailure("{…}", ConfigError.MissingField("outbounds"))), InputFormat.SING_BOX_JSON)
        }
        val ok = ArrayList<ConnectionProfile>()
        val bad = ArrayList<ImportFailure>()
        val seen = HashSet<String>()
        for (ob in candidates) {
            val type = ob.str("type") ?: continue
            val protocol = proxyTypes[type] ?: continue // selector/direct/block/dns/urltest are silently skipped
            try {
                val p = toProfile(ob, protocol, source)
                if (seen.add(p.fingerprint)) ok += p
            } catch (e: ParseException) {
                bad += ImportFailure("outbound '${ob.str("tag") ?: type}'", e.error)
            }
        }
        return ImportReport(ok, bad, InputFormat.SING_BOX_JSON)
    }

    private fun toProfile(ob: JsonObject, protocol: Protocol, source: ProfileSource): ConnectionProfile {
        val firstPeer = (ob["peers"] as? JsonArray)?.firstOrNull() as? JsonObject
        val server = ob.str("server") ?: firstPeer?.str("address") ?: throw ParseException(ConfigError.MissingField("server"))
        val port = ob.int("server_port") ?: firstPeer?.int("port") ?: throw ParseException(ConfigError.MissingField("server_port"))
        val tlsObj = ob["tls"] as? JsonObject
        val tls = if (tlsObj != null && tlsObj.bool("enabled") == true) {
            val reality = (tlsObj["reality"] as? JsonObject)?.takeIf { it.bool("enabled") == true }?.let {
                TlsSettings.Reality(publicKey = it.str("public_key") ?: throw ParseException(ConfigError.MissingField("tls.reality.public_key")), shortId = it.str("short_id") ?: "")
            }
            TlsSettings(
                enabled = true,
                serverName = tlsObj.str("server_name"),
                allowInsecure = tlsObj.bool("insecure") == true,
                alpn = tlsObj.strList("alpn"),
                fingerprint = (tlsObj["utls"] as? JsonObject)?.takeIf { it.bool("enabled") == true }?.str("fingerprint"),
                reality = reality,
                certificatePem = tlsObj.strList("certificate").joinToString("\n").ifEmpty { null },
                disableSni = tlsObj.bool("disable_sni") == true,
            )
        } else TlsSettings(enabled = false)
        val transport = (ob["transport"] as? JsonObject)?.let { t ->
            when (t.str("type")) {
                "ws" -> Transport.WebSocket(
                    path = t.str("path") ?: "/", host = (t["headers"] as? JsonObject)?.str("Host"),
                    maxEarlyData = t.int("max_early_data"), earlyDataHeaderName = t.str("early_data_header_name"),
                )
                "grpc" -> Transport.Grpc(serviceName = t.str("service_name") ?: "")
                "http" -> Transport.HttpUpgradeOrH2(path = t.str("path") ?: "/", host = t.strList("host"), upgrade = false)
                "httpupgrade" -> Transport.HttpUpgradeOrH2(path = t.str("path") ?: "/", host = listOfNotNull(t.str("host")), upgrade = true)
                null -> Transport.Tcp
                else -> Transport.Unsupported(t.str("type")!!)
            }
        } ?: if (protocol in setOf(Protocol.VLESS, Protocol.VMESS, Protocol.TROJAN, Protocol.SHADOWSOCKS, Protocol.SOCKS, Protocol.HTTP)) Transport.Tcp else Transport.None

        val auth: Authentication = when (protocol) {
            Protocol.VLESS -> Authentication.Vless(uuid = ob.req("uuid"), flow = ob.str("flow")?.ifEmpty { null }, packetEncoding = ob.str("packet_encoding"))
            Protocol.VMESS -> Authentication.Vmess(uuid = ob.req("uuid"), alterId = ob.int("alter_id") ?: 0, security = ob.str("security") ?: "auto", packetEncoding = ob.str("packet_encoding"))
            Protocol.TROJAN -> Authentication.Trojan(ob.req("password"))
            Protocol.SHADOWSOCKS -> Authentication.Shadowsocks(method = ob.req("method"), password = ob.req("password"), plugin = ob.str("plugin"), pluginOptions = ob.str("plugin_opts"), udpOverTcp = ob["udp_over_tcp"].let { it is JsonPrimitive && it.booleanOrNull == true || it is JsonObject })
            Protocol.HYSTERIA -> Authentication.Hysteria(auth = ob.str("auth_str"), upMbps = ob.int("up_mbps"), downMbps = ob.int("down_mbps"), obfs = ob.str("obfs"))
            Protocol.HYSTERIA2 -> {
                val obfs = ob["obfs"] as? JsonObject
                Authentication.Hysteria2(password = ob.str("password") ?: "", obfsType = obfs?.str("type"), obfsPassword = obfs?.str("password"), upMbps = ob.int("up_mbps"), downMbps = ob.int("down_mbps"), ports = ob.strList("server_ports").joinToString(",").ifEmpty { null })
            }
            Protocol.TUIC -> Authentication.Tuic(uuid = ob.req("uuid"), password = ob.str("password") ?: "", congestionControl = ob.str("congestion_control") ?: "cubic", udpRelayMode = ob.str("udp_relay_mode") ?: "native", zeroRttHandshake = ob.bool("zero_rtt_handshake") == true)
            Protocol.WIREGUARD -> {
                val peer = (ob["peers"] as? JsonArray)?.firstOrNull() as? JsonObject
                Authentication.WireGuard(
                    privateKey = ob.req("private_key"),
                    peerPublicKey = peer?.str("public_key") ?: ob.str("peer_public_key") ?: throw ParseException(ConfigError.MissingField("peer public_key")),
                    preSharedKey = peer?.str("pre_shared_key") ?: ob.str("pre_shared_key"),
                    localAddresses = ob.strList("address").ifEmpty { ob.strList("local_address") },
                    reserved = (peer?.get("reserved") ?: ob["reserved"])?.let { r -> (r as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull } }.orEmpty(),
                    mtu = ob.int("mtu") ?: 1408,
                )
            }
            Protocol.SOCKS, Protocol.HTTP -> if (ob.str("username") == null) Authentication.None else Authentication.UserPassword(ob.str("username"), ob.str("password"))
            else -> throw ParseException(ConfigError.Unsupported(protocol.name))
        }
        val wgServer = server
        val wgPort = port
        val now = time.nowMs()
        return ConnectionProfile(
            id = ids.next(),
            name = ob.str("tag") ?: defaultName(protocol, wgServer, wgPort),
            protocol = protocol,
            address = wgServer,
            port = wgPort,
            transport = transport,
            tls = tls,
            authentication = auth,
            metadata = io.ucc.core.model.ProfileMetadata(source = source, createdAtEpochMs = now, updatedAtEpochMs = now),
        )
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.req(k: String): String = str(k)?.takeIf { it.isNotEmpty() } ?: throw ParseException(ConfigError.MissingField(k))
    private fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
    private fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.strList(k: String): List<String> = when (val v = this[k]) {
        is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        is JsonPrimitive -> listOfNotNull(v.contentOrNull).filter { it.isNotEmpty() }
        else -> emptyList()
    }
}
