// The lab's own solvers: the source checkers that verify a solution claim against its problem, and the reference
// solvers that produce the reference rows. Only this module needs the XCSP3 checker.
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    `java-library`
    `java-test-fixtures`
}

kotlin { jvmToolchain(25) }

dependencies {
    api(project(":lab-model"))
    // The official XCSP3 solution checker (MIT), which the lab runs over solver XCSP3 witnesses ([com.eignex.lab.checker]).
    implementation(project(":xcsp3-tools"))
    implementation(libs.zstd)

    testImplementation(platform(libs.junit.bom))
    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.junit.params)
    testFixturesImplementation(kotlin("test"))
    testRuntimeOnly(libs.junit.launcher)
}

// The CNF evaluator source ships beside its classes for receipt identity.
tasks.processResources {
    from("src/main/kotlin") {
        include("com/eignex/lab/checker/DimacsSourceEvaluation.kt")
        into("checker-source")
    }
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // Requiring real checkers must rerun tests previously allowed to skip unavailable tools.
    inputs.property("requireCheckers", providers.environmentVariable("LAB_REQUIRE_CHECKERS").orElse(""))
    val testHome = layout.buildDirectory.dir("test-home")
    systemProperty("user.home", testHome.get().asFile.absolutePath)
    doFirst { testHome.get().asFile.mkdirs() }
}
