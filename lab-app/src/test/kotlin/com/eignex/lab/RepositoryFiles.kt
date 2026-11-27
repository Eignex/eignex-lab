package com.eignex.lab

import java.nio.file.Files
import java.nio.file.Path

/** A committed file of this checkout, such as a deployment example; Gradle passes the checkout as `lab.repositoryRoot`. */
internal fun repositoryFile(path: String): Path = Path.of(System.getProperty("lab.repositoryRoot"), path).toAbsolutePath()

/** The committed example queue settings, written to [file]. */
internal fun writeExampleQueue(file: Path, edit: (String) -> String = { it }): Path =
    file.also { Files.writeString(it, edit(Files.readString(repositoryFile("deploy/nomad/queue.example.json")))) }

/** The example queue with its eight-CPU cloud target widened to 16 logical CPUs and a 16000 cpuMillis budget. */
internal fun writeWideExampleQueue(file: Path): Path =
    writeExampleQueue(file) { it.replace("6000", "16000").replace("\"logicalCpus\": 8", "\"logicalCpus\": 16") }

/** `deploy/lab <args>` against the lab at [url]; the caller redirects its streams and starts it. */
internal fun labClient(url: String, password: String, args: List<String>): ProcessBuilder =
    ProcessBuilder(listOf("bash", repositoryFile("deploy/lab").toString()) + args).apply {
        environment()["LAB_URL"] = url
        environment()["LAB_PASSWORD"] = password
    }
