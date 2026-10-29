plugins {
    id("com.eignex.jvm")
    id("com.eignex.publish.github") apply false
    kotlin("plugin.serialization") version "2.4.21"
    application
}

// KBuild's platform uses the same anonymous release layout as its plugins.
allprojects {
    repositories {
        ivy {
            url = uri("https://github.com/Eignex/kbuild/releases/download")
            patternLayout { artifact("v[revision]/[artifact]-[revision](-[classifier]).[ext]") }
            metadataSources { gradleMetadata() }
            content { includeModule("com.eignex", "kbuild-platform") }
        }
    }
}

eignexBuild {
    jvmToolchain.set(25)
    jvmTarget.set(25)
}

repositories {
    maven("https://central.sonatype.com/repository/maven-snapshots/") { mavenContent { snapshotsOnly() } }
}

val ktor = "3.2.3"

dependencies {
    implementation(project(":lab-bench"))
    implementation("io.ktor:ktor-server-core:$ktor")
    implementation("io.ktor:ktor-server-netty:$ktor")
    implementation("io.ktor:ktor-server-content-negotiation:$ktor")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor")
    implementation("io.ktor:ktor-server-status-pages:$ktor")
    // Retained MPS source-checker parser; consumer execution uses only repository manifests.
    implementation("com.eignex:klause-jvm:0.0.1-20261021.091620-860")
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")
    implementation("com.github.luben:zstd-jni:1.5.7-24")
    // The official XCSP3 solution checker (MIT), which the lab runs over solver XCSP3 witnesses ([com.eignex.lab.checker]).
    implementation(project(":xcsp3-tools"))
    implementation("ch.qos.logback:logback-classic:1.5.18")
    testImplementation("io.ktor:ktor-server-test-host:$ktor")
    testImplementation(gradleTestKit())
}

eignexPublish { publish.set(false) }

// Keep the existing verification gate until the lab has a baseline for KBuild's lint rules.
eignexLint { enabled.set(false) }

// The in-process Klause MPS parser still uses foreign downcalls and the Vector API.
val parserJvmArgs = listOf("--enable-native-access=ALL-UNNAMED", "--add-modules=jdk.incubator.vector")

application {
    mainClass.set("com.eignex.lab.MainKt")
    applicationDefaultJvmArgs = parserJvmArgs
}

tasks.withType<Test>().configureEach {
    jvmArgs(parserJvmArgs)
    val testHome = layout.buildDirectory.dir("test-home")
    systemProperty("user.home", testHome.get().asFile.absolutePath)
    doFirst { testHome.get().asFile.mkdirs() }
}

// install.sh runs the services on the toolchain JDK, so the host needs no JDK 25 of its own.
tasks.register("printJavaHome") {
    val home = javaToolchains.launcherFor {
        languageVersion.set(eignexBuild.jvmToolchain.map(JavaLanguageVersion::of))
    }
        .map { it.metadata.installationPath.asFile.absolutePath }
    doLast { println(home.get()) }
}

// Runs real, already-built consumer adapters through preparation, execution and coordinator ingestion.
tasks.test { useJUnitPlatform { excludeTags("bench-adapters") } }
val benchAdapterMatrix = providers.environmentVariable("LAB_BENCH_ADAPTER_MATRIX")
tasks.register<Test>("verifyBenchAdapters") {
    onlyIf("LAB_BENCH_ADAPTER_MATRIX must name prepared scenarios") { benchAdapterMatrix.isPresent }
    group = "verification"
    description = "Verify prepared repository adapters from LAB_BENCH_ADAPTER_MATRIX through the worker/coordinator path."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("bench-adapters") }
    environment("JAVA_HOME", javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(25))
    }.get().metadata.installationPath.asFile.absolutePath)
    outputs.upToDateWhen { false }
    testLogging { showStandardStreams = true }
}
