package com.eignex.lab.xcsp3cpsat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cases of the former `test_solve.py`, the verdict contract of the command line, and one instance per constraint
 * family the importer handles, whose verdicts and optima are the ones the former CPMpy image produced for the same files.
 */
class SolveTest {
    @TempDir
    Path dir;

    private Path write(String xml) throws IOException {
        Path file = Files.createTempFile(dir, "instance", ".xml");
        Files.writeString(file, xml);
        return file;
    }

    private Map<String, Object> solve(String xml) throws Exception {
        return Main.solve(write(xml).toString(), 1, 1, 3);
    }

    record Run(int exit, String out, String err) {
        List<String> lines() {
            return out.lines().toList();
        }
    }

    private Run run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();
        int exit = Main.run(args, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Long> witness(Map<String, Object> result) {
        return (Map<String, Long>) result.get("witness");
    }

    // ---------------------------------------------------------------- the former test_solve.py

    @ParameterizedTest
    @CsvSource({"2,3,false", "3,2,false", "2,2,false", "2,3,true", "3,2,true", "2,2,true"})
    void matrixSelectionUsesRowMajorCoordinates(int rows, int columns, boolean variableCells) throws Exception {
        StringBuilder matrix = new StringBuilder(), cells = new StringBuilder();
        for (int r = 0; r < rows; r++) {
            matrix.append('(');
            for (int c = 0; c < columns; c++) {
                if (c > 0) matrix.append(',');
                matrix.append(variableCells ? "cells[" + r + "][" + c + "]" : String.valueOf(r * columns + c));
                cells.append("<domain for=\"cells[").append(r).append("][").append(c).append("]\"> ")
                    .append(r * columns + c).append(" </domain>");
            }
            matrix.append(')');
        }
        String array = variableCells ? "<array id=\"cells\" size=\"[" + rows + "][" + columns + "]\">" + cells + "</array>" : "";
        Map<String, Object> result = solve("""
            <instance format="XCSP3" type="CSP">
              <variables>%s<var id="r">%d</var><var id="c">%d</var></variables>
              <constraints><element><matrix>%s</matrix><index>r c</index><value>%d</value></element></constraints>
            </instance>""".formatted(array, rows - 1, columns - 1, matrix, rows * columns - 1));
        assertEquals("FEASIBLE", result.get("exit"));
        assertEquals(Main.VERSION, result.get("version"));
    }

    @ParameterizedTest
    @CsvSource({"-1,2", "0,3", "2,0"})
    void matrixSelectionKeepsAxisBounds(int row, int column) throws Exception {
        Map<String, Object> result = solve("""
            <instance format="XCSP3" type="CSP">
              <variables><var id="r">%d</var><var id="c">%d</var></variables>
              <constraints><element><matrix>(0,0,0)(0,0,0)</matrix><index>r c</index><value>0</value></element></constraints>
            </instance>""".formatted(row, column));
        assertEquals("UNSATISFIABLE", result.get("exit"));
    }

    @Test
    void groupedMatrixSelectionPreservesConditionsAndObjective() throws Exception {
        Map<String, Object> result = solve("""
            <instance format="XCSP3" type="COP">
              <variables><var id="r">1</var><var id="c">0..2</var><var id="v">0..5</var></variables>
              <constraints><group><element><matrix>(0,1,2)(3,4,5)</matrix><index>%0 %1</index>
                <condition>(eq,%2)</condition></element><args>r c v</args></group>
                <element><matrix>(0,1,2)(3,4,5)</matrix><index>r c</index><condition>(ne,4)</condition></element>
              </constraints><objectives><maximize>v</maximize></objectives>
            </instance>""");
        assertEquals("OPTIMAL", result.get("exit"));
        assertEquals(5L, result.get("objective"));
        assertEquals(true, result.get("maximize"));
    }

    @Test
    void negativeTablesPreservePairsAfterSourceDomainExclusions() throws Exception {
        Map<String, Object> result = solve("""
            <instance format="XCSP3" type="COP">
              <variables><var id="x">0..5</var><var id="y">0..5</var></variables>
              <constraints>
                <extension><list>x</list><conflicts>1 3 4</conflicts></extension>
                <extension><list>y</list><conflicts>0 2 3</conflicts></extension>
                <extension><list>x y</list><conflicts>(0,0)(0,2)(1,1)(2,4)(3,3)(4,4)</conflicts></extension>
              </constraints><objectives><maximize type="sum"><list>x y</list><coeffs>1 1</coeffs></maximize></objectives>
            </instance>""");
        assertEquals("OPTIMAL", result.get("exit"));
        assertEquals(Map.of("x", 5L, "y", 5L), witness(result));
        assertEquals("10", result.get("exactObjective"));
    }

    @Test
    void negativeTableWildcardsExcludeOnlyMatchingCoordinates() throws Exception {
        Map<String, Object> result = solve("""
            <instance format="XCSP3" type="CSP">
              <variables><var id="x">0..1</var><var id="y">0..1</var></variables>
              <constraints><extension><list>x y</list><conflicts>(0,*)(1,1)</conflicts></extension></constraints>
            </instance>""");
        assertEquals("FEASIBLE", result.get("exit"));
        assertEquals(Map.of("x", 1L, "y", 0L), witness(result));
    }

    @Test
    void allWildcardConflictsRemainInfeasible() throws Exception {
        Map<String, Object> result = solve("""
            <instance format="XCSP3" type="CSP">
              <variables><var id="x">0..1</var><var id="y">0..1</var></variables>
              <constraints><extension><list>x y</list><conflicts>(*,*)</conflicts></extension></constraints>
            </instance>""");
        assertEquals("UNSATISFIABLE", result.get("exit"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"(0,1)(1,0)|FEASIBLE", "(0,0)(1,1)|UNSATISFIABLE"})
    void repeatedNegativeTableCoordinatesPreserveSourceAliases(String conflicts, String expected) throws Exception {
        Map<String, Object> result = solve("""
            <instance format="XCSP3" type="CSP">
              <variables><var id="x">0..1</var></variables>
              <constraints><extension><list>x x</list><conflicts>%s</conflicts></extension></constraints>
            </instance>""".formatted(conflicts));
        assertEquals(expected, result.get("exit"));
    }

    @Test
    void expressionSumsPreserveTheSourceObjectiveAndWitness() throws Exception {
        Map<String, Object> result = solve("""
            <instance format="XCSP3" type="COP">
              <variables><array id="x" size="[2]">0..2</array></variables>
              <constraints><extension><list>x[]</list><conflicts>(0,1)(1,0)</conflicts></extension>
                <sum><list>eq(x[0],0) eq(x[1],0)</list><coeffs>2 3</coeffs><condition>(ge,2)</condition></sum>
              </constraints><objectives><maximize type="sum"><list>eq(x[0],2) eq(x[1],2)</list>
                <coeffs>5 7</coeffs></maximize></objectives>
            </instance>""");
        assertEquals("OPTIMAL", result.get("exit"));
        assertEquals("7", result.get("exactObjective"));
        assertEquals(Map.of("x[0]", 0L, "x[1]", 2L), witness(result));
    }

    @Test
    void contradictoryExpressionSumsRemainInfeasible() throws Exception {
        Map<String, Object> result = solve("""
            <instance format="XCSP3" type="CSP">
              <variables><var id="x">0..1</var></variables>
              <constraints><sum><list>eq(x,0)</list><condition>(eq,1)</condition></sum>
                <sum><list>eq(x,1)</list><condition>(eq,1)</condition></sum></constraints>
            </instance>""");
        assertEquals("UNSATISFIABLE", result.get("exit"));
        assertNull(result.get("witness"));
    }

    @Test
    void witnessesIncludeUnconstrainedSourceVariables() throws Exception {
        Map<String, Object> result = solve("""
            <instance format="XCSP3" type="CSP">
              <variables><var id="x">1</var><var id="unused">4 9</var></variables>
              <constraints><intension>eq(x,1)</intension></constraints>
            </instance>""");
        assertEquals("FEASIBLE", result.get("exit"));
        assertEquals(1L, witness(result).get("x"));
        assertTrue(List.of(4L, 9L).contains(witness(result).get("unused")));
    }

    @Test
    void objectiveRetainsIntegersBeyondBinary64Precision() throws Exception {
        Map<String, Object> result = solve("""
            <instance format="XCSP3" type="COP">
              <variables><var id="x">0..1</var></variables>
              <objectives><maximize type="sum"><list>x</list><coeffs>9007199254740993</coeffs></maximize></objectives>
            </instance>""");
        assertEquals("OPTIMAL", result.get("exit"));
        assertEquals("9007199254740993", result.get("exactObjective"));
    }

    @ParameterizedTest
    @CsvSource({"minimize,9007199254740992", "maximize,9007199254740993"})
    void unweightedObjectivesPreserveIntegerPrecision(String direction, long expected) throws Exception {
        Map<String, Object> result = solve("""
            <instance format="XCSP3" type="COP">
              <variables><var id="x">9007199254740992..9007199254740993</var><var id="y">0</var></variables>
              <objectives><%1$s type="sum">x y</%1$s></objectives>
            </instance>""".formatted(direction));
        assertEquals("OPTIMAL", result.get("exit"));
        assertEquals(expected, witness(result).get("x"));
        assertEquals(String.valueOf(expected), result.get("exactObjective"));
    }

    // ---------------------------------------------------------------- the command-line contract

    @Test
    void anOptimumPrintsCostWitnessAndVerdictLines() throws Exception {
        Run r = run(write("""
            <instance format="XCSP3" type="COP">
              <variables><var id="x">0..5</var><var id="y">0..5</var></variables>
              <constraints><intension>le(add(x,y),7)</intension><intension>eq(x,5)</intension></constraints>
              <objectives><maximize type="sum"><list>x y</list><coeffs>1 2</coeffs></maximize></objectives>
            </instance>""").toString(), "1", "1", "7");
        assertEquals(0, r.exit());
        List<String> lines = r.lines();
        assertEquals(3, lines.size(), r.out());
        assertEquals("o 9", lines.get(0));
        assertEquals("v <instantiation cost=\"9\"><list>x y</list><values>5 2</values></instantiation>", lines.get(1));
        String verdict = lines.get(2).replaceAll("\"runtime\": [0-9.e+-]+", "\"runtime\": R");
        assertEquals("{\"version\": \"" + Main.VERSION + "\", \"exit\": \"OPTIMAL\", \"runtime\": R, \"objective\": 9, "
            + "\"maximize\": true, \"exactObjective\": \"9\", \"witness\": {\"x\": 5, \"y\": 2}, \"seed\": 7, \"workers\": 1, "
            + "\"cpModelPresolve\": true}", verdict);
    }

    @Test
    void aSatisfactionSolutionPrintsAWitnessWithoutCost() throws Exception {
        Run r = run(write("""
            <instance format="XCSP3" type="CSP">
              <variables><array id="q" size="[2]">0..1</array></variables>
              <constraints><intension>ne(q[0],q[1])</intension><intension>eq(q[0],1)</intension></constraints>
            </instance>""").toString(), "1");
        assertEquals(0, r.exit());
        assertEquals("v <instantiation><list>q[0] q[1]</list><values>1 0</values></instantiation>", r.lines().get(0));
        assertTrue(r.lines().get(1).contains("\"exit\": \"FEASIBLE\", "), r.out());
        assertTrue(r.lines().get(1).contains("\"objective\": null, \"maximize\": null, \"exactObjective\": null, "), r.out());
        assertTrue(r.lines().get(1).endsWith("\"seed\": 3, \"workers\": 1, \"cpModelPresolve\": true}"), r.out());
    }

    @Test
    void infeasibilityPrintsOnlyTheVerdict() throws Exception {
        Run r = run(write("""
            <instance format="XCSP3" type="COP">
              <variables><var id="x">0..3</var></variables>
              <constraints><intension>gt(x,5)</intension></constraints>
              <objectives><minimize>x</minimize></objectives>
            </instance>""").toString(), "1", "1");
        assertEquals(0, r.exit());
        assertEquals(1, r.lines().size(), r.out());
        assertTrue(r.out().contains("\"exit\": \"UNSATISFIABLE\", "));
        assertTrue(r.out().contains("\"objective\": null, \"maximize\": false, \"exactObjective\": null, \"witness\": null, "));
    }

    @Test
    void aRejectedModelIsAnErrorVerdict() throws Exception {
        Run r = run(write("""
            <instance format="XCSP3" type="CSP"><variables><var id="x">0..3</var></variables>
            <constraints><intension>eq(fdiv(x,2),1)</intension></constraints></instance>""").toString(), "1", "1");
        assertEquals(0, r.exit());
        assertEquals(List.of("{\"exit\": \"ERROR\", \"error\": \"KeyError: 'fdiv'\", \"version\": \"" + Main.VERSION + "\"}"), r.lines());
    }

    @Test
    void anUnimplementedConstructExitsWithoutAVerdict() throws Exception {
        Run r = run(write("""
            <instance format="XCSP3" type="CSP"><variables><array id="x" size="[3]">0..2</array></variables>
            <constraints><maximumArg><list>x[]</list><condition>(eq,1)</condition></maximumArg></constraints></instance>""").toString());
        assertEquals(1, r.exit());
        assertEquals("", r.out());
        assertTrue(r.err().contains("EXIT because not implemented method:"), r.err());
    }

    @Test
    void aNonPositiveTimeLimitIsAnErrorVerdict() throws Exception {
        Run r = run(write("<instance format=\"XCSP3\" type=\"CSP\"><variables><var id=\"x\">0..1</var></variables></instance>").toString(), "0");
        assertEquals(0, r.exit());
        assertTrue(r.out().startsWith("{\"exit\": \"ERROR\", \"error\": \"ValueError: Time limit must be positive\""), r.out());
    }

    @Test
    void missingOrBadArgumentsExitWithoutAVerdict() {
        assertEquals(1, run().exit());
        Run bad = run("x.xml", "soon");
        assertEquals(1, bad.exit());
        assertEquals("", bad.out());
    }

    @Test
    void aMalformedFileIsAnErrorVerdict() throws Exception {
        Run r = run(write("<instance format=\"XCSP3\" type=\"CSP\"><variables>").toString(), "1");
        assertEquals(0, r.exit());
        assertTrue(r.out().startsWith("{\"exit\": \"ERROR\", \"error\": \"ParseError: "), r.out());
    }

    @Test
    void floatsPrintAsPythonDoes() {
        assertEquals("0.0072477520000000005", Json.pyFloat(0.0072477520000000005));
        assertEquals("1e-05", Json.pyFloat(0.00001));
        assertEquals("1.5e-07", Json.pyFloat(1.5e-7));
        assertEquals("10.0", Json.pyFloat(10.0));
        assertEquals("5.0009134490000005", Json.pyFloat(5.0009134490000005));
        assertEquals("1e+16", Json.pyFloat(1e16));
        assertEquals("123456789012345.6", Json.pyFloat(123456789012345.6));
    }

    // ---------------------------------------------------------------- constraint families

    private void optimum(long expected, String xml) throws Exception {
        Map<String, Object> result = solve(xml);
        assertEquals("OPTIMAL", result.get("exit"), String.valueOf(result));
        assertEquals(expected, result.get("objective"));
    }

    private void fails(String expectedError, String xml) throws Exception {
        Run r = run(write(xml).toString(), "1", "1");
        assertEquals(0, r.exit(), r.err());
        assertTrue(r.out().startsWith("{\"exit\": \"ERROR\", \"error\": \"" + expectedError), r.out());
    }

    private void unimplemented(String xml) throws Exception {
        Run r = run(write(xml).toString(), "1", "1");
        assertEquals(1, r.exit(), r.out());
        assertEquals("", r.out());
    }

    @Test
    void intensionOperatorsAndPartialFunctions() throws Exception {
        optimum(42, """
            <instance format="XCSP3" type="COP">
              <variables><var id="x">-4..4</var><var id="y">-2..2</var></variables>
              <constraints><intension>or(eq(div(x,y),2),eq(x,-4))</intension><intension>not(eq(mod(x,y),1))</intension></constraints>
              <objectives><maximize>add(mul(x,10),y)</maximize></objectives>
            </instance>""");
        optimum(-1, """
            <instance format="XCSP3" type="COP">
              <variables><var id="x">0..9</var><var id="y">-1..1</var></variables>
              <constraints><intension>ge(add(div(x,y),mod(x,y)),0)</intension></constraints>
              <objectives><minimize>add(x,y)</minimize></objectives>
            </instance>""");
        optimum(11, """
            <instance format="XCSP3" type="COP">
              <variables><array id="x" size="[4]">0..3</array></variables>
              <constraints><intension>eq(x[0],x[1],x[2])</intension><intension>ne(x[1],x[2],x[3])</intension>
              <intension>iff(lt(x[0],2),lt(x[3],1),eq(x[2],x[3]))</intension></constraints>
              <objectives><maximize>add(x[0],x[1],x[2],x[3])</maximize></objectives>
            </instance>""");
        optimum(-9, """
            <instance format="XCSP3" type="COP"><variables><var id="x">-9..9</var><var id="y">-4..-2</var></variables>
            <constraints><intension>eq(mod(x,y),-1)</intension><intension>eq(div(x,y),2)</intension></constraints>
            <objectives><minimize>x</minimize></objectives></instance>""");
        optimum(1, """
            <instance format="XCSP3" type="COP"><variables><var id="x">-3..3</var></variables>
            <constraints><intension>eq(pow(x,3),-8)</intension></constraints><objectives><minimize>pow(x,0)</minimize></objectives></instance>""");
    }

    @Test
    void tablesAndAutomata() throws Exception {
        optimum(10, """
            <instance format="XCSP3" type="COP"><variables><array id="x" size="[3]">0..3</array></variables>
            <constraints><extension><list>x[]</list><supports>(0,1,2)(1,2,3)(3,3,0)(2,0,1)</supports></extension>
            <extension><list>x[0]</list><supports>0 2 3</supports></extension>
            <extension><list>x[1] x[2]</list><supports>(*,0)(1,*)(3,3)</supports></extension></constraints>
            <objectives><maximize type="sum"><list>x[]</list><coeffs>1 2 4</coeffs></maximize></objectives></instance>""");
        optimum(23, """
            <instance format="XCSP3" type="COP"><variables><array id="x" size="[5]">0..1</array><array id="y" size="[3]">0..2</array></variables>
            <constraints><regular><list>x[]</list><transitions>(a,0,a)(a,1,b)(b,1,b)(b,0,c)(c,0,c)</transitions><start>a</start><final>b c</final></regular>
            <mdd><list>y[]</list><transitions>(r,0,n1)(r,1,n2)(n1,2,n3)(n2,1,n3)(n2,2,n4)(n3,0,t)(n4,1,t)</transitions></mdd></constraints>
            <objectives><maximize type="sum"><list>x[] y[]</list><coeffs>1 2 3 4 5 1 2 3</coeffs></maximize></objectives></instance>""");
        Map<String, Object> filtered = solve("""
            <instance format="XCSP3" type="CSP"><variables><var id="x">0..3</var><var id="y">0..3</var></variables>
            <constraints><extension><list>x y</list><supports>(5,5)(6,6)</supports></extension></constraints></instance>""");
        assertEquals("UNSATISFIABLE", filtered.get("exit"));
    }

    @Test
    void comparisonGlobals() throws Exception {
        optimum(10, """
            <instance format="XCSP3" type="COP"><variables><array id="x" size="[4]">0..4</array><array id="y" size="[3]">0..2</array></variables>
            <constraints><allDifferent>x[]</allDifferent>
            <allDifferent><list>y[]</list><except>0</except></allDifferent>
            <allDifferent>add(x[0],y[0]) add(x[1],y[1]) x[2]</allDifferent></constraints>
            <objectives><minimize type="sum"><list>x[] y[]</list><coeffs>1 2 3 4 1 1 1</coeffs></minimize></objectives></instance>""");
        optimum(4, """
            <instance format="XCSP3" type="COP"><variables><array id="x" size="[3][2]">0..1</array></variables>
            <constraints><allDifferent><list>x[0][]</list><list>x[1][]</list><list>x[2][]</list></allDifferent>
            <allDifferent><matrix>(x[0][0],x[0][1])(x[1][0],x[1][1])</matrix></allDifferent></constraints>
            <objectives><maximize type="sum">x[][]</maximize></objectives></instance>""");
        optimum(9, """
            <instance format="XCSP3" type="COP"><variables><array id="x" size="[4]">0..3</array></variables>
            <constraints><allEqual><list>x[]</list><except>0</except></allEqual><intension>ne(x[0],x[3])</intension></constraints>
            <objectives><maximize type="sum">x[]</maximize></objectives></instance>""");
        Map<String, Object> lex = solve("""
            <instance format="XCSP3" type="COP"><variables><array id="x" size="[3][3]">0..4</array><array id="l" size="[2]">1..2</array></variables>
            <constraints><ordered><list>x[0][]</list><operator>lt</operator></ordered>
            <ordered><list>x[1][]</list><lengths>l[]</lengths><operator>le</operator></ordered>
            <lex><list>x[1][]</list><list>x[2][]</list><operator>gt</operator></lex>
            <lex><list>x[0][]</list><list>2 2 2</list><operator>ge</operator></lex>
            <lex><matrix>x[][]</matrix><operator>le</operator></lex></constraints>
            <objectives><minimize type="sum">x[][]</minimize></objectives></instance>""");
        assertEquals("UNSATISFIABLE", lex.get("exit"));
        optimum(5, """
            <instance format="XCSP3" type="COP"><variables><array id="x" size="[5]">0..3</array></variables>
            <constraints><precedence><list>x[]</list></precedence><precedence><list>x[]</list><values>1 3</values></precedence>
            <allDifferent>x[0] x[2] x[4]</allDifferent></constraints>
            <objectives><minimize type="sum"><list>x[]</list><coeffs>5 4 3 2 1</coeffs></minimize></objectives></instance>""");
    }

    @Test
    void countingGlobals() throws Exception {
        optimum(8, """
            <instance format="XCSP3" type="COP"><variables><array id="x" size="[6]">0..4</array><var id="k">0..6</var></variables>
            <constraints><count><list>x[]</list><values>1 2</values><condition>(eq,k)</condition></count>
            <count><list>add(x[0],x[1]) x[2]</list><values>3</values><condition>(ge,1)</condition></count>
            <nValues><list>x[]</list><condition>(in,2..3)</condition></nValues>
            <nValues><list>x[]</list><except>0</except><condition>(le,2)</condition></nValues>
            <sum><list>x[]</list><condition>(notin,5..9)</condition></sum></constraints>
            <objectives><maximize>add(k,x[5])</maximize></objectives></instance>""");
        optimum(8, """
            <instance format="XCSP3" type="COP"><variables><array id="x" size="[6]">0..3</array><array id="o" size="[2]">0..6</array></variables>
            <constraints><cardinality><list>x[]</list><values closed="true">0 1 2</values><occurs>o[0] 1..2 2</occurs></cardinality>
            <cardinality><list>x[0] x[1] x[2]</list><values>1 3</values><occurs>1 o[1]</occurs></cardinality></constraints>
            <objectives><maximize type="sum">o[] x[]</maximize></objectives></instance>""");
        optimum(11, """
            <instance format="XCSP3" type="COP"><variables><array id="x" size="[4]">0..9</array><var id="m">0..9</var></variables>
            <constraints><minimum><list>x[]</list><condition>(eq,m)</condition></minimum>
            <maximum><list>x[] add(m,3)</list><condition>(le,7)</condition></maximum></constraints>
            <objectives><maximize>add(m,x[0])</maximize></objectives></instance>""");
        optimum(16, """
            <instance format="XCSP3" type="COP"><variables><array id="x" size="[4]">0..2</array></variables>
            <constraints><knapsack><list>x[]</list><weights>3 4 5 6</weights><condition>(le,12)</condition><profits>4 5 7 8</profits><condition>(ge,10)</condition></knapsack></constraints>
            <objectives><maximize type="sum"><list>x[]</list><coeffs>4 5 7 8</coeffs></maximize></objectives></instance>""");
        optimum(2, """
            <instance format="XCSP3" type="COP"><variables><array id="x" size="[5]">0..4</array><array id="ld" size="[2]">0..20</array></variables>
            <constraints><binPacking><list>x[]</list><sizes>4 3 3 2 2</sizes><condition>(le,7)</condition></binPacking>
            <binPacking><list>x[]</list><sizes>4 3 3 2 2</sizes><limits>6 6 7</limits></binPacking>
            <binPacking><list>x[0] x[1]</list><sizes>4 3</sizes><loads>ld[]</loads></binPacking></constraints>
            <objectives><minimize type="maximum">x[]</minimize></objectives></instance>""");
    }

    @Test
    void connectionGlobals() throws Exception {
        optimum(29, """
            <instance format="XCSP3" type="COP"><variables><array id="x" size="[4]">0..9</array><var id="i">-1..5</var><var id="v">0..9</var></variables>
            <constraints><element><list>x[]</list><index>i</index><value>v</value></element>
            <element><list>3 1 4 1 5</list><index>i</index><condition>(gt,2)</condition></element>
            <allDifferent>x[]</allDifferent></constraints>
            <objectives><maximize>add(v,mul(i,10))</maximize></objectives></instance>""");
        optimum(52, """
            <instance format="XCSP3" type="COP"><variables><array id="x" size="[4]">0..3</array><array id="y" size="[4]">0..3</array>
            <array id="z" size="[3]">0..4</array><array id="b" size="[3]">0..1</array><var id="v">0..2</var></variables>
            <constraints><channel><list>x[]</list><list>y[]</list></channel><channel>z[]</channel><channel><list>b[]</list><value>v</value></channel></constraints>
            <objectives><maximize type="sum"><list>x[] z[] v</list><coeffs>8 4 2 1 1 2 3 5</coeffs></maximize></objectives></instance>""");
        optimum(12, """
            <instance format="XCSP3" type="COP"><variables><array id="x" size="[2]">0..3</array><array id="y" size="[4]">0..3</array></variables>
            <constraints><channel><list>x[]</list><list>y[]</list></channel></constraints>
            <objectives><maximize type="sum">x[] y[]</maximize></objectives></instance>""");
        optimum(21, """
            <instance format="XCSP3" type="COP"><variables><array id="x" size="[5]">0..4</array></variables>
            <constraints><circuit>x[]</circuit><intension>eq(x[2],2)</intension></constraints>
            <objectives><minimize type="sum"><list>x[]</list><coeffs>1 2 3 4 5</coeffs></minimize></objectives></instance>""");
        Map<String, Object> start = solve("""
            <instance format="XCSP3" type="CSP"><variables><array id="x" size="[3]">0..2</array></variables>
            <constraints><circuit>x[]</circuit><intension>eq(x[0],0)</intension></constraints></instance>""");
        assertEquals("UNSATISFIABLE", start.get("exit"));
    }

    @Test
    void schedulingGlobals() throws Exception {
        optimum(10, """
            <instance format="XCSP3" type="COP"><variables><array id="s" size="[4]">0..10</array><array id="d" size="[2]">0..3</array>
            <array id="p" size="[3][2]">0..4</array><array id="q" size="[3][3]">0..2</array></variables>
            <constraints><noOverlap><origins>s[]</origins><lengths>2 3 1 4</lengths></noOverlap>
            <noOverlap><origins>s[0] s[1]</origins><lengths>d[]</lengths></noOverlap>
            <noOverlap><origins>(p[0][0],p[0][1])(p[1][0],p[1][1])(p[2][0],p[2][1])</origins><lengths>(2,2)(3,1)(1,3)</lengths></noOverlap>
            <noOverlap><origins>(q[0][0],q[0][1],q[0][2])(q[1][0],q[1][1],q[1][2])(q[2][0],q[2][1],q[2][2])</origins><lengths>(1,1,1)(2,1,1)(1,2,1)</lengths></noOverlap></constraints>
            <objectives><minimize>max(add(s[0],2),add(s[1],3),add(s[2],1),add(s[3],4),add(p[0][0],p[1][1],p[2][0]),q[0][0],q[1][2])</minimize></objectives></instance>""");
        optimum(15, """
            <instance format="XCSP3" type="COP"><variables><array id="s" size="[5]">0..20</array><var id="cap">2..4</var></variables>
            <constraints><cumulative><origins>s[]</origins><lengths>3 2 4 2 3</lengths><heights>2 1 2 3 1</heights><condition>(le,cap)</condition></cumulative></constraints>
            <objectives><minimize>add(max(add(s[0],3),add(s[1],2),add(s[2],4),add(s[3],2),add(s[4],3)),mul(cap,2))</minimize></objectives></instance>""");
    }

    @Test
    void constructsTheImporterRejectsFailAsBefore() throws Exception {
        fails("AssertionError: indexes must always be 0 for XCSP3-core", """
            <instance format="XCSP3" type="CSP"><variables><array id="x" size="[4]">0..9</array><var id="i">1..4</var></variables>
            <constraints><element><list startIndex="1">x[]</list><index>i</index><value>3</value></element></constraints></instance>""");
        fails("AssertionError: Expected list as rhs but got 1..2", """
            <instance format="XCSP3" type="CSP"><variables><array id="x" size="[3]">0..2</array></variables>
            <constraints><minimum><list>x[]</list><condition>(in,1..2)</condition></minimum></constraints></instance>""");
        fails("TypeError: Only integer arguments allowed for global constraint Inverse: [x[0], x[1]]", """
            <instance format="XCSP3" type="CSP"><variables><array id="x" size="[2]">0..1</array></variables>
            <constraints><channel>x[]</channel></constraints></instance>""");
        fails("NotImplementedError: ('Decomposition for', IfThenElseNum(", """
            <instance format="XCSP3" type="CSP"><variables><var id="x">0..3</var><var id="y">0..3</var><var id="b">0..1</var></variables>
            <constraints><intension>ge(if(b,x,y),2)</intension></constraints></instance>""");
        fails("AssertionError: For the moment, only mono-objective handled", """
            <instance format="XCSP3" type="COP"><variables><array id="x" size="[2]">0..5</array></variables>
            <objectives><minimize>x[0]</minimize><maximize>x[1]</maximize></objectives></instance>""");
        fails("AssertionError: reified and soft constraints are not handled", """
            <instance format="XCSP3" type="CSP"><variables><array id="x" size="[3]">0..3</array><var id="b">0..1</var></variables>
            <constraints><allDifferent reifiedBy="b">x[]</allDifferent></constraints></instance>""");
        fails("TypeError: Durations should be non-negative", """
            <instance format="XCSP3" type="CSP"><variables><array id="s" size="[2]">0..5</array><array id="d" size="[2]">-1..2</array></variables>
            <constraints><cumulative><origins>s[]</origins><lengths>d[]</lengths><heights>1 1</heights><condition>(le,1)</condition></cumulative></constraints></instance>""");
        unimplemented("""
            <instance format="XCSP3" type="CSP"><variables><var id="c" type="symbolic">red green</var></variables>
            <constraints><intension>eq(c,red)</intension></constraints></instance>""");
        unimplemented("""
            <instance format="XCSP3" type="COP"><variables><array id="x" size="[2]">1..3</array></variables>
            <objectives><maximize type="product">x[]</maximize></objectives></instance>""");
        unimplemented("""
            <instance format="XCSP3" type="CSP"><variables><array id="x" size="[3]">0..2</array></variables>
            <constraints><binPacking><list>x[]</list><sizes>1 2 3</sizes><conditions>(le,3) (le,3) (le,3)</conditions></binPacking></constraints></instance>""");
    }

    @Test
    void theImageSmokeInstanceSolves() throws Exception {
        Path smoke = Path.of(SolveTest.class.getResource("/smoke.xml").toURI());
        Map<String, Object> result = Main.solve(smoke.toString(), 5, 1, 3);
        assertEquals("OPTIMAL", result.get("exit"));
    }
}
