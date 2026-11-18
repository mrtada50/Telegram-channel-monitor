# Optional/platform classes referenced by libraries but absent on Android.
-dontwarn com.google.re2j.**
-dontwarn org.jspecify.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# TDLib (only present when built with enableTdlib=true): JNI needs these names intact.
-keep class org.drinkless.tdlib.** { *; }
-dontwarn org.drinkless.tdlib.**
