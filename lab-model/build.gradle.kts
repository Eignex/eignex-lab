// The lab's model of a problem it runs: what an experiment's cases are made of, which every part of the lab and the
// reference and checker solvers share. It depends on the bench protocol alone, since a problem may carry a bench case.
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    `java-library`
}

repositories { mavenCentral() }

kotlin { jvmToolchain(25) }

dependencies {
    api(project(":lab-bench"))
}
