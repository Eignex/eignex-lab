package com.eignex.lab.checker

import java.io.File
import java.util.Properties
import java.util.jar.JarFile
import org.chocosolver.parser.flatzinc.Flatzinc4Parser
import org.chocosolver.solver.Model

/** The official Choco assignment-checking API, isolated in a bounded child JVM. */
internal object FlatZincGroundChecker {
    data class Runtime(val java: File, val arguments: List<String>, val files: List<File>, val version: String, val sha256: String)

    fun runtime(): Runtime {
        fun jar(type: Class<*>) = File(type.protectionDomain.codeSource.location.toURI()).canonicalFile
        fun version(file: File, artifact: String): String = JarFile(file).use { archive ->
            val entry = checkNotNull(archive.getEntry("META-INF/maven/org.choco-solver/$artifact/pom.properties"))
            Properties().apply { archive.getInputStream(entry).use(::load) }.getProperty("version")
        }
        val core = jar(Model::class.java)
        val parser = jar(Flatzinc4Parser::class.java)
        check(jar(FlatZincGroundCheckerMain::class.java).isFile) { "the assignment API wrapper must run from its packaged jar" }
        val coreVersion = version(core, "choco-solver")
        val parserVersion = version(parser, "choco-parsers")
        check(coreVersion == "5.0.1" && parserVersion == "5.0.1") { "Choco checker versions differ from their pins" }
        val classpath = System.getProperty("java.class.path")
        return Runtime(File(System.getProperty("java.home"), "bin/java"), listOf(
            "-Xms16m", "-Xmx256m", "-XX:+UseSerialGC", "-XX:ActiveProcessorCount=1",
            "-XX:MaxMetaspaceSize=128m", "-XX:CompressedClassSpaceSize=64m", "-XX:ReservedCodeCacheSize=64m",
            "-cp", classpath, FlatZincGroundCheckerMain::class.java.name,
        ), classpath.split(File.pathSeparator).map(::File).filter(File::isFile),
            "Choco Constraint.isSatisfied core=$coreVersion parser=$parserVersion|parser-sha256=${sha256(parser)}",
            sha256(core))
    }
}
