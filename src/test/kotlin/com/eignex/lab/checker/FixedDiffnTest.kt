package com.eignex.lab.checker

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals

class FixedDiffnTest {
    @TempDir
    lateinit var work: Path

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "diffn([0,1],[0,0],[1,1],[1,1])|VALID",
            "diffn([0,0],[0,1],[1,1],[1,1])|VALID",
            "diffn([-2,0],[0,0],[2,1],[1,1])|VALID",
            "diffn([0,0],[0,0],[1,1],[1,1])|INVALID",
            "fzn_diffn([0,1],[0,0],[2,2],[1,1])|INVALID",
            "diffn([0],[0],[1],[1])|VALID",
        ],
    )
    fun `official rectangle API checks fixed non-overlap without auxiliary completion`(call: String, expected: String) {
        val provider = work.resolve("provider.fzn")
        Files.writeString(provider, "constraint $call; solve satisfy;")

        val state = FlatZincGroundCheckerMain.evaluate(provider)

        assertEquals(expected, state)
    }

    @ParameterizedTest
    @ValueSource(strings = ["constraint int_eq(0,1);", "var 1..2: free;"])
    fun `binding named rectangles preserves every other obligation`(other: String) {
        val text = "predicate diffn(array[int] of var int: x,array[int] of var int: y," +
            "array[int] of int: w,array[int] of int: h);\n" +
            "array[1..2] of var int: x :: var_is_introduced = [0,1];\n" +
            "array[1..2] of int: y = [0,0];\narray[1..2] of int: size = [1,1];\n" +
            "constraint diffn(x,y,size,size);\n$other\nsolve satisfy;"
        val provider = work.resolve("provider.fzn")
        Files.writeString(provider, text)

        val state = FlatZincGroundCheckerMain.evaluate(provider)

        assertEquals(if (other.startsWith("var")) "UNKNOWN" else "INVALID", state)
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "var 0..1: x; constraint diffn([x],[0],[1],[1]);",
        "constraint diffn([0,1],[0],[1],[1]);",
        "constraint diffn([0],[0],[0],[1]);",
        "constraint fzn_diffn([0],[0],[0],[1]);",
        "constraint fzn_diffn([],[],[],[]);",
        "constraint diffn([0],[0],[-1],[1]);",
        "constraint diffn([2147483647],[0],[1],[1]);",
        "constraint diffn_nonstrict([0],[0],[1],[1]);",
    ])
    fun `unsupported rectangle forms remain unknown`(body: String) {
        val provider = work.resolve("provider.fzn")
        Files.writeString(provider, "$body\nsolve satisfy;")

        val state = FlatZincGroundCheckerMain.evaluate(provider)

        assertEquals("UNKNOWN", state)
    }
}
