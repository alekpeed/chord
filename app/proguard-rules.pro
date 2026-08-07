# kotlinx.serialization keeps the structured chord format readable across releases; its generated
# serializers are referenced reflectively and must survive shrinking.
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.alekpeed.hearsay.**$$serializer { *; }
-keepclassmembers class com.alekpeed.hearsay.** {
    *** Companion;
}
-keepclasseswithmembers class com.alekpeed.hearsay.** {
    kotlinx.serialization.KSerializer serializer(...);
}
