// The lab's own solvers: the source checkers that verify a solution claim against its problem, and the reference
// solvers that produce the reference rows, which depend on each other. Only they need the XCSP3 checker and Choco.
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
    implementation(libs.choco) {
        exclude(group = "org.xcsp", module = "xcsp3-tools")
        // Choco asks for slf4j as a version range, which Gradle resolves against Maven Central on every fresh build.
        exclude(group = "org.slf4j", module = "slf4j-api")
    }
    implementation(libs.zstd)
    // Retained MPS source-checker parser; consumer execution uses only repository manifests.
    implementation(libs.klause)
    // Choco's slf4j 1.7 range is excluded above; the application runs the 2.0 that logback brings, so this module builds against it too.
    implementation(libs.slf4j)

    testImplementation(platform(libs.junit.bom))
    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.junit.params)
    testFixturesImplementation(kotlin("test"))
    testRuntimeOnly(libs.junit.launcher)
}

// The exact checkers name the hash of their own source in each verdict, so the source ships beside the classes.
tasks.processResources {
    from("src/main/kotlin") {
        include("com/eignex/lab/checker/ExactMpsChecker.kt", "com/eignex/lab/checker/MpsPolicyChecks.kt", "com/eignex/lab/checker/DimacsSourceEvaluation.kt")
        into("checker-source")
    }
}

// The retained MPS parser uses foreign downcalls and the Vector API.
tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED", "--add-modules=jdk.incubator.vector")
    val testHome = layout.buildDirectory.dir("test-home")
    systemProperty("user.home", testHome.get().asFile.absolutePath)
    doFirst { testHome.get().asFile.mkdirs() }
}
