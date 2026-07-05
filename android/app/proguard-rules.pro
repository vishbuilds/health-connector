# Add project specific ProGuard rules here.
# minifyEnabled is currently disabled (see app/build.gradle.kts), so these
# rules are not exercised by default, but are kept minimal and ready in case
# minification is turned on later.

# kotlinx.serialization keeps (see https://github.com/Kotlin/kotlinx.serialization)
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.vishaal.healthconnector.**$$serializer { *; }
-keepclassmembers class com.vishaal.healthconnector.** {
    *** Companion;
}
-keepclasseswithmembers class com.vishaal.healthconnector.** {
    kotlinx.serialization.KSerializer serializer(...);
}
