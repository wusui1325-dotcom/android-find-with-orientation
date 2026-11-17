# 方向尋找 1.2.0 — keep rules for BLE, UWB, Compose, and the foreground service.

# Manifest entry points
-keep class com.wusper.findorientation.FindApp { *; }
-keep class com.wusper.findorientation.MainActivity { *; }
-keep class com.wusper.findorientation.nearby.FindService { *; }
-keep class com.wusper.findorientation.nearby.BootReceiver { *; }

# BLE and sensor callbacks live in this package; binder / system reflection must see them.
-keep class com.wusper.findorientation.nearby.** { *; }
-keepclassmembers class * extends android.bluetooth.BluetoothGattCallback { *; }
-keepclassmembers class * extends android.bluetooth.BluetoothGattServerCallback { *; }
-keepclassmembers class * extends android.bluetooth.le.ScanCallback { *; }
-keepclassmembers class * extends android.bluetooth.le.AdvertiseCallback { *; }
-keepclassmembers class * extends android.hardware.SensorEventListener { *; }
-keepclassmembers class * extends android.location.LocationListener { *; }
-keepclassmembers class * extends android.content.BroadcastReceiver { *; }

# AndroidX UWB talks to a device backend through reflection.
-keep class androidx.core.uwb.** { *; }
-keep class androidx.core.uwb.backend.** { *; }
-dontwarn androidx.core.uwb.**

# Compose runtime (R8 full mode)
-dontwarn androidx.compose.**
-keep class androidx.compose.runtime.internal.** { *; }

# Coroutines
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable
