plugins {
    id("com.eignex.jvm")
    kotlin("plugin.serialization")
    application
}

eignexBuild {
    jvmToolchain.set(25)
    jvmTarget.set(25)
}

dependencies {
    implementation(project(":lab-bench"))
    implementation(project(":lab-model"))
    // The source checkers and reference solvers, and the upstream XCSP3 checker they alone need.
    implementation(project(":lab-solvers"))
    // Capacity grants and the provider calls the cloud capacity functions and the capacity adapter share.
    implementation(project(":lab-capacity"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.html)
    implementation(libs.sqlite)
    implementation(libs.zstd)
    implementation(libs.commons.compress)
    implementation(libs.logback)
    testImplementation(testFixtures(project(":lab-solvers")))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(gradleTestKit())
}

eignexPublish { publish.set(false) }

// Keep the existing verification gate until the lab has a baseline for KBuild's lint rules.
eignexLint { enabled.set(false) }

// The in-process Klause MPS parser still uses foreign downcalls and the Vector API.
val parserJvmArgs = listOf("--enable-native-access=ALL-UNNAMED", "--add-modules=jdk.incubator.vector")

// The local UI uses test fixtures and a simulated cluster, never the production launcher or a worker.
tasks.register<JavaExec>("devUi") {
    group = "application"
    description = "Serve the UI at http://127.0.0.1:8080 with sample data and fake Nomad (override with -PuiPort)."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().output + sourceSets.main.get().runtimeClasspath
    mainClass.set("com.eignex.lab.dev.LocalUiKt")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    maxHeapSize = "512m"
    jvmArgs(parserJvmArgs)
    systemProperty("lab.ui.assets", file("src/main/resources/web").absolutePath)
    systemProperty("logback.configurationFile", file("src/test/resources/dev-logback.xml").absolutePath)
    args(providers.gradleProperty("uiPort").orElse("8080").get(), layout.buildDirectory.dir("dev-ui").get().asFile.absolutePath)
}

application {
    applicationName = "eignex-lab"
    mainClass.set("com.eignex.lab.cli.MainKt")
    applicationDefaultJvmArgs = parserJvmArgs
}

tasks.withType<Test>().configureEach {
    // Integration tests read committed deployment contracts from the repository.
    systemProperty("lab.repositoryRoot", rootProject.projectDir.absolutePath)
    jvmArgs(parserJvmArgs)
    val testHome = layout.buildDirectory.dir("test-home")
    systemProperty("user.home", testHome.get().asFile.absolutePath)
    doFirst { testHome.get().asFile.mkdirs() }
    // Tests start this process supervisor command instead of the lab's own (SupervisorProcess); a change reruns them.
    inputs.property("labTestSupervisor", providers.environmentVariable("LAB_TEST_SUPERVISOR").orElse(""))
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
// deploy/nomad/connected-probe.sh runs this against its Nomad sandbox. A task of its own writes its results to
// lab-app/build/test-results/verifyConnectedNomad and leaves the check run's lab-app/build/test-results/test in place.
tasks.register<Test>("verifyConnectedNomad") {
    group = "verification"
    description = "Run ConnectedNomadTest against the sandbox deploy/nomad/connected-probe.sh starts."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching("com.eignex.lab.ConnectedNomadTest") }
    outputs.upToDateWhen { false }
}

// Keep distribution filenames stable for release consumers.
base { archivesName.set("eignex-lab") }
distributions.main { distributionBaseName.set("eignex-lab") }
