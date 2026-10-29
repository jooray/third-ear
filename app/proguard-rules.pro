# EventBus
-keepattributes *Annotation*
-keepclassmembers class ** {
    @org.greenrobot.eventbus.Subscribe <methods>;
}
-keep enum org.greenrobot.eventbus.ThreadMode { *; }

-keep class androidx.compose.runtime.** { *; }

# Third Ear: MarmotKit (UniFFI over JNA) is called reflectively from native code
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class dev.ipf.marmotkit.** { *; }
-keep class io.crates.keyring.** { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**
