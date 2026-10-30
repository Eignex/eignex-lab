package com.eignex.lab.checker

internal object FixedSubcircuit {
    private val constraint = Regex("""constraint\s+klause_subcircuit\s*\(([^;]*)\)\s*;""")
    private val array = Regex(
        """array\s*\[\s*1\s*\.\.\s*([0-9]+)\s*]\s*of\s+(?:var\s+)?int\s*:\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(\[[^]]*])\s*;""",
    )
    private val identifier = Regex("[A-Za-z_][A-Za-z0-9_]*")

    fun providerInput(text: String): String {
        val items = MiniZincPoint.items(text)
        val arrays = items.mapNotNull { array.matchEntire(it) }.groupBy { it.groupValues[2] }
        var changed = false
        val derived = items.joinToString("\n", postfix = "\n") { item ->
            val call = constraint.matchEntire(item) ?: return@joinToString item
            val argument = call.groupValues[1].trim()
            val values = if (identifier.matches(argument)) {
                val declaration = arrays[argument]?.singleOrNull() ?: return@joinToString item
                val length = declaration.groupValues[1].toIntOrNull() ?: return@joinToString item
                literal(declaration.groupValues[3])?.takeIf { it.size == length } ?: return@joinToString item
            } else literal(argument) ?: return@joinToString item
            changed = true
            circuit(values)
        }
        return if (changed) derived else text
    }

    private fun literal(text: String): List<Int>? {
        if (!text.startsWith('[') || !text.endsWith(']')) return null
        val content = text.substring(1, text.lastIndex).trim()
        if (content.isEmpty()) return emptyList()
        return content.split(',').map { it.trim().toIntOrNull() ?: return null }
    }

    private fun circuit(successors: List<Int>): String {
        if (successors.any { it !in 1..successors.size }) return "constraint int_eq(0,1);"
        val active = successors.indices.filter { successors[it] != it + 1 }
        if (active.isEmpty()) return "constraint int_eq(0,0);"
        // Fixed self loops are inactive. Dense relabeling preserves precisely the edges in the active circuit.
        val dense = active.withIndex().associate { it.value to it.index }
        val next = active.map { dense[successors[it] - 1] ?: return "constraint int_eq(0,1);" }
        return "constraint fzn_circuit(0, [${next.joinToString(",")}]);"
    }
}
