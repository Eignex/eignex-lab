package com.eignex.lab.reference

import com.eignex.lab.checker.*
import com.eignex.lab.domain.*

/** The last complete solution a reference solver printed, kept in its record so the verdict can be checked again. */
object Witness {
    private val ASSIGNMENT = Regex("^\\s*[A-Za-z_][A-Za-z_0-9]*\\s*=")

    fun final(format: Format, raw: String): String? = when (format) {
        Format.MINIZINC -> minizinc(raw)
        Format.SMTLIB -> smtModel(raw)
        Format.XCSP3 -> xcsp3(raw)?.let { "v $it" }
        Format.OPB, Format.DIMACS, Format.WCNF, Format.MPS -> raw.lineSequence().lastOrNull { it.startsWith("v ") }
    }

    /** The `v`-prefixed `<instantiation>` block, when the last one printed is complete. */
    fun xcsp3(raw: String): String? {
        val lines = raw.lineSequence().filter { it.startsWith("v ") }.map { it.removePrefix("v ") }.toList()
        val start = lines.indexOfLast { it.trimStart().startsWith("<instantiation") }
        val witness = lines.drop(start.coerceAtLeast(0)).joinToString("\n").trim()
        return witness.takeIf { it.endsWith("</instantiation>") }
    }

    /** The assignments of the last solution before a `----------` separator. */
    private fun minizinc(raw: String): String? {
        val complete = raw.split("----------").dropLast(1).lastOrNull() ?: return null
        val lines = complete.lineSequence().filter { line ->
            val trimmed = line.trim()
            trimmed.isNotEmpty() && !trimmed.startsWith('%') && !trimmed.startsWith("=====")
        }.toList()
        val start = lines.indexOfFirst { ASSIGNMENT.containsMatchIn(it) }
        return if (start < 0) null else lines.drop(start).joinToString("\n")
    }

    /** The last top-level model block: a line `(` through the matching line `)`. */
    private fun smtModel(raw: String): String? {
        val lines = raw.replace("\r\n", "\n").replace('\r', '\n').lines()
        val end = lines.indexOfLast { it == ")" }
        if (end < 0) return null
        val start = lines.subList(0, end).indexOfLast { it == "(" }
        return if (start < 0) null else lines.subList(start, end + 1).joinToString("\n")
    }
}
