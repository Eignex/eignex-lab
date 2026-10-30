package com.eignex.lab.checker

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertContains

class FixedSubcircuitTest {
    @TempDir
    lateinit var work: Path

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = ["[]|VALID", "[1,2,3]|VALID", "[2,1,3]|VALID", "[2,3,1]|VALID",
            "[2,1,4,3]|INVALID", "[2,2,1]|INVALID", "[0,1]|INVALID", "[3,1]|INVALID",
            "[2,3,2]|INVALID"],
    )
    fun `official circuit API checks the fixed active subcircuit`(successors: String, expected: String) {
        val original = "predicate klause_subcircuit(array[int] of var int: x);\n" +
            "constraint klause_subcircuit($successors); solve satisfy;"
        val provider = work.resolve("provider.fzn")
        Files.writeString(provider, FixedSubcircuit.providerInput(original))

        val state = FlatZincGroundCheckerMain.evaluate(provider)

        assertEquals(expected, state)
    }

    @ParameterizedTest
    @ValueSource(strings = ["int", "var int"])
    fun `fixed named arrays retain all other constraints`(type: String) {
        val original = "array[1..3] of $type: succ = [2,1,3];\n" +
            "constraint klause_subcircuit(succ);\nconstraint int_eq(0,1);\nsolve satisfy;"
        val provider = work.resolve("provider.fzn")
        val text = FixedSubcircuit.providerInput(original)
        Files.writeString(provider, text)

        val state = FlatZincGroundCheckerMain.evaluate(provider)

        assertContains(text, "constraint int_eq(0,1);")
        assertEquals("INVALID", state)
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "var 1..2: x; array[1..2] of var int: succ = [x,1]; constraint klause_subcircuit(succ);",
        "array[0..1] of int: succ = [1,0]; constraint klause_subcircuit(succ);",
        "array[1..3] of int: succ = [2,1]; constraint klause_subcircuit(succ);",
        "constraint klause_subcircuit(missing);",
        "constraint other_subcircuit([2,1]);",
    ])
    fun `unsupported forms remain with the original provider`(body: String) {
        val original = "$body\nsolve satisfy;"

        val provider = FixedSubcircuit.providerInput(original)

        assertEquals(original, provider)
    }

    @ParameterizedTest
    @ValueSource(strings = ["var 1..3: free;", "var bool: free;"])
    fun `translating a fixed circuit cannot complete another variable`(declaration: String) {
        val original = "$declaration constraint klause_subcircuit([2,1]); solve satisfy;"
        val provider = work.resolve("provider.fzn")
        Files.writeString(provider, FixedSubcircuit.providerInput(original))

        val state = FlatZincGroundCheckerMain.evaluate(provider)

        assertEquals("UNKNOWN", state)
    }
}
