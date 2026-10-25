rootProject.name = "eignex-lab"

plugins {
    // Provisions the JDK the build asks for, so the server needs only a Java to launch Gradle.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include("xcsp3-tools")
