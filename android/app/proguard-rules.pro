# Retrofit
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.claudelists.app.api.** { *; }
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
