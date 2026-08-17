# kotlinx.serialization keeps its generated serializers on the companion of each @Serializable
# class; R8 needs to be told not to strip them.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class dev.starlinkguard.core.**$$serializer { *; }
-keepclassmembers class dev.starlinkguard.core.** {
    *** Companion;
}
-keepclasseswithmembers class dev.starlinkguard.core.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp ships optional references to Conscrypt/BouncyCastle providers that are absent here.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
