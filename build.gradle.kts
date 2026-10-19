plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
    application
}

repositories {
    mavenCentral()
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
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")
    implementation("ch.qos.logback:logback-classic:1.5.18")
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:$ktor")
}

kotlin { jvmToolchain(25) }

// koblas binds host BLAS through foreign downcalls and runs its own kernels on the Vector API.
val koblasJvmArgs = listOf("--enable-native-access=ALL-UNNAMED", "--add-modules=jdk.incubator.vector")

application {
    mainClass.set("com.eignex.lab.MainKt")
    applicationDefaultJvmArgs = koblasJvmArgs
}

tasks.withType<Test>().configureEach { jvmArgs(koblasJvmArgs) }

tasks.test { useJUnitPlatform() }

// install.sh runs the services on the toolchain JDK, so the host needs no JDK 25 of its own.
tasks.register("printJavaHome") {
    val home = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }
        .map { it.metadata.installationPath.asFile.absolutePath }
    doLast { println(home.get()) }
}
