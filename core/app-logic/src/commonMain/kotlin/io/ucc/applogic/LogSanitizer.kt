package io.ucc.applogic

/**
 * Last line of defence before a log line is shown, copied or shared. Producers
 * (core adapter, manager) already redact; this catches anything they missed:
 * UUIDs, key=value credentials, URL userinfo, query-string tokens, WireGuard /
 * base64 key blobs, and share links that embed secrets.
 */
object LogSanitizer {
    private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val kv = Regex("(?i)\\b(password|passwd|pass|pwd|uuid|user_id|private_key|privatekey|pre_shared_key|preshared_key|psk|auth_str|auth|token|secret|key|obfs_password|obfs-password)(\"?\\s*[:=]\\s*\"?)([^\\s\",;}&]+)")
    private val urlUserInfo = Regex("(?i)([a-z][a-z0-9+.-]*://)([^/\\s@]+)@")
    private val query = Regex("(?i)([?&](?:password|pass|pwd|token|secret|key|auth|psk|privateKey|presharedKey|publicKey|sid|pbk)=)([^&\\s#]+)")
    private val shareLink = Regex("(?i)\\b(vmess|ss|ssr|trojan-go)://[A-Za-z0-9+/=_-]{16,}")
    private val base64Key = Regex("\\b[A-Za-z0-9+/]{42,}={0,2}\\b")

    fun sanitize(text: String): String = text
        .replace(urlUserInfo, "$1***@")
        .replace(query, "$1***")
        .replace(shareLink, "$1://***")
        .replace(kv, "$1$2***")
        .replace(uuid, "<uuid>")
        .replace(base64Key, "<key>")
}
