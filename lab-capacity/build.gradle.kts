import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.zip.ZipFile

// Capacity grants and the cloud providers' capacity control: what the coordinator signs, what the cloud functions verify
// before they open or close a pool, and the provider API calls both the functions and the Mac's capacity adapter make.
// The functions run on the providers' Java 21 runtimes, so this module targets Java 21 and depends on JSON alone.
plugins {
    kotlin("jvm")
    `java-library`
}

repositories { mavenCentral() }

kotlin {
    jvmToolchain(25)
    compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    // The runtimes provide both; the AWS interface is packaged with the function, as AWS expects.
    implementation("com.amazonaws:aws-lambda-java-core:1.2.3")
    compileOnly("com.google.cloud.functions:functions-framework-api:1.1.4")
    testImplementation("com.google.cloud.functions:functions-framework-api:1.1.4")
    testImplementation(platform("org.junit:junit-bom:5.13.3"))
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test { useJUnitPlatform() }

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

// Byte-for-byte reproducible packages, so Terraform's source hash changes only when the functions do.
val functionsDir = rootProject.layout.buildDirectory.dir("capacity-functions")

// AWS Lambda's Java layout: the classpath's jars under lib/.
val awsCapacityFunctions by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Package the AWS capacity renewal and expiry Lambda (java21) for deploy/infra/aws."
    archiveFileName.set("aws-capacity-functions.zip")
    destinationDirectory.set(functionsDir)
    into("lib") {
        from(tasks.jar)
        from(configurations.runtimeClasspath)
    }
}

// Cloud Functions' Java buildpack deploys a single self-contained jar at the source root; the runtime supplies the
// Functions Framework, which is compileOnly here.
val gcpCapacityJar by tasks.registering(Jar::class) {
    archiveFileName.set("gcp-capacity-functions.jar")
    destinationDirectory.set(layout.buildDirectory.dir("gcp"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.map { it.output })
    from(configurations.runtimeClasspath.map { files -> files.map { zipTree(it) } }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/*/module-info.class", "module-info.class")
    }
}
val gcpCapacityFunctions by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Package the GCP capacity renewal and expiry functions (java21) for deploy/infra/gcp."
    archiveFileName.set("gcp-capacity-functions.zip")
    destinationDirectory.set(functionsDir)
    from(gcpCapacityJar)
}

val capacityFunctions by tasks.registering {
    group = "distribution"
    description = "Package the AWS and GCP capacity functions into build/capacity-functions."
    dependsOn(awsCapacityFunctions, gcpCapacityFunctions)
}

// The packages hold the handlers Terraform names, compiled for the runtimes' Java 21.
val verifyCapacityFunctions by tasks.registering {
    group = "verification"
    description = "Check that the capacity function packages hold their handlers as Java 21 classes."
    val aws = awsCapacityFunctions.flatMap { it.archiveFile }
    val gcp = gcpCapacityFunctions.flatMap { it.archiveFile }
    inputs.files(aws, gcp)
    doLast {
        fun major(bytes: ByteArray) = ((bytes[6].toInt() and 0xff) shl 8) or (bytes[7].toInt() and 0xff)
        ZipFile(aws.get().asFile).use { zip ->
            val module = zip.entries().asSequence().single { it.name.startsWith("lib/lab-capacity") }
            val jar = File(temporaryDir, "lab-capacity.jar").apply { zip.getInputStream(module).use { input -> outputStream().use { input.copyTo(it) } } }
            ZipFile(jar).use { classes ->
                for (handler in listOf("AwsRenewHandler", "AwsExpireHandler")) {
                    val entry = checkNotNull(classes.getEntry("com/eignex/lab/cloud/aws/$handler.class")) { "$handler missing" }
                    check(major(classes.getInputStream(entry).readBytes()) == 65) { "$handler is not a Java 21 class" }
                }
            }
            check(zip.entries().asSequence().any { it.name.startsWith("lib/kotlin-stdlib") }) { "the AWS package lacks the Kotlin runtime" }
        }
        ZipFile(gcp.get().asFile).use { zip ->
            val entry = zip.entries().asSequence().single()
            check(entry.name == "gcp-capacity-functions.jar") { "the GCP source holds ${entry.name}" }
            val jar = File(temporaryDir, entry.name).apply { zip.getInputStream(entry).use { input -> outputStream().use { input.copyTo(it) } } }
            ZipFile(jar).use { classes ->
                for (handler in listOf("GcpRenewFunction", "GcpExpireFunction")) {
                    val entry = checkNotNull(classes.getEntry("com/eignex/lab/cloud/gcp/$handler.class")) { "$handler missing" }
                    check(major(classes.getInputStream(entry).readBytes()) == 65) { "$handler is not a Java 21 class" }
                }
                check(classes.getEntry("kotlin/Unit.class") != null) { "the GCP jar lacks the Kotlin runtime" }
                check(classes.getEntry("com/google/cloud/functions/HttpFunction.class") == null) { "the GCP jar bundles the Functions Framework" }
            }
        }
    }
}
tasks.check { dependsOn(verifyCapacityFunctions) }
