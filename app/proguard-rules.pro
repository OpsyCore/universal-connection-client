# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class io.ucc.**$$serializer { *; }
-keepclassmembers class io.ucc.** { *** Companion; }
-keepclasseswithmembers class io.ucc.** { kotlinx.serialization.KSerializer serializer(...); }
