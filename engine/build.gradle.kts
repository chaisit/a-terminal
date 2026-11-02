plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// zero Android deps — conformance bridge is invoked via JavaExec (dumpGrid), not instrumented tests
dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    testLogging { events("passed", "failed", "skipped") }
    // R2 (F-1): FloodEvictTest must prove bounded memory — confinement: shrink the heap
    maxHeapSize = "256m"
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

// batch (N6): gradle -q :engine:dumpGridBatch -Plist=<listfile> — ONE JVM for 204 units
tasks.register<JavaExec>("dumpGridBatch") {
    group = "conformance"
    mainClass.set("dev.aterminal.engine.EngineBatchKt")
    classpath = sourceSets["main"].runtimeClasspath
    val list = project.findProperty("list") as String? ?: error("usage: -Plist=<listfile>")
    args(list)
}

// R7 harness: gradle -q :engine:engineSteps -Psteps=<steps file> -Pout=<grid json> — step-script (INIT/B/R) ≡ tools/vt/lib/steps.mjs
tasks.register<JavaExec>("engineSteps") {
    group = "conformance"
    mainClass.set("dev.aterminal.engine.EngineStepsKt")
    classpath = sourceSets["main"].runtimeClasspath
    val steps = project.findProperty("steps") as String? ?: error("usage: -Psteps=<file> -Pout=<file>")
    val out = project.findProperty("out") as String? ?: error("usage: -Psteps=<file> -Pout=<file>")
    args(steps, out)
}

// conformance bridge (N4/N5/N6): gradle -q :engine:dumpGrid -Praw=<raw bytes> -Pout=<grid json>
tasks.register<JavaExec>("dumpGrid") {
    group = "conformance"
    mainClass.set("dev.aterminal.engine.EngineMainKt")
    classpath = sourceSets["main"].runtimeClasspath
    val raw = project.findProperty("raw") as String? ?: error("usage: -Praw=<path raw> -Pout<path out>")
    val out = project.findProperty("out") as String? ?: error("usage: -Praw=<path raw> -Pout=<path out>")
    val cols = (project.findProperty("cols") as String?)?.toIntOrNull() ?: 80
    val rows = (project.findProperty("rows") as String?)?.toIntOrNull() ?: 24
    val hist = (project.findProperty("history") as String?)?.toIntOrNull() ?: 9001
    args(raw, out, cols.toString(), rows.toString(), hist.toString())
}
