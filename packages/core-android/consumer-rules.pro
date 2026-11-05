# kotlinx.serialization: keep generated serializers for contract types.
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class ug.kuuma.contracts.**$$serializer { *; }
-keepclassmembers class ug.kuuma.contracts.** { *** Companion; }
-keepclasseswithmembers class ug.kuuma.contracts.** { kotlinx.serialization.KSerializer serializer(...); }
# SQLCipher
-keep class net.zetetic.database.** { *; }
