package com.eignex.lab.checker

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertContains
import kotlin.test.assertEquals

class FixedGlobalsTest {
    @TempDir
    lateinit var work: Path

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "klause_all_different_int([1,2,3])|VALID",
            "klause_all_different_int([1,2,1])|INVALID",
            "klause_circuit([2,3,1])|VALID",
            "klause_circuit([2,1,4,3])|INVALID",
            "klause_circuit([0,1])|INVALID",
            "klause_lex_less_int([1,2],[1,3])|VALID",
            "klause_lex_less_int([1,2],[1,2])|INVALID",
            "klause_lex_less_int([1,3],[1,2])|INVALID",
            "klause_inverse_offsets([8,7],-2,[-1,-2],7)|VALID",
            "klause_inverse_offsets([8,7],-2,[-2,-1],7)|INVALID",
            "klause_inverse_offsets([8,9],-2,[-1,-2],7)|INVALID",
        ],
    )
    fun `official assignment APIs check the fixed global`(call: String, expected: String) {
        val original = "constraint $call; solve satisfy;"
        val provider = work.resolve("provider.fzn")
        Files.writeString(provider, FixedGlobals.providerInput(original))

        val state = FlatZincGroundCheckerMain.evaluate(provider)

        assertEquals(expected, state)
    }

    @ParameterizedTest
    @ValueSource(strings = ["domain", "klause_symmetry"])
    fun `fixed named arrays preserve metadata and other constraints`(annotation: String) {
        val original = "array[1..3] of var int: x :: var_is_introduced = [1,2,3];\n" +
            "constraint klause_all_different_int(x) :: $annotation;\nconstraint int_eq(0,1);\nsolve satisfy;"
        val text = FixedGlobals.providerInput(original)
        val provider = work.resolve("provider.fzn")
        Files.writeString(provider, text)

        val state = FlatZincGroundCheckerMain.evaluate(provider)

        assertContains(text, ":: $annotation;")
        assertContains(text, "constraint int_eq(0,1);")
        assertEquals("INVALID", state)
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "var 1..2: x; constraint klause_all_different_int([x,1]);",
        "constraint klause_inverse_offsets([1,2],1,[1],1);",
        "constraint klause_circuit([]);",
        "constraint klause_lex_less_int([1],[1,2]);",
        "constraint klause_lex_less_int([],[]);",
        "constraint klause_unknown([1]);",
    ])
    fun `unsupported signatures remain unchanged`(body: String) {
        val original = "$body\nsolve satisfy;"

        val provider = FixedGlobals.providerInput(original)

        assertEquals(original, provider)
    }
}
