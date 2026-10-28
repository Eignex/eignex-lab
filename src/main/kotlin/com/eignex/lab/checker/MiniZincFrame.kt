package com.eignex.lab.checker

/** The exact compiler outputs and native stdout retained with a MiniZinc solve record. */
data class MiniZincFrame(val flatZinc: String, val outputModel: String, val stdout: String) {
    companion object {
        internal fun read(root: java.nio.file.Path, prefix: String, artifacts: Map<String, String>): MiniZincFrame? {
            val text = listOf("model.fzn", "model.ozn", "cli.stdout").map { name ->
                val path = prefix + name
                val file = root.resolve(path).toFile()
                if (!file.isFile || file.length() > 8 * 1024 * 1024) return null
                val bytes = file.inputStream().use { it.readNBytes(8 * 1024 * 1024 + 1) }
                if (bytes.size > 8 * 1024 * 1024 || sha256(bytes) != artifacts[path]) return null
                bytes.decodeToString(throwOnInvalidSequence = true)
            }
            return MiniZincFrame(text[0], text[1], text[2])
        }
    }
}

/** Protocol binding only; MiniZinc evaluates the constraints and renders the output model. */
internal object MiniZincPoint {
    private val scalar = Regex("([A-Za-z][A-Za-z0-9_]*|_[A-Za-z0-9_]+)\\s*=\\s*(-?[0-9]+|true|false);")
    private val objective = Regex("\\b(minimize|maximize)\\s+([A-Za-z_][A-Za-z0-9_]*|-?[0-9]+)\\s*;\\s*$")

    data class Bound(val model: String, val native: String, val optimize: Boolean)

    fun bind(frame: MiniZincFrame, claimedObjective: String?): Bound {
        require(listOf(frame.flatZinc, frame.outputModel, frame.stdout).all { it.length <= 8 * 1024 * 1024 })
        val final = frame.stdout.split("----------").dropLast(1).lastOrNull()
            ?: error("native stdout kept no complete solution")
        val lines = final.lines()
        val begin = lines.indexOf("% klause-assignment-begin: flatzinc-integer-v1")
        val end = lines.indexOf("% klause-assignment-end")
        require(begin >= 0 && end > begin && lines.count { it.startsWith("% klause-assignment-begin:") } == 1) {
            "native solution kept no complete integer/Boolean point"
        }
        val point = linkedMapOf<String, String>()
        for (line in lines.subList(begin + 1, end)) {
            require(line.startsWith("% klause-assignment: "))
            val match = scalar.matchEntire(line.removePrefix("% klause-assignment: "))
                ?: error("non-scalar complete-point coordinate")
            require(point.put(match.groupValues[1], match.groupValues[2]) == null) { "duplicate coordinate" }
        }
        require(point.size in 1..65_537)
        val items = items(frame.flatZinc)
        val variables = items.filter { it.startsWith("var ") }.map {
            Regex("var\\s+[^:]+:\\s*([A-Za-z_][A-Za-z0-9_]*)\\b").find(it)?.groupValues?.get(1)
                ?: error("unsupported scalar declaration")
        }.toSet()
        require(point.keys - "_objective" == variables) { "point does not cover exactly the original scalar variables" }
        require(items.none { it.startsWith("array ") && "var " in it && '=' !in it }) {
            "unnamed array variables are unsupported"
        }
        val solve = items.single { it.startsWith("solve ") }
        val target = objective.find(solve)?.groupValues?.get(2)
        require(target != null || solve.trim() == "solve satisfy;") { "unsupported solve item" }
        if (target != null) {
            require(claimedObjective?.matches(Regex("-?[0-9]+")) == true) { "missing exact integer objective" }
            require(point["_objective"] == claimedObjective) { "point and record objectives differ" }
        }
        val native = lines.filter { it.isNotBlank() && !it.trimStart().startsWith('%') && !it.startsWith("=====") }
            .joinToString("\n") + "\n----------\n"
        val assignments = items(native.substringBefore("----------"))
        require(assignments.all { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*\\s*=.+;", RegexOption.DOT_MATCHES_ALL)) })
        val model = buildString {
            items.filterNot { it == solve || it.startsWith("output ") }.forEach { appendLine(it) }
            for ((name, value) in point) if (name != "_objective") appendLine("constraint $name = $value;")
            for (assignment in assignments) {
                val name = assignment.substringBefore('=').trim()
                if (name != "_objective") appendLine("constraint $name = ${assignment.substringAfter('=').trim()}")
            }
            if (target != null) appendLine("constraint $target = $claimedObjective;")
            appendLine("output [];")
            appendLine("solve satisfy;")
        }
        return Bound(model, native, target != null)
    }

    /** Split compiler-generated statements without interpreting their constraints. */
    fun items(text: String): List<String> {
        val result = ArrayList<String>()
        val item = StringBuilder()
        var quoted = false
        var escaped = false
        var comment = false
        for (character in text) {
            if (comment) {
                if (character == '\n') { comment = false; item.append(' ') }
            } else if (!quoted && character == '%') {
                comment = true
            } else {
                item.append(character)
                if (quoted) {
                    if (escaped) escaped = false
                    else if (character == '\\') escaped = true
                    else if (character == '"') quoted = false
                } else if (character == '"') quoted = true
                else if (character == ';') {
                    result += item.toString().trim()
                    item.clear()
                }
            }
        }
        require(!quoted && item.isBlank()) { "incomplete MiniZinc statement" }
        return result
    }

    fun discharged(text: String): Boolean = items(text).singleOrNull()?.matches(Regex("solve\\s+satisfy\\s*;")) == true

    fun inconsistent(text: String): Boolean = items(text).map { it.replace(Regex("\\s+"), "") } ==
        listOf("constraintbool_eq(false,true);", "solvesatisfy;")
}
