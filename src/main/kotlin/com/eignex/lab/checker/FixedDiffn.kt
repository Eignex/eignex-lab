package com.eignex.lab.checker

import org.chocosolver.solver.Model

internal object FixedDiffn {
    private val constraint = Regex(
        """constraint\s+(?:diffn|fzn_diffn)\s*\((.*)\)\s*(?:::\s*[A-Za-z_][A-Za-z0-9_]*\s*)*;""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val named = Regex("""constraint\s+(?:diffn|fzn_diffn)\b.*""", RegexOption.DOT_MATCHES_ALL)

    fun bind(text: String, model: Model): String {
        val items = MiniZincPoint.items(text)
        val arrays = FixedGlobals.arrays(items)
        var changed = false
        val parsed = items.joinToString("\n", postfix = "\n") { item ->
            if (!named.matches(item)) return@joinToString item
            val call = checkNotNull(constraint.matchEntire(item)) { "unsupported rectangle annotations" }
            val arguments = checkNotNull(FixedGlobals.arguments(call.groupValues[1])) { "unsupported rectangle arguments" }
            check(arguments.size == 4) { "rectangle checking requires four arrays" }
            val values = arguments.map { checkNotNull(FixedGlobals.values(it, arrays)) { "rectangle arrays must be fixed literals" } }
            check(values.all { it.size == values[0].size } && values[0].isNotEmpty() &&
                values[2].all { it > 0 } && values[3].all { it > 0 }) { "unsupported rectangle dimensions" }
            for (index in values[0].indices) {
                check(values[0][index].toLong() + values[2][index] in Int.MIN_VALUE.toLong()..Int.MAX_VALUE &&
                    values[1][index].toLong() + values[3][index] in Int.MIN_VALUE.toLong()..Int.MAX_VALUE) { "rectangle endpoint exceeds integer range" }
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
