// The lab's own solvers: the source checkers that verify a solution claim against its problem, and the reference
// solvers that produce the reference rows, which depend on each other. Only they need the XCSP3 checker and Choco.
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    `java-library`
}

repositories {
    mavenCentral()
    maven("https://central.sonatype.com/repository/maven-snapshots/") { mavenContent { snapshotsOnly() } }
}

kotlin { jvmToolchain(25) }

dependencies {
    api(project(":lab-model"))
    // The official XCSP3 solution checker (MIT), which the lab runs over solver XCSP3 witnesses ([com.eignex.lab.checker]).
    implementation(project(":xcsp3-tools"))
    implementation("org.choco-solver:choco-parsers:5.0.1") {
        exclude(group = "org.xcsp", module = "xcsp3-tools")
    }
    implementation("com.github.luben:zstd-jni:1.5.7-24")
    // Retained MPS source-checker parser; consumer execution uses only repository manifests.
    implementation("com.eignex:klause-jvm:0.0.1-20261021.091620-860")
    constraints {
        // Choco asks for slf4j 1.7; the application runs the 2.0 that logback brings, so this module builds against it too.
        implementation("org.slf4j:slf4j-api:2.0.17")
    }
}

// The exact checkers name the hash of their own source in each verdict, so the source ships beside the classes.
tasks.processResources {
    from("src/main/kotlin") {
        include("com/eignex/lab/checker/ExactMpsChecker.kt", "com/eignex/lab/checker/DimacsSourceEvaluation.kt")
        into("checker-source")
    }
}
