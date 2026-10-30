package com.eignex.lab.checker

import org.chocosolver.solver.Model

internal object FixedDiffn {
    private val constraint = Regex(
        """constraint\s+(?:diffn|fzn_diffn)\s*\((.*)\)\s*(?:::\s*[A-Za-z_][A-Za-z0-9_]*\s*)*;""",
    )

    fun bind(text: String, model: Model): String {
        val items = MiniZincPoint.items(text)
        val arrays = FixedGlobals.arrays(items)
        var changed = false
        val parsed = items.joinToString("\n", postfix = "\n") { item ->
            val call = constraint.matchEntire(item) ?: return@joinToString item
            val arguments = FixedGlobals.arguments(call.groupValues[1]) ?: return@joinToString item
            if (arguments.size != 4) return@joinToString item
            val values = arguments.map { FixedGlobals.values(it, arrays) ?: return@joinToString item }
            if (values.any { it.size != values[0].size } || values[0].isEmpty() ||
                values[2].any { it <= 0 } || values[3].any { it <= 0 }) return@joinToString item
            for (index in values[0].indices) {
                if (values[0][index].toLong() + values[2][index] !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE ||
                    values[1][index].toLong() + values[3][index] !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE) return@joinToString item
            }
            val fixed = values.map { row -> row.map { model.intVar(it) }.toTypedArray() }
            // Redundant cumulative reasoning creates unfixed helper variables; the DIFFN assertion has no helpers.
            model.diffN(fixed[0], fixed[1], fixed[2], fixed[3], false).post()
            changed = true
            ""
        }
        return if (changed) parsed else text
    }
}
