# Keep kotlinx.serialization generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class it.davide.diario.** {
    *** Companion;
}
-keepclasseswithmembers class it.davide.diario.** {
    kotlinx.serialization.KSerializer serializer(...);
}
