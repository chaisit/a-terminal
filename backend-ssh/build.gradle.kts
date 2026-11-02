plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":terminal-core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    // Apache MINA SSHD — Apache-2.0, PTY/interactive channel support คราวหน้า
    implementation("org.apache.sshd:sshd-core:2.14.0")
    // MINA ตรวจ classpath ตอน boot: มี lib นี้ = เปิดใช้ ed25519 (host key + user key ที่นิยมที่สุด)
    implementation("net.i2p.crypto:eddsa:0.3.0")

    // test: in-process SshServer lab (server อยู่ในระดับเดียวกับ client ใน jar เดียวกัน)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

tasks.test {
    testLogging { events("passed", "failed", "skipped") }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}
