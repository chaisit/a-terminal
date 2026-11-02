plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.aterminal.render"
    compileSdk = 34

    defaultConfig {
        // TextShaper = API 31 — fallback Paint path ทำให้ minSdk 29 ปลอดภัย (ข้อจำกัด API29-30 จดใน test-report-9)
        minSdk = 29
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    api(project(":engine"))         // Cell/TermLine/TerminalEngine (pure Kotlin)
    api(project(":terminal-core"))  // Scheme (palette จาก settings.json)
    testImplementation("junit:junit:4.13.2")
}
