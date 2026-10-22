import java.security.MessageDigest

plugins { `java-library` }

val revision = "ca58fc79a42374d080aac4d1410874e748cf0a05"
val sourceSha256 = "541624efb4ac227d0c99c7058e2e07041ff4f119070b0c996cde539fe71cc296"
val sourceRoot = "XCSP3-Java-Tools-$revision"
version = "2.5.1+$revision"

repositories {
    exclusiveContent {
        forRepository {
            ivy {
                url = uri("https://codeload.github.com/xcsp3team/XCSP3-Java-Tools")
                patternLayout { artifact("tar.gz/[revision]") }
                metadataSources { artifact() }
            }
        }
        filter { includeModule("org.xcsp", "xcsp3-tools-sources") }
    }
    mavenCentral()
}

val upstreamSources by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

dependencies {
    upstreamSources("org.xcsp:xcsp3-tools-sources:$revision@tar.gz")
    implementation("org.glassfish:javax.json:1.1.2")
}

val unpackSources by tasks.registering(Sync::class) {
    val archive = upstreamSources.elements.map { it.single().asFile }
    inputs.files(upstreamSources)
    inputs.property("sourceSha256", sourceSha256)
    from(archive.map { tarTree(resources.gzip(it)) }) {
        include("$sourceRoot/src/main/java/**", "$sourceRoot/LICENSE")
    }
    into(layout.buildDirectory.dir("upstream"))
    doFirst {
        val actual = MessageDigest.getInstance("SHA-256").digest(archive.get().readBytes())
            .joinToString("") { "%02x".format(it) }
        check(actual == sourceSha256) { "XCSP3 source archive SHA-256 mismatch: $actual" }
    }
}

sourceSets.main {
    java.srcDir(unpackSources.map { it.destinationDir.resolve("$sourceRoot/src/main/java") })
}

java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
tasks.compileJava {
    options.release.set(8)
    options.encoding = "UTF-8"
}

tasks.jar {
    from(unpackSources.map { it.destinationDir.resolve("$sourceRoot/LICENSE") }) {
        into("META-INF")
        rename { "LICENSE-XCSP3" }
    }
    manifest.attributes(
        "Implementation-Version" to project.version.toString(),
        "XCSP3-Source-Revision" to revision,
        "XCSP3-Source-SHA256" to sourceSha256,
    )
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
