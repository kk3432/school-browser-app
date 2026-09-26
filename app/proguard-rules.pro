# ===== MMKV（native 库，禁止混淆）=====
-keep class com.tencent.mmkv.** { *; }

# ===== ZXing 扫码库 =====
-keep class com.google.zxing.** { *; }
-keep class com.journeyapps.** { *; }
-dontwarn com.google.zxing.**
-dontwarn com.journeyapps.**

# ===== OkHttp / Okio（库自带 consumer rules，此处兜底）=====
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# ===== Kotlin 协程 =====
-keepclassmembers class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# ===== 应用自身数据模型（org.json 显式解析，保留以防内联优化）=====
-keep class edu.campus.browser.config.** { *; }
-keep class edu.campus.browser.net.** { *; }

# ===== 保留注解与枚举 =====
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keepclassmembers enum * { *; }

# ===== WebView（无 JS 接口，保留兜底）=====
-keepclassmembers class * extends android.webkit.WebViewClient { *; }
