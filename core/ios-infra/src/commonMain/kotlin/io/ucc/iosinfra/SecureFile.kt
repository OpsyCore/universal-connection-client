package io.ucc.iosinfra

import io.ucc.core.platform.currentTimeMillis

/**
 * Codec-wrapped document with the same contract as the Android `SecureFile`:
 * `name.enc` is the live file; reads that fail to decode are moved aside as
 * `name.enc.corrupt-<ts>` (never silently deleted) and reported. There is no
 * plaintext-migration branch here because no plaintext generation ever shipped on iOS.
 */
public class SecureFile(private val store: BlobStore, name: String, private val codec: FileCodec) {
    private val live = "$name.enc"

    public sealed class ReadResult {
        public data object Missing : ReadResult()
        public class Ok(public val bytes: ByteArray) : ReadResult()
        /** Live file exists but cannot be decoded (key changed / tampered / truncated). Moved to [quarantinedName]. */
        public data class Unreadable(public val quarantinedName: String) : ReadResult()
    }

    public fun exists(): Boolean = store.exists(live)

    /** @throws BlobIoException */
    public fun read(): ReadResult {
        if (!store.exists(live)) return ReadResult.Missing
        val stored = store.read(live)
        return try {
            ReadResult.Ok(codec.decode(stored))
        } catch (e: FileCodec.CorruptOrForeign) {
            val q = "$live.corrupt-${currentTimeMillis()}"
            if (!store.rename(live, q)) store.delete(live)
            ReadResult.Unreadable(q)
        }
    }

    /** @throws BlobIoException */
    public fun write(plain: ByteArray): Unit = store.writeAtomically(live, codec.encode(plain))
}
