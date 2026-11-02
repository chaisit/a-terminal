# R8 rules (round 13 T5) — philosophy: over-keep ก่อนแล้วค่อยหด, ห้ามเดา
# ทุก rule trace กลับ reflection/SPI surface จริงที่ grep + javap เจอ; การ proof = suite เต็มบน release APK (ไม่ใช่ build ผ่าน)

# ---------- MINA SSHD: SPI/reflect จัด (NamedFactory service files, CipherFactory, enums ผ่าน valueOf) ----------
# sshd โหลด crypto factories จาก META-INF/services + ชื่อ class ใน string — keep ทั้ง package = เจตนา (v1, ขนาดเป็น second-order)
-keep class org.apache.sshd.** { *; }
-dontwarn org.apache.sshd.**

# eddsa = pure-Java Ed25519 (sshd key algorithm); EdDSASecurityProvider ลง java.security ผ่านชื่อ class
-keep class net.i2p.crypto.eddsa.** { *; }
-keep class net.i2p.crypto.eddsa.EdDSASecurityProvider
-dontwarn net.i2p.crypto.eddsa.**

# MINA core (sshd-common dependency) — NioProcessor ฯลฯ ถูก instantiate ผ่าน reflection generic type
-keep class org.apache.mina.** { *; }
-dontwarn org.apache.mina.**

# java/awt/swing อ้างใน sshd-common paths ที่ Android ไม่มี (build warning ไม่ใช่ runtime path)
-dontwarn java.awt.**
-dontwarn javax.**

# empirical (R8 missing_rules.txt round-13 T5): compile-time-only annotation/binding classes
# - errorprone = compile annotations ของ Tink (androidx.security-crypto) — ไม่มี runtime semantics
# - slf4j StaticLoggerBinder = API เก่าที่ slf4j-api 1.8+ ตกลงเป็น NOP เมื่อไม่มี binding (= พฤติกรรมเดิมตอน debug)
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.slf4j.impl.**

# ---------- kotlinx-serialization: Settings model อ่าน/เขียนผ่าน serializer ที่ generate ----------
# rule มาตรฐานจาก docs kotlinx.serialization — กัน serializer/factory ถูกลบแล้ว fallback ไป reflect field ที่ rename แล้ว
-keepattributes *Annotation*, InnerClasses, Signature
-dontwarn kotlinx.serialization.**
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
    static **$* *;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    *** Default(...);
}

# ---------- WebView JS bridge: SessionBridge (inner class ของ MainActivity) ----------
# AGP consumer rules keep ตัว annotated method แล้ว แต่ class/ctor ของ bridge ถูก new จาก Java side + JS หาผ่านชื่อ — keep ตรงตัว
-keep class dev.aterminal.app.MainActivity$SessionBridge { *; }
-keepattributes JavascriptInterface

# ---------- java.security Provider instantiation (sshd ชี้ provider ชื่อผ่าน string) ----------
-keep class * extends java.security.Provider {
    public <init>();
}
