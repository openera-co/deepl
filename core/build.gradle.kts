plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

val ortVersion = "1.31.1"

sourceSets["test"].kotlin.srcDir("../pdfshared")

dependencies {
    testImplementation("org.apache.pdfbox:pdfbox:2.0.32")
    // The Android app supplies onnxruntime-android (same ai.onnxruntime API); desktop runtime for tests and tools.
    compileOnly("com.microsoft.onnxruntime:onnxruntime:$ortVersion")
    testImplementation("com.microsoft.onnxruntime:onnxruntime:$ortVersion")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "4g"
    systemProperty("carnet.model", System.getProperty("carnet.model") ?: "")
    systemProperty("carnet.data", System.getProperty("carnet.data") ?: "")
    systemProperty("carnet.fp32", System.getProperty("carnet.fp32") ?: "")
    testLogging { events("failed"); showStandardStreams = true; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

// Command-line harness used by the test loops (tools/run_bench.sh).
val bench by tasks.registering(JavaExec::class) {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.carnet.core.bench.BenchKt")
    maxHeapSize = "6g"
    args = (project.findProperty("benchArgs") as String? ?: "").split(" ").filter { it.isNotBlank() }
}

val dbg by tasks.registering(JavaExec::class) {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set(project.findProperty("dbgMain") as String? ?: "app.carnet.core.dbg.DbgKt")
    args = (project.findProperty("dbgArgs") as String? ?: "").split(" ").filter { it.isNotBlank() }
}
