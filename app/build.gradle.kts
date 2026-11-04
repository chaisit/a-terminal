import java.util.Properties // import จำเป็น: ชื่อ `java` ใน script ถอด shadow โดย java extension ของ project

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Signing is file-driven — the keystore is created and held by the user alone.
//   มี keystore.properties = ต่อ signingConfig จริง; ไม่มี = logger.warn + release UNSIGNED ที่ build ยังผ่าน
//   (repo ไม่มี CI — ต้อง assembleRelease ได้เสมอ; ไฟล์หายห้ามทำให้ build ตาย)
//   เลือก file-based แทน env vars: ไม่มี double code path และไม่หลุดเข้า shell history
val keystoreProps = Properties().also { p ->
    val f = rootProject.file("keystore.properties")
    if (f.isFile) f.inputStream().use { p.load(it) }
}
fun prop(name: String): String =
    requireNotNull(keystoreProps.getProperty(name)) { "keystore.properties มีแต่ขาด key `$name` (ดู keystore.properties.example)" }

android {
    namespace = "dev.aterminal.app"
    compileSdk = 35 // T3 (round 13): AGP 8.7.3 รับได้ถึง 35 (36 ได้ไม่ได้ — ตรวจแล้วรอบ plan)

    defaultConfig {
        applicationId = "dev.aterminal.app"
        minSdk = 29
        // T3 (round 13): overturn การตัดสินใจ round 10 ("คง 34") — release ต้องตาม policy 35+
        // edge-to-edge ถูกบังคับบน API35 = จุดทดสอบจริง (insets padding ใน KeyboardWatcher), predictive-back ยังไม่บังคับ (gap = 36)
        targetSdk = 35
        // T1 (round 13): release build แรก — user เคาะ "0.1.0" + code 2 (1 = ยุค scaffold)
        // round-14 T4: เจอ bug จริง (WebView<86 → xterm จอดำ) → bump ตามกติกาแผน = 0.1.1 / code 3
        // round-15: IME gate, canvas renderer, Unicode 11, MIT + notices ใน APK = เปลี่ยนพฤติกรรมที่ user เห็น → minor bump
        // 0.3.0: public release แรก — UI อังกฤษ, settings เขียนแบบ atomic; 0.2.4 = patch P5 ตัวซ้ำ; 0.2.3 = กู้ pane; 0.2.2 = secrets + ปิด backup
        // 0.4.5: re-pin ยืนยัน fingerprint เมื่อ host key เปลี่ยน (ปิด deadlock "ผู้ใช้ติดตาย" ของ 0.4.4)
        // round-23 → 0.5.0 (code 19): chrome ทั้งชุด — Keys Bar fit-to-width+fold(⋯)+presets, top bar แถวเดียว (pills/∨/⋮),
        // pinch-zoom, chrome ตาม scheme, menu=ปิด IME ก่อน, back-as-escape, ปุ่ม paste ⚡ กลับมา,
        // update check (GitHub Releases: auto 1×/วัน + manual, cumulative changelog — release แรกที่จะถูกเช็คเจอ = release ถัดไป)
        versionCode = 19
        versionName = "0.5.0"
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = prop("storeFile").let { file(it) } // absolute path ผ่านตรง, สัมพัทธ์ = เทียบ module dir
                storePassword = prop("storePassword")
                keyAlias = prop("keyAlias")
                keyPassword = prop("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // T5 (round 13): R8 + resource shrinking — rules in app/proguard-rules.pro (over-keep SSHD/eddsa/bridge)
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                logger.warn("A-Terminal release: no keystore.properties, the APK will be unsigned. See keystore.properties.example.")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }

    packaging {
        resources {
            // jar ของ MINA SSHD มีไฟล์ license/META-INF ซ้ำกันระหว่าง modules
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module",
            )
        }
    }
}

dependencies {
    implementation(project(":terminal-core"))
    implementation(project(":backend-ssh"))
    implementation(project(":backend-termux"))
    implementation(project(":engine-render")) // round 9 N7: native engine + renderer (api → :engine, :terminal-core)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // Compose (round 4): ใช้เฉพาะ tab strip — ตัว terminal host ยังเป็น plain Views
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.9.3")

    // JVM unit tests (round 3): SessionController state machine + ExtraKeysBar byte constants
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    // T4: org.json บน JVM unit test = stub "not mocked" ของ android.jar — ต้องมี impl จริงให้ SessionRestoreTest
    testImplementation("org.json:json:20240303")
}
