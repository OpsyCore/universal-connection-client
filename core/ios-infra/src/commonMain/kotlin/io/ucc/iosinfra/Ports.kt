package io.ucc.iosinfra

/** Encodes bytes before they hit disk and decodes them on the way back (mirror of the Android `FileCodec`). */
public interface FileCodec {
    public fun encode(plain: ByteArray): ByteArray

    /** @throws CorruptOrForeign when the bytes are not something this codec wrote (or the key changed). */
    public fun decode(stored: ByteArray): ByteArray

    public class CorruptOrForeign(message: String, cause: Throwable? = null) : Exception(message, cause)
}

/** Identity codec — tests only, never wired in production. */
public object PlainCodec : FileCodec {
    override fun encode(plain: ByteArray): ByteArray = plain
    override fun decode(stored: ByteArray): ByteArray = stored
}

/** Thrown by [BlobStore] on I/O failure (the iOS counterpart of `java.io.IOException`). */
public class BlobIoException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Minimal file-system port for one directory: atomic replace semantics are the
 * implementation's responsibility ([writeAtomically] must never leave a
 * half-written live file). iOS: `NSFileManager` in the app-group/Documents dir.
 */
public interface BlobStore {
    public fun exists(name: String): Boolean
    /** @throws BlobIoException */
    public fun read(name: String): ByteArray
    /** @throws BlobIoException */
    public fun writeAtomically(name: String, bytes: ByteArray)
    /** Best effort; returns false if nothing was renamed. */
    public fun rename(from: String, to: String): Boolean
    public fun delete(name: String)
}

/** Synchronous small key/value port (iOS: `NSUserDefaults`). Values are never secrets. */
public interface KeyValueStore {
    public fun getString(key: String): String?
    public fun putString(key: String, value: String?)
    public fun getBoolean(key: String, default: Boolean): Boolean
    public fun putBoolean(key: String, value: Boolean)
}

/** In-memory ports for tests and previews. */
public class InMemoryBlobStore : BlobStore {
    public val files: MutableMap<String, ByteArray> = LinkedHashMap()
    public var failNextWrite: Boolean = false
    override fun exists(name: String): Boolean = name in files
    override fun read(name: String): ByteArray = files[name] ?: throw BlobIoException("missing $name")
    override fun writeAtomically(name: String, bytes: ByteArray) {
        if (failNextWrite) { failNextWrite = false; throw BlobIoException("disk full") }
        files[name] = bytes.copyOf()
    }
    override fun rename(from: String, to: String): Boolean { val b = files.remove(from) ?: return false; files[to] = b; return true }
    override fun delete(name: String) { files.remove(name) }
}

public class InMemoryKeyValueStore : KeyValueStore {
    public val values: MutableMap<String, Any> = LinkedHashMap()
    override fun getString(key: String): String? = values[key] as? String
    override fun putString(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
    override fun getBoolean(key: String, default: Boolean): Boolean = values[key] as? Boolean ?: default
    override fun putBoolean(key: String, value: Boolean) { values[key] = value }
}
