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
    implementation(project(":lab-model"))
    // The source checkers and reference solvers, and the XCSP3 checker and Choco they alone need.
    implementation(project(":lab-solvers"))
    implementation("io.ktor:ktor-server-core:$ktor")
    implementation("io.ktor:ktor-server-netty:$ktor")
    implementation("io.ktor:ktor-server-content-negotiation:$ktor")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor")
    implementation("io.ktor:ktor-server-status-pages:$ktor")
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")
    implementation("com.github.luben:zstd-jni:1.5.7-24")
    implementation("ch.qos.logback:logback-classic:1.5.18")
    testImplementation("io.ktor:ktor-server-test-host:$ktor")
    testImplementation("com.eignex:klause-jvm:0.0.1-20261021.091620-860")
    testImplementation(project(":xcsp3-tools"))
    testImplementation(gradleTestKit())
}

eignexPublish { publish.set(false) }

// Keep the existing verification gate until the lab has a baseline for KBuild's lint rules.
eignexLint { enabled.set(false) }

// The in-process Klause MPS parser still uses foreign downcalls and the Vector API.
val parserJvmArgs = listOf("--enable-native-access=ALL-UNNAMED", "--add-modules=jdk.incubator.vector")

application {
    mainClass.set("com.eignex.lab.cli.MainKt")
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
tasks.test { useJUnitPlatform { excludeTags("bench-adapters", "preparation-performance") } }
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

// Explicit full-distribution regression, including cold setup and two fresh-worker warm preparations.
val preparationPerfRoot = providers.environmentVariable("LAB_PREPARATION_PERF_ROOT")
tasks.register<Test>("verifyPreparationPerformance") {
    onlyIf("LAB_PREPARATION_PERF_ROOT must name the target scratch directory") { preparationPerfRoot.isPresent }
    group = "verification"
    description = "Verify full-sized warm preparations; set LAB_PREPARATION_PERF_ROOT to the target scratch disk."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("preparation-performance") }
    outputs.upToDateWhen { false }
    testLogging { showStandardStreams = true }
}

