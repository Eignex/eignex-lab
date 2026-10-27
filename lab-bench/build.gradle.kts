import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.publish.maven.tasks.GenerateMavenPom
import org.gradle.api.publish.tasks.GenerateModuleMetadata

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    `java-library`
    `maven-publish`
}

group = "com.eignex"
version = providers.gradleProperty("benchVersion").getOrElse("0.1.0-SNAPSHOT")
require(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9]+(?:[.-][A-Za-z0-9]+)*)?").matches(version.toString())) {
    "benchVersion must be a version such as 0.1.0, 0.1.0-rc.1 or 0.1.0-SNAPSHOT"
}

repositories { mavenCentral() }

kotlin {
    jvmToolchain(25)
    explicitApi()
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    testImplementation(platform("org.junit:junit-bom:5.13.3"))
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test { useJUnitPlatform() }

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

publishing {
    publications {
        create<MavenPublication>("sdk") {
            from(components["java"])
            pom {
                name.set("Eignex Lab Bench")
                description.set("Process contract and reporting helpers for repository-owned benchmarks")
                url.set("https://github.com/Eignex/eignex-lab")
            }
        }
    }
}

tasks.register<Sync>("releaseAssets") {
    group = "distribution"
    description = "Stage the SDK and Gradle metadata as flat GitHub Release assets."
    from(tasks.jar)
    from(tasks.named<Jar>("sourcesJar"))
    from(tasks.named<GenerateModuleMetadata>("generateMetadataFileForSdkPublication").flatMap { it.outputFile }) {
        rename { "lab-bench-$version.module" }
    }
    from(tasks.named<GenerateMavenPom>("generatePomFileForSdkPublication").map { it.destination }) {
        rename { "lab-bench-$version.pom" }
    }
    into(layout.buildDirectory.dir("release-assets"))
}

tasks.register<Exec>("verifyRelease") {
    group = "verification"
    description = "Resolve the release assets in an independent Gradle build without credentials or caches."
    dependsOn("releaseAssets")
    commandLine(
        "python3", layout.projectDirectory.file("scripts/verify-release.py").asFile,
        layout.buildDirectory.dir("release-assets").get().asFile,
        version.toString(), gradle.gradleHomeDir!!.resolve("bin/gradle"),
    )
    environment("JAVA_HOME", javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(25))
    }.get().metadata.installationPath.asFile.absolutePath)
}

// Mirrors the public release layout so consumer adapters can be tested before a tag exists.
tasks.register<Sync>("stageAdapterRepository") {
    dependsOn("releaseAssets")
    from(layout.buildDirectory.dir("release-assets"))
    into(layout.buildDirectory.dir("adapter-repository/lab-bench-v$version"))
}
