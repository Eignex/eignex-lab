rootProject.name = "eignex-lab"

pluginManagement {
    val kbuildVersion = "1.3.8"
    repositories {
        ivy {
            url = uri("https://github.com/Eignex/kbuild/releases/download")
            patternLayout { artifact("v[revision]/[artifact]-[revision](-[classifier]).[ext]") }
            metadataSources { gradleMetadata() }
            content { includeModule("com.eignex", "kbuild") }
        }
        mavenCentral()
        gradlePluginPortal()
    }
    resolutionStrategy.eachPlugin {
        if (requested.id.id in setOf("com.eignex.jvm", "com.eignex.publish.github")) {
            useModule("com.eignex:kbuild:${requested.version ?: kbuildVersion}")
        }
    }
    plugins {
        id("com.eignex.jvm") version kbuildVersion
        id("com.eignex.publish.github") version kbuildVersion
    }
}

plugins {
    // Provisions the JDK the build asks for, so the server needs only a Java to launch Gradle.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include("lab-app", "lab-bench", "lab-capacity", "lab-model", "lab-solvers", "xcsp3-tools")

// All modules resolve the same dependency repositories, including KBuild's anonymous platform assets.
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
        ivy {
            url = uri("https://github.com/Eignex/kbuild/releases/download")
            patternLayout { artifact("v[revision]/[artifact]-[revision](-[classifier]).[ext]") }
            metadataSources { gradleMetadata() }
            content { includeModule("com.eignex", "kbuild-platform") }
        }
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
    }
}
