plugins {
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.jvm") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
    // Compose compiler มากับ Kotlin 2.0 (ห้ามเพิ่ม composeOptions — deprecated ในรุ่นนี้)
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}

// guardrail (retro 11-09): test ที่แขวนต้องแดง ไม่ใช่build ที่ต้องคนไปสังหาร
// (บทเรียน: runBlocking + blocking child ใน WindowStripHostTest แขวน :app:testDebugUnitTest ~4 ชม. — deadlock วัดจริง)
subprojects {
    tasks.withType<Test>().configureEach {
        timeout.set(java.time.Duration.ofMinutes(5)) // suite จริงสั้นกว่านี้มาก (~1–2 นาที/module) — 5 นาที = margin กว้างพอสำหรับเครื่องโหลดหนัก
    }
}
