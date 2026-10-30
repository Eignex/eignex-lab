package com.eignex.lab.checker

internal object FixedGlobals {
    private val constraint = Regex(
        """constraint\s+(klause_all_different_int|klause_circuit|klause_inverse_offsets|klause_lex_less_int)\s*\((.*)\)\s*((?:::\s*[A-Za-z_][A-Za-z0-9_]*\s*)*);""",
    )
    private val array = Regex(
        """array\s*\[\s*1\s*\.\.\s*([0-9]+)\s*]\s*of\s+(?:var\s+)?int\s*:\s*([A-Za-z_][A-Za-z0-9_]*)\s*(?:::\s*[A-Za-z_][A-Za-z0-9_]*\s*)*=\s*(\[[^]]*])\s*;""",
    )

    fun providerInput(text: String): String {
        val items = MiniZincPoint.items(text)
        val arrays = items.mapNotNull { array.matchEntire(it) }.groupBy { it.groupValues[2] }
        fun values(argument: String): List<Int>? {
            if (argument.startsWith('[')) return literal(argument)
            val declaration = arrays[argument]?.singleOrNull() ?: return null
            val length = declaration.groupValues[1].toIntOrNull() ?: return null
            return literal(declaration.groupValues[3])?.takeIf { it.size == length }
        }
        var changed = false
        val derived = items.joinToString("\n", postfix = "\n") { item ->
            val call = constraint.matchEntire(item) ?: return@joinToString item
            val arguments = arguments(call.groupValues[2]) ?: return@joinToString item
            val translated = when (call.groupValues[1]) {
                "klause_all_different_int" -> {
                    if (arguments.size != 1) return@joinToString item
                    val x = values(arguments[0]) ?: return@joinToString item
                    "fzn_all_different_int(${format(x)})"
                }
                "klause_circuit" -> {
                    if (arguments.size != 1) return@joinToString item
                    val x = values(arguments[0]) ?: return@joinToString item
                    if (x.isEmpty()) return@joinToString item
                    if (x.any { it !in 1..x.size }) "int_eq(0,1)" else "fzn_circuit(1,${format(x)})"
                }
                "klause_inverse_offsets" -> {
                    if (arguments.size != 4) return@joinToString item
                    val f = values(arguments[0]) ?: return@joinToString item
                    val fOffset = arguments[1].toIntOrNull() ?: return@joinToString item
                    val g = values(arguments[2]) ?: return@joinToString item
                    val gOffset = arguments[3].toIntOrNull() ?: return@joinToString item
                    if (f.size != g.size) return@joinToString item
                    if (f.isEmpty()) "int_eq(0,0)" else {
                        val forward = f.map { it.toLong() - gOffset }
                        val inverse = g.map { it.toLong() - fOffset }
                        if ((forward + inverse).any { it < 0 || it >= f.size }) "int_eq(0,1)" else {
                            "fzn_inverse(${format(forward)},0,${format(inverse)},0)"
                        }
                    }
                }
                else -> {
                    if (arguments.size != 2) return@joinToString item
                    val x = values(arguments[0]) ?: return@joinToString item
                    val y = values(arguments[1]) ?: return@joinToString item
                    if (x.size != y.size || x.isEmpty()) return@joinToString item
                    "choco_fzn_lex_less(${format(x)},${format(y)},true)"
                }
            }
            changed = true
            "constraint $translated ${call.groupValues[3]};"
        }
        return if (changed) derived else text
    }

    private fun literal(text: String): List<Int>? {
        if (!text.startsWith('[') || !text.endsWith(']')) return null
        val content = text.substring(1, text.lastIndex).trim()
        if (content.isEmpty()) return emptyList()
        return content.split(',').map { it.trim().toIntOrNull() ?: return null }
    }

    private fun format(values: List<Number>): String = values.joinToString(",", "[", "]")

    private fun arguments(text: String): List<String>? {
        val result = ArrayList<String>()
        var depth = 0
        var start = 0
        for ((index, character) in text.withIndex()) {
            when (character) {
                '[' -> depth++
                ']' -> if (--depth < 0) return null
                ',' -> if (depth == 0) {
                    result += text.substring(start, index).trim()
                    start = index + 1
                }
            }
        }
        if (depth != 0) return null
        result += text.substring(start).trim()
        return result
    }
}
