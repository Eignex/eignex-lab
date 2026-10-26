plugins {
    id("com.eignex.jvm") version "1.3.5"
    kotlin("plugin.serialization") version "2.4.21"
    application
}

repositories {
    maven("https://central.sonatype.com/repository/maven-snapshots/") { mavenContent { snapshotsOnly() } }
}

val ktor = "3.2.3"

dependencies {
    implementation("io.ktor:ktor-server-core:$ktor")
    implementation("io.ktor:ktor-server-netty:$ktor")
    implementation("io.ktor:ktor-server-content-negotiation:$ktor")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor")
    implementation("io.ktor:ktor-server-status-pages:$ktor")
    // Same pin as klause, so the startup check sees the backend the solves bind.
    implementation("com.eignex:koblas:0.1.1-20260918.215912-232")
    // The MPS reader the SCIP and HiGHS references judge their claims against: the parse klause itself solves.
    implementation("com.eignex:klause-jvm:0.0.1-20261021.091620-860")
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")
    // The official XCSP3 solution checker (MIT), which the lab runs over Klause's XCSP3 witnesses ([com.eignex.lab.checker]).
    implementation(project(":xcsp3-tools"))
    implementation("ch.qos.logback:logback-classic:1.5.18")
    testImplementation("io.ktor:ktor-server-test-host:$ktor")
}

eignexPublish { publish.set(false) }

// Keep the existing verification gate until the lab has a baseline for KBuild's lint rules.
eignexLint { enabled.set(false) }

// koblas binds host BLAS through foreign downcalls and runs its own kernels on the Vector API.
val koblasJvmArgs = listOf("--enable-native-access=ALL-UNNAMED", "--add-modules=jdk.incubator.vector")

application {
    mainClass.set("com.eignex.lab.MainKt")
    applicationDefaultJvmArgs = koblasJvmArgs
}

tasks.withType<Test>().configureEach { jvmArgs(koblasJvmArgs) }

// install.sh runs the services on the toolchain JDK, so the host needs no JDK 25 of its own.
tasks.register("printJavaHome") {
    val home = javaToolchains.launcherFor {
        languageVersion.set(eignexBuild.jvmToolchain.map(JavaLanguageVersion::of))
    }
        .map { it.metadata.installationPath.asFile.absolutePath }
    doLast { println(home.get()) }
}
