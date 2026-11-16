// The root coordinates the build; production code and tests belong to the modules.
plugins {
    base
    id("com.eignex.jvm") apply false
    id("com.eignex.publish.github") apply false
    kotlin("jvm") version "2.4.21" apply false
    kotlin("plugin.serialization") version "2.4.21" apply false
}

// A root check/assemble is the complete multi-module lifecycle, including Java-only tools.
tasks.check { dependsOn(subprojects.map { "${it.path}:check" }) }
tasks.assemble { dependsOn(subprojects.map { "${it.path}:assemble" }) }
