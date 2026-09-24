# Universal Connection Client — release shrinking rules.
# Every rule here is narrow and justified. R8 handles Compose, AndroidX,
# CameraX, ML Kit and WorkManager through the consumer rules those libraries
# ship in their own AARs; nothing is added for them here on purpose.

# --- kotlinx.serialization (reflection-free, but the generated $$serializer
#     classes and Companion.serializer() are looked up by name) ------------
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
# Our @Serializable models: profiles/subscriptions/health (encrypted JSON files)
# and the sing-box JSON config builder. Field renaming would break the on-disk
# JSON format across upgrades, hence the serializer keep (names come from the
# @SerialName/property names baked into the generated serializer, not from
# reflection, so no field-level keep is needed).
-keep,includedescriptorclasses class io.ucc.**$$serializer { *; }
-keepclassmembers class io.ucc.** { *** Companion; }
-keepclasseswithmembers class io.ucc.** { kotlinx.serialization.KSerializer serializer(...); }
# Polymorphic @Serializable sealed hierarchies (Transport, Authentication,
# ProfileSource…) are registered through the generated serializers above; the
# class discriminator uses @SerialName, not the runtime class name.

# --- sing-box / libbox (gomobile) --------------------------------------------
# Kept by core/engine-singbox/consumer-rules.pro: the Go runtime resolves the
# generated Java binding classes and their methods by name via JNI.

# --- VpnService ---------------------------------------------------------------
# Declared in the manifest → kept automatically by the AGP/R8 manifest pass.
# The system binds it by class name; no extra rule required.

# --- Debug info ---------------------------------------------------------------
# Keep line numbers for readable stack traces in bug reports; hide the original
# source file name (the mapping file restores it).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
