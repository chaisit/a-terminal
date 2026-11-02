plugins {
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.jvm") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
    // Compose compiler มากับ Kotlin 2.0 (ห้ามเพิ่ม composeOptions — deprecated ในรุ่นนี้)
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
