package com.eignex.lab.xcsp3cpsat;

import com.google.ortools.sat.CircuitConstraint;
import com.google.ortools.sat.CumulativeConstraint;
import com.google.ortools.sat.IntVar;
import com.google.ortools.sat.IntervalVar;
import com.google.ortools.sat.LinearArgument;
import com.google.ortools.sat.Literal;
import com.google.ortools.sat.AutomatonConstraint;
import com.google.ortools.util.Domain;
import org.xcsp.common.Condition;
import org.xcsp.common.Condition.ConditionIntset;
import org.xcsp.common.Condition.ConditionIntvl;
import org.xcsp.common.Condition.ConditionRel;
import org.xcsp.common.Condition.ConditionVal;
import org.xcsp.common.Condition.ConditionVar;
import org.xcsp.common.Constants;
import org.xcsp.common.Types.TypeAtt;
import org.xcsp.common.Types.TypeChild;
import org.xcsp.common.Types.TypeConditionOperatorRel;
import org.xcsp.common.Types.TypeConditionOperatorSet;
import org.xcsp.common.Types.TypeCtr;
import org.xcsp.common.Types.TypeExpr;
import org.xcsp.common.Types.TypeFlag;
import org.xcsp.common.Types.TypeObjective;
import org.xcsp.common.Types.TypeOperator;
import org.xcsp.common.domains.Domains.Dom;
import org.xcsp.common.domains.Values.IntegerEntity;
import org.xcsp.common.domains.Values.IntegerInterval;
import org.xcsp.common.domains.Values.IntegerValue;
import org.xcsp.common.domains.Values.Occurrences;
import org.xcsp.common.predicates.XNode;
import org.xcsp.common.predicates.XNodeLeaf;
import org.xcsp.common.predicates.XNodeParent;
import org.xcsp.common.structures.AbstractTuple;
import org.xcsp.common.structures.Transition;
import org.xcsp.parser.XParser;
import org.xcsp.parser.entries.ParsingEntry.CEntry;
import org.xcsp.parser.entries.ParsingEntry.OEntry;
import org.xcsp.parser.entries.ParsingEntry.VEntry;
import org.xcsp.parser.entries.XConstraints.CChild;
import org.xcsp.parser.entries.XConstraints.CEntryReifiable;
import org.xcsp.parser.entries.XConstraints.XBlock;
import org.xcsp.parser.entries.XConstraints.XCtr;
import org.xcsp.parser.entries.XConstraints.XGroup;
import org.xcsp.parser.entries.XConstraints.XSlide;
import org.xcsp.parser.entries.XObjectives.OObjectiveExpr;
import org.xcsp.parser.entries.XObjectives.OObjectiveSpecial;
import org.xcsp.parser.entries.XVariables.XArray;
import org.xcsp.parser.entries.XVariables.XVar;
import org.xcsp.parser.entries.XVariables.XVarInteger;
import org.xcsp.parser.entries.XVariables.XVarSymbolic;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Loads a parsed XCSP3 instance into CP-SAT. The dispatch follows PyCSP3's `CallbackerXCSP3.load_ctr` and each
 * constraint follows the CPMpy callback the reference image used before (`CallbacksCPMPy` with the source-preserving
 * overrides of the former `solve.py`): what that importer rejected is rejected here, with the same kind of failure.
 */
final class Loader {
    final Builder b;
    final Expr e;
    /** The objective that the last objective entry set, as CPMpy's `Model.minimize` / `maximize` replace each other. */
    Lin objective;
    boolean minimize;

    Loader(Builder b) {
        this.b = b;
        this.e = new Expr(b);
    }

    void load(XParser parser) {
        for (VEntry entry : parser.vEntries) {
            if (entry instanceof XArray array) {
                for (XVar x : array.vars) if (x != null) variable(x);
            } else variable((XVar) entry);
        }
        constraints(parser.cEntries);
        for (OEntry entry : parser.oEntries) objective(entry);
    }

    // ---------------------------------------------------------------- variables

    private void variable(XVar x) {
        if (x instanceof XVarSymbolic) throw new Failure.Unimplemented("var_symbolic");
        if (!(x instanceof XVarInteger) || !(x.dom instanceof Dom dom))
            throw Failure.assertion("unsupported variable type for " + x.id);
        Object[] pieces = dom.values;
        if (pieces.length == 0) throw Failure.assertion("empty domain for " + x.id);
        long[][] intervals = new long[pieces.length][];
        for (int i = 0; i < pieces.length; i++) {
            Object p = pieces[i];
            if (p instanceof IntegerValue v) intervals[i] = new long[]{v.v, v.v};
            else if (p instanceof IntegerInterval ii) {
                if (ii.inf == Constants.MINUS_INFINITY || ii.sup == Constants.PLUS_INFINITY)
                    throw Failure.value("infinite domain for " + x.id);
                intervals[i] = new long[]{ii.inf, ii.sup};
            } else throw Failure.assertion("unsupported domain piece " + p);
        }
        e.declare(x.id, Domain.fromIntervals(intervals));
    }

    // ---------------------------------------------------------------- raw child values

    private static List<Object> items(Object value) {
        List<Object> out = new ArrayList<>();
        if (value == null) return out;
        if (!value.getClass().isArray()) {
            out.add(value);
            return out;
        }
        for (int i = 0; i < Array.getLength(value); i++) out.add(Array.get(value, i));
        return out;
    }

    /** Integer values of a child, unrolling ranges and repeated values. */
    private static List<Long> ints(Object value) {
        List<Long> out = new ArrayList<>();
        for (Object o : items(value)) {
            if (o instanceof Long l) out.add(l);
            else if (o instanceof Integer i) out.add((long) i);
            else if (o instanceof IntegerValue v) out.add(v.v);
            else if (o instanceof IntegerInterval ii) for (long v = ii.inf; v <= ii.sup; v++) out.add(v);
            else if (o instanceof Occurrences oc) for (long k = 0; k < oc.nOccurrences; k++) out.add((Long) oc.value);
            else throw Failure.type("expected an integer but got " + o);
        }
        return out;
    }

    /** `get_cpm_vars`: variables and constants, never expressions. */
    private List<Lin> vars(Object value) {
        List<Lin> out = new ArrayList<>();
        for (Object o : items(value)) {
            if (o instanceof XVar x) out.add(e.source(x).lin());
            else if (o instanceof Long l) out.add(Lin.constant(l));
            else if (o instanceof IntegerInterval ii) for (long v = ii.inf; v <= ii.sup; v++) out.add(Lin.constant(v));
            else throw Failure.key(String.valueOf(o));
        }
        return out;
    }

    private Lin var(Object o) {
        if (o instanceof XVar x) return e.source(x).lin();
        if (o instanceof Long l) return Lin.constant(l);
        throw Failure.key(String.valueOf(o));
    }

    /** `get_cpm_exprs`: variables, constants and expression trees. */
    private List<Lin> exprs(Object value, List<Literal> guards) {
        List<Lin> out = new ArrayList<>();
        for (Object o : items(value)) {
            if (o instanceof XVar x) out.add(e.source(x).lin());
            else if (o instanceof Long l) out.add(Lin.constant(l));
            else if (o instanceof XNode<?> n) out.add(e.num(n, guards));
            else if (o instanceof IntegerInterval ii) out.add(Lin.of(b.intVar(ii.inf, ii.sup), ii.inf, ii.sup));
            else throw Failure.type("unexpected list element " + o);
        }
        return out;
    }

    private static List<Lin> constants(List<Long> values) {
        return values.stream().map(Lin::constant).toList();
    }

    private static List<List<Lin>> rows(Object matrix, java.util.function.Function<Object, List<Lin>> row) {
        List<List<Lin>> out = new ArrayList<>();
        for (Object r : items(matrix)) out.add(row.apply(r));
        return out;
    }

    /** Integer tuples with [STAR] where the source has `*`. */
    static final long STAR = Long.MIN_VALUE;

    private static long[][] tuples(Object value) {
        if (value instanceof AbstractTuple[]) throw Failure.assertion("For the moment, hybrid tuples are not handled");
        List<Object> rows = items(value);
        long[][] out = new long[rows.size()][];
        for (int i = 0; i < out.length; i++) {
            Object row = rows.get(i);
            int n = Array.getLength(row);
            out[i] = new long[n];
            for (int j = 0; j < n; j++) {
                Object v = Array.get(row, j);
                long x;
                boolean star;
                if (v instanceof Byte y) {
                    x = y;
                    star = y == Constants.STAR_BYTE;
                } else if (v instanceof Short s) {
                    x = s;
                    star = s == Constants.STAR_SHORT;
                } else if (v instanceof Integer k) {
                    x = k;
                    star = k == Constants.STAR_INT;
                } else {
                    x = (Long) v;
                    star = x == Constants.STAR_LONG;
                }
                out[i][j] = star ? STAR : x;
            }
        }
        return out;
    }

    private static boolean has(CChild[] childs, int i, TypeChild type) {
        return i < childs.length && childs[i].type == type;
    }

    private static void check(boolean condition) {
        if (!condition) throw Failure.assertion("");
    }

    private static void startsAtZero(CChild child) {
        if (child.getAttributeValue(TypeAtt.startIndex, 0) != 0)
            throw Failure.assertion("indexes must always be 0 for XCSP3-core");
    }

    // ---------------------------------------------------------------- conditions

    private static Builder.Rel rel(TypeConditionOperatorRel op) {
        return Builder.Rel.valueOf(op.name());
    }

    private Lin operand(ConditionRel c) {
        if (c instanceof ConditionVal v) return Lin.constant(v.k);
        if (c instanceof ConditionVar v) return e.source((XVar) v.x).lin();
        throw Failure.assertion("unsupported condition " + c);
    }

    /** `eval_cpm_comp(lhs, op, rhs)` with an integer or variable right operand. Set conditions have no list there. */
    private void compare(Lin lhs, Condition c) {
        if (!(c instanceof ConditionRel r))
            throw Failure.assertion("Expected list as rhs but got " + setText(c));
        b.postRel(rel(r.operator), lhs, operand(r));
    }

    private Literal compared(Lin lhs, Condition c) {
        if (!(c instanceof ConditionRel r))
            throw Failure.assertion("Expected list as rhs but got " + setText(c));
        return b.rel(rel(r.operator), lhs, operand(r));
    }

    private static String setText(Condition c) {
        if (c instanceof ConditionIntvl i) return i.min + ".." + i.max;
        if (c instanceof ConditionIntset s)
            return "{" + String.join(",", Arrays.stream(s.t).mapToObj(String::valueOf).toList()) + "}";
        return String.valueOf(c);
    }

    /** The comparison of sums, counts and nValues, which also take an interval or a set of values. */
    private void compareOrIn(Lin lhs, Condition c) {
        if (c instanceof ConditionRel) {
            compare(lhs, c);
            return;
        }
        Domain d;
        TypeConditionOperatorSet op;
        if (c instanceof ConditionIntvl i) {
            d = i.min > i.max ? Domain.fromValues(new long[0]) : new Domain(i.min, i.max);
            op = i.operator;
        } else if (c instanceof ConditionIntset s) {
            d = Domain.fromValues(Arrays.stream(s.t).asLongStream().toArray());
            op = s.operator;
        } else throw Failure.assertion("Competition only supports intervals when operator is `in` or `notin`");
        b.postInDomain(lhs, op == TypeConditionOperatorSet.IN ? d : d.complement());
    }

    // ---------------------------------------------------------------- constraints

    private void constraints(List<CEntry> entries) {
        for (CEntry entry : entries) {
            if (entry instanceof XBlock block) constraints(block.subentries);
            else if (entry instanceof XGroup group) templated(group.template, group.argss);
            else if (entry instanceof XSlide slide) templated(slide.template, slide.scopes);
            else if (entry instanceof XCtr c) constraint(c);
            else throw Failure.assertion("unsupported constraint entry " + entry.getClass().getSimpleName());
        }
    }

    private void templated(CEntryReifiable template, Object[][] argss) {
        if (!(template instanceof XCtr c)) throw Failure.assertion("unsupported template");
        for (Object[] args : argss) {
            c.id = null;
            c.abstraction.concretize(args);
            constraint(c);
        }
    }

    private void constraint(XCtr c) {
        if (c.reification != null || c.softening != null)
            throw Failure.assertion("reified and soft constraints are not handled");
        XVar[] involved = c.vars();
        if (!Arrays.stream(involved).allMatch(x -> x instanceof XVarInteger)) {
            if (!Arrays.stream(involved).allMatch(x -> x instanceof XVarSymbolic)) throw Failure.assertion("");
            switch (c.type) {
                case intension, extension, allDifferent, instantiation ->
                    throw new Failure.Unimplemented("ctr_" + c.type + "_symbolic");
                default -> throw Failure.assertion("not implemented");
            }
        }
        List<Literal> guards = new ArrayList<>();
        CChild[] ch = c.childs;
        switch (c.type) {
            case intension -> {
                check(ch.length == 1 && ch[0].type == TypeChild.function);
                e.post(((XNode<?>) ch[0].value).canonization());
            }
            case extension -> extension(ch);
            case regular -> {
                check(ch.length == 4);
                regular(vars(ch[0].value), (Transition[]) ch[1].value, (String) ch[2].value, (String[]) ch[3].value);
            }
            case mdd -> {
                check(ch.length == 2);
                mdd(vars(ch[0].value), (Transition[]) ch[1].value);
            }
            case allDifferent -> allDifferent(ch, guards);
            case allEqual -> {
                check(ch.length >= 1 && ch[0].type == TypeChild.list && (ch.length == 1 || ch.length == 2 && has(ch, 1, TypeChild.except)));
                List<Lin> xs = exprs(ch[0].value, guards);
                if (ch.length == 1) b.post(e.allEqual(xs));
                else allEqualExcept(xs, ints(ch[1].value));
            }
            case ordered -> ordered(ch);
            case lex -> lex(ch);
            case precedence -> precedence(ch);
            case sum -> sum(ch, guards);
            case count -> {
                check(ch.length == 3 && ch[0].type == TypeChild.list && ch[1].type == TypeChild.values && ch[2].value instanceof Condition);
                List<Lin> xs = exprs(ch[0].value, guards);
                List<Literal> hits = new ArrayList<>();
                for (Lin v : vars(ch[1].value)) for (Lin x : xs) hits.add(b.rel(Builder.Rel.EQ, x, v));
                compareOrIn(b.count(hits), (Condition) ch[2].value);
            }
            case nValues -> {
                check(ch[0].type == TypeChild.list && ch[ch.length - 1].value instanceof Condition
                    && (ch.length == 2 || ch.length == 3 && ch[1].type == TypeChild.except));
                List<Lin> xs = exprs(ch[0].value, guards);
                Long except = null;
                if (ch.length == 3) {
                    List<Long> values = ints(ch[1].value);
                    if (values.size() != 1) throw Failure.assertion("Competition only allows 1 integer value in excepting list");
                    except = values.get(0);
                }
                compareOrIn(nValues(xs, except), (Condition) ch[ch.length - 1].value);
            }
            case cardinality -> cardinality(ch, guards);
            case minimum, maximum -> {
                check(ch.length == 2 && ch[0].type == TypeChild.list && ch[1].type == TypeChild.condition);
                compare(b.extremum(exprs(ch[0].value, guards), c.type == TypeCtr.maximum), (Condition) ch[1].value);
            }
            case minimumArg, maximumArg -> {
                check(ch.length == 2 && ch[0].type == TypeChild.list && ch[1].type == TypeChild.condition);
                throw new Failure.Unimplemented("ctr_" + (c.type == TypeCtr.minimumArg ? "minimum" : "maximum") + "_arg");
            }
            case element -> element(ch);
            case channel -> channel(ch);
            case stretch -> throw Failure.assertion("not implemented for the moment (and not in XCSP3-core)");
            case noOverlap -> noOverlap(ch);
            case cumulative -> cumulative(ch, guards);
            case binPacking -> binPacking(ch);
            case knapsack -> {
                check(ch.length == 5);
                List<Lin> xs = vars(ch[0].value);
                List<Long> weights = ints(ch[1].value), profits = ints(ch[3].value);
                compare(weighted(xs, weights), (Condition) ch[2].value);
                compare(weighted(xs, profits), (Condition) ch[4].value);
            }
            case flow -> {
                check(ch.length >= 3 && ch[0].type == TypeChild.list && ch[1].type == TypeChild.balance && ch[2].type == TypeChild.arcs);
                throw new Failure.Unimplemented(ch.length <= 4 ? "ctr_flow" : "ctr_flow_weighted");
            }
            case instantiation -> {
                check(ch.length == 2 && ch[0].type == TypeChild.list && ch[1].type == TypeChild.values);
                List<Lin> xs = vars(ch[0].value);
                List<Long> values = ints(ch[1].value);
                if (xs.size() != values.size()) throw Failure.value("Tuple " + values + " has the wrong arity");
                b.model.addAllowedAssignments(xs.stream().map(b::affine).toArray(LinearArgument[]::new))
                    .addTuple(values.stream().mapToLong(Long::longValue).toArray());
            }
            case clause -> {
                check(ch.length == 1 && ch[0].type == TypeChild.list);
                throw new Failure.Unimplemented("ctr_clause");
            }
            case circuit -> {
                check(ch.length == 1 || ch.length == 2 && ch[1].type == TypeChild.size);
                startsAtZero(ch[0]);
                circuit(vars(ch[0].value));
            }
            case adhoc -> throw Failure.assertion("not implemented for the moment (and not in XCSP3-core)");
            default -> throw Failure.assertion("not implemented");
        }
        b.postAll(guards);
    }

    private Lin weighted(List<Lin> xs, List<Long> coefs) {
        Lin s = Lin.constant(0);
        for (int i = 0; i < xs.size(); i++) s = s.plus(xs.get(i).times(coefs.get(broadcast(i, coefs.size(), xs.size()))));
        return s;
    }

    /** Index into a coefficient array under numpy broadcasting, which accepts one coefficient for every term. */
    private static int broadcast(int i, int coefs, int terms) {
        if (coefs == terms) return i;
        if (coefs == 1) return 0;
        throw Failure.value("operands could not be broadcast together with shapes (" + coefs + ",) (" + terms + ",)");
    }

    private void extension(CChild[] ch) {
        check(ch.length == 2 && ch[0].type == TypeChild.list && (ch[1].type == TypeChild.supports || ch[1].type == TypeChild.conflicts));
        boolean positive = ch[1].type == TypeChild.supports;
        Object table = ch[1].value;
        List<Lin> xs = vars(ch[0].value);
        // PyCSP3's parser fails on an empty tuple element. The XCSP3 parser also drops tuples outside the domains, which
        // PyCSP3 kept: no remaining support is unsatisfiable and no remaining conflict excludes nothing.
        if (table == null) throw new Failure("AttributeError", "'NoneType' object has no attribute 'strip'");
        if (Array.getLength(table) == 0) {
            if (positive) b.post(b.FALSE);
            return;
        }
        if (xs.size() == 1) {
            Domain values = Builder.values(ints(table));
            b.postInDomain(xs.get(0), positive ? values : values.complement());
            return;
        }
        long[][] tuples = tuples(table);
        boolean starred = ch[1].flags.contains(TypeFlag.STARRED_TUPLES);
        for (long[] t : tuples) if (t.length != xs.size()) throw Failure.value("conflict tuple arity differs from its source scope");
        if (positive && !starred) {
            b.model.addAllowedAssignments(xs.stream().map(b::affine).toArray(LinearArgument[]::new)).addTuples(tuples);
        } else if (positive) {
            List<Literal> rows = new ArrayList<>();
            for (long[] t : tuples) {
                List<Literal> eqs = new ArrayList<>();
                for (int i = 0; i < t.length; i++) if (t[i] != STAR) eqs.add(b.eq(xs.get(i), t[i]));
                rows.add(b.and(eqs));
            }
            b.postOr(rows);
        } else {
            // A conflict excludes only its matching tuple, including wildcard coordinates.
            for (long[] t : tuples) {
                List<Literal> differ = new ArrayList<>();
                for (int i = 0; i < t.length; i++) if (t[i] != STAR) differ.add(b.not(b.eq(xs.get(i), t[i])));
                b.postOr(differ);
            }
        }
    }

    private void regular(List<Lin> xs, Transition[] transitions, String start, String[] finals) {
        TreeSet<String> nodes = new TreeSet<>();
        for (Transition t : transitions) {
            if (!(t.value instanceof Long)) throw Failure.type("The second argument of a regular constraint should be a list of transitions");
            nodes.add(t.start);
            nodes.add(t.end);
        }
        Map<String, Integer> index = new HashMap<>();
        for (String n : nodes) index.put(n, index.size());
        if (!index.containsKey(start)) throw Failure.key(start);
        long[] accepting = new long[finals.length];
        for (int i = 0; i < finals.length; i++) {
            Integer f = index.get(finals[i]);
            if (f == null) throw Failure.key(finals[i]);
            accepting[i] = f;
        }
        automaton(xs, index.get(start), accepting,
            Arrays.stream(transitions).map(t -> new long[]{index.get(t.start), (Long) t.value, index.get(t.end)}).toList());
    }

    private void automaton(List<Lin> xs, long start, long[] accepting, List<long[]> transitions) {
        if (xs.isEmpty()) throw Failure.value("add_automaton expects a non-empty transition_expressions array");
        if (accepting.length == 0) throw Failure.value("add_automaton expects some final states");
        if (transitions.isEmpty()) throw Failure.value("add_automaton expects some transition triples");
        AutomatonConstraint a = b.model.addAutomaton(xs.stream().map(b::affine).toArray(LinearArgument[]::new), start, accepting);
        for (long[] t : transitions) a.addTransition((int) t[0], (int) t[2], t[1]);
    }

    /** CPMpy's MDD: the root is the source of the first transition and the single node on the last level is the sink. */
    private void mdd(List<Lin> xs, Transition[] transitions) {
        Map<String, Map<Long, String>> mapping = new java.util.LinkedHashMap<>();
        for (Transition t : transitions) {
            if (!(t.value instanceof Long)) throw Failure.type("The second argument of an MDD constraint should be a list of transitions");
            mapping.computeIfAbsent(t.start, k -> new java.util.LinkedHashMap<>()).put((Long) t.value, t.end);
        }
        String root = transitions[0].start;
        Map<String, Integer> levels = new java.util.LinkedHashMap<>();
        levels.put(root, 0);
        List<String> current = List.of(root);
        for (int level = 0; level < xs.size(); level++) {
            java.util.LinkedHashSet<String> next = new java.util.LinkedHashSet<>();
            for (String node : current)
                for (String dst : mapping.computeIfAbsent(node, k -> new java.util.LinkedHashMap<>()).values()) {
                    next.add(dst);
                    levels.put(dst, level + 1);
                }
            current = new ArrayList<>(next);
        }
        List<String> sinks = levels.entrySet().stream().filter(x -> x.getValue() == xs.size()).map(Map.Entry::getKey).toList();
        if (sinks.size() != 1) throw Failure.assertion("");
        Map<String, Integer> index = new HashMap<>();
        for (String n : levels.keySet()) index.put(n, index.size());
        List<long[]> arcs = new ArrayList<>();
        for (Map.Entry<String, Map<Long, String>> src : mapping.entrySet()) {
            Integer from = index.get(src.getKey());
            if (from == null) throw Failure.key(src.getKey());
            for (Map.Entry<Long, String> edge : src.getValue().entrySet()) {
                Integer to = index.get(edge.getValue());
                if (to == null) throw Failure.key(edge.getValue());
                arcs.add(new long[]{from, edge.getKey(), to});
            }
        }
        automaton(xs, index.get(root), new long[]{index.get(sinks.get(0))}, arcs);
    }

    private void allDifferent(CChild[] ch, List<Literal> guards) {
        check(ch.length > 0);
        List<Long> excepting = ch[ch.length - 1].type == TypeChild.except ? ints(ch[ch.length - 1].value) : null;
        if (ch[0].type == TypeChild.matrix) {
            check(ch.length == 1);
            List<List<Lin>> m = rows(ch[0].value, this::vars);
            for (List<Lin> row : m) allDifferent(row, null);
            for (List<Lin> col : transpose(m)) allDifferent(col, null);
            return;
        }
        for (int i = 0; i < ch.length - (excepting == null ? 0 : 1); i++) check(ch[i].type == TypeChild.list);
        if (ch.length > 1 && ch[1].type == TypeChild.list) {
            List<List<Lin>> lists = new ArrayList<>();
            for (CChild c : ch) if (c.type == TypeChild.list) lists.add(vars(c.value));
            for (List<Lin> l : lists)
                if (l.size() != lists.get(0).size()) throw Failure.value("Lists should have equal length");
            if (excepting == null) {
                for (int i = 0; i < lists.size(); i++)
                    for (int j = i + 1; j < lists.size(); j++) {
                        List<Literal> differ = new ArrayList<>();
                        for (int k = 0; k < lists.get(i).size(); k++)
                            differ.add(b.rel(Builder.Rel.NE, lists.get(i).get(k), lists.get(j).get(k)));
                        b.postOr(differ);
                    }
            } else {
                long[][] allowed = tuples(ch[ch.length - 1].value instanceof Object[] o && o.length > 0 && !o[0].getClass().isArray()
                    ? new Object[]{ch[ch.length - 1].value} : ch[ch.length - 1].value);
                for (long[] t : allowed)
                    if (t.length != lists.get(0).size()) throw Failure.value("Lists should have equal length");
                for (int i = 0; i < lists.size(); i++)
                    for (int j = i + 1; j < lists.size(); j++) {
                        List<Literal> same = new ArrayList<>();
                        for (int k = 0; k < lists.get(i).size(); k++)
                            same.add(b.rel(Builder.Rel.EQ, lists.get(i).get(k), lists.get(j).get(k)));
                        List<Literal> rowsHit = new ArrayList<>();
                        for (long[] t : allowed) {
                            List<Literal> eqs = new ArrayList<>();
                            for (int k = 0; k < t.length; k++) eqs.add(b.eq(lists.get(i).get(k), t[k]));
                            rowsHit.add(b.and(eqs));
                        }
                        b.postOr(List.of(b.not(b.and(same)), b.or(rowsHit)));
                    }
            }
            return;
        }
        allDifferent(exprs(ch[0].value, guards), excepting);
    }

    private void allDifferent(List<Lin> xs, List<Long> excepting) {
        if (excepting == null || excepting.isEmpty()) {
            b.model.addAllDifferent(xs.stream().map(b::affine).toArray(LinearArgument[]::new));
            return;
        }
        Domain except = Builder.values(excepting);
        for (int i = 0; i < xs.size(); i++)
            for (int j = i + 1; j < xs.size(); j++)
                b.postOr(List.of(b.rel(Builder.Rel.NE, xs.get(i), xs.get(j)), b.inDomain(xs.get(i), except)));
    }

    private void allEqualExcept(List<Lin> xs, List<Long> excepting) {
        Domain except = Builder.values(excepting);
        for (int i = 0; i < xs.size(); i++)
            for (int j = i + 1; j < xs.size(); j++)
                b.postOr(List.of(b.inDomain(xs.get(i), except), b.rel(Builder.Rel.EQ, xs.get(i), xs.get(j)),
                    b.inDomain(xs.get(j), except)));
    }

    private static <T> List<List<T>> transpose(List<List<T>> m) {
        List<List<T>> out = new ArrayList<>();
        if (m.isEmpty()) return out;
        int n = m.get(0).size();
        for (List<T> row : m) if (row.size() != n) throw Failure.value("setting an array element with a sequence: inhomogeneous shape");
        for (int j = 0; j < n; j++) {
            List<T> col = new ArrayList<>();
            for (List<T> row : m) col.add(row.get(j));
            out.add(col);
        }
        return out;
    }

    private static Builder.Rel ordering(Object operator) {
        if (!(operator instanceof TypeOperator op)) throw Failure.value("");
        return switch (op) {
            case LT -> Builder.Rel.LT;
            case LE -> Builder.Rel.LE;
            case GE -> Builder.Rel.GE;
            case GT -> Builder.Rel.GT;
            default -> throw Failure.value("");
        };
    }

    private void ordered(CChild[] ch) {
        check(ch[0].type == TypeChild.list && (ch.length == 2 || ch.length == 3 && ch[1].type == TypeChild.lengths)
            && ch[ch.length - 1].type == TypeChild.operator);
        Builder.Rel op = ordering(ch[ch.length - 1].value);
        List<Lin> xs = vars(ch[0].value);
        List<Lin> lengths = ch.length == 3 ? vars(ch[1].value) : null;
        for (int i = 0; i + 1 < xs.size(); i++) {
            if (lengths != null && i >= lengths.size()) break;
            Lin x = lengths == null ? xs.get(i) : xs.get(i).plus(lengths.get(i));
            b.postRel(op, x, xs.get(i + 1));
        }
    }

    private void lex(CChild[] ch) {
        check(ch.length >= 2 && ch[ch.length - 1].type == TypeChild.operator);
        Builder.Rel op = ordering(ch[ch.length - 1].value);
        if (ch[0].type == TypeChild.matrix) {
            check(ch.length == 2);
            List<List<Lin>> m = rows(ch[0].value, this::vars);
            lexChain(m, op);
            lexChain(transpose(m), op);
            return;
        }
        List<List<Lin>> lists = new ArrayList<>();
        for (int i = 0; i < ch.length - 1; i++) {
            check(ch[i].type == TypeChild.list);
            lists.add(vars(ch[i].value));
        }
        lexChain(lists, op);
    }

    private void lexChain(List<List<Lin>> lists, Builder.Rel op) {
        transpose(lists);
        boolean strict = op == Builder.Rel.LT || op == Builder.Rel.GT;
        List<List<Lin>> chain = new ArrayList<>(lists);
        if (op == Builder.Rel.GT || op == Builder.Rel.GE) java.util.Collections.reverse(chain);
        for (int i = 0; i + 1 < chain.size(); i++) b.post(lexLess(chain.get(i), chain.get(i + 1), strict));
    }

    /** CPMpy's LexLess / LexLessEq: x before y, all pairs comparing left to right. */
    private Literal lexLess(List<Lin> x, List<Lin> y, boolean strict) {
        int n = x.size();
        if (n == 0) return b.FALSE;
        Literal r = b.rel(strict ? Builder.Rel.LT : Builder.Rel.LE, x.get(n - 1), y.get(n - 1));
        for (int i = n - 2; i >= 0; i--) {
            Literal le = b.rel(Builder.Rel.LE, x.get(i), y.get(i)), lt = b.rel(Builder.Rel.LT, x.get(i), y.get(i));
            r = b.and(List.of(le, b.or(List.of(lt, r))));
        }
        return r;
    }

    private void precedence(CChild[] ch) {
        check(ch[0].type == TypeChild.list && (ch.length == 1 || ch.length == 2 && ch[1].type == TypeChild.values));
        if (ch.length == 2 && ch[1].getAttributeValue(TypeAtt.covered, false)) throw new Failure.Unimplemented("ctr_precedence");
        List<Lin> xs = vars(ch[0].value);
        List<Long> values;
        if (ch.length == 2) values = ints(ch[1].value);
        else {
            TreeSet<Long> all = new TreeSet<>();
            for (Lin x : xs) for (long v = x.lb; v <= x.ub; v++) all.add(v);
            values = new ArrayList<>(all);
        }
        for (int k = 0; k + 1 < values.size(); k++) {
            long s = values.get(k), t = values.get(k + 1);
            b.post(b.not(b.eq(xs.get(0), t)));
            for (int j = 1; j < xs.size(); j++) {
                List<Literal> before = new ArrayList<>();
                for (int i = 0; i < j; i++) before.add(b.eq(xs.get(i), s));
                b.postOr(List.of(b.not(b.eq(xs.get(j), t)), b.or(before)));
            }
        }
    }

    private void sum(CChild[] ch, List<Literal> guards) {
        check(ch[0].type == TypeChild.list && ch[ch.length - 1].value instanceof Condition
            && (ch.length == 2 || ch.length == 3 && ch[1].type == TypeChild.coeffs));
        List<Lin> xs = exprs(ch[0].value, guards);
        Lin s = Lin.constant(0);
        if (ch.length == 2) s = b.sum(xs);
        else {
            List<Object> coefs = items(ch[1].value);
            if (coefs.isEmpty()) s = b.sum(xs);
            else if (coefs.get(0) instanceof XVar) {
                List<Lin> cs = vars(ch[1].value);
                for (int i = 0; i < xs.size(); i++) s = s.plus(b.mul(cs.get(broadcast(i, cs.size(), xs.size())), xs.get(i)));
            } else s = weighted(xs, ints(ch[1].value));
        }
        compareOrIn(s, (Condition) ch[ch.length - 1].value);
    }

    /** CPMpy's NValue (and NValueExcept): one "some term takes v" literal per value v within the terms' bounds. */
    Lin nValues(List<Lin> xs, Long except) {
        if (xs.isEmpty()) return Lin.constant(0);
        long lo = Long.MAX_VALUE, hi = Long.MIN_VALUE;
        for (Lin x : xs) {
            lo = Math.min(lo, x.lb);
            hi = Math.max(hi, x.ub);
        }
        List<Literal> taken = new ArrayList<>();
        for (long v = lo; v <= hi; v++) {
            if (except != null && v == except) continue;
            List<Literal> any = new ArrayList<>();
            for (Lin x : xs) any.add(b.eq(x, v));
            taken.add(b.or(any));
            if (v == Long.MAX_VALUE) break;
        }
        return b.count(taken);
    }

    private void cardinality(CChild[] ch, List<Literal> guards) {
        check(ch.length == 3 && ch[0].type == TypeChild.list && ch[1].type == TypeChild.values && ch[2].type == TypeChild.occurs);
        List<Lin> xs = exprs(ch[0].value, guards);
        List<Object> rawValues = items(ch[1].value);
        if (rawValues.stream().anyMatch(v -> v instanceof XVar))
            throw Failure.type("('GlobalCardinalityCount expects a list of values, but got', " + e.show(vars(ch[1].value)) + ")");
        List<Long> values = ints(ch[1].value);
        boolean closed = ch[1].getAttributeValue(TypeAtt.closed, false);
        List<Lin> occurs = new ArrayList<>();
        for (Object o : items(ch[2].value)) {
            if (o instanceof IntegerInterval ii) occurs.add(Lin.of(b.intVar(ii.inf, ii.sup), ii.inf, ii.sup));
            else if (o instanceof XVar x) occurs.add(e.source(x).lin());
            else if (o instanceof Long l) occurs.add(Lin.constant(l));
            else if (o instanceof XNode<?> n) occurs.add(e.num(n, guards));
            else throw Failure.type("unexpected occurrence " + o);
        }
        if (values.size() != occurs.size())
            throw Failure.value("Number of values and occurrences must be equal, but got " + values.size() + " and " + occurs.size());
        for (int k = 0; k < values.size(); k++) {
            List<Literal> hits = new ArrayList<>();
            for (Lin x : xs) hits.add(b.eq(x, values.get(k)));
            b.postRel(Builder.Rel.EQ, b.count(hits), occurs.get(k));
        }
        if (closed) for (Lin x : xs) b.postInDomain(x, Builder.values(values));
    }

    private void element(CChild[] ch) {
        if (ch.length != 3)
            throw Failure.assertion("element seen as a member constraint is not in XCSP3-core, as it can be written differently");
        check((ch[0].type == TypeChild.list || ch[0].type == TypeChild.matrix) && ch[1].type == TypeChild.index
            && (ch[2].type == TypeChild.value || ch[2].type == TypeChild.condition));
        Condition condition = ch[2].type == TypeChild.value
            ? (ch[2].value instanceof XVar x ? new ConditionVar(TypeConditionOperatorRel.EQ, x) : new ConditionVal(TypeConditionOperatorRel.EQ, (Long) ch[2].value))
            : (Condition) ch[2].value;
        if (ch[0].type == TypeChild.matrix) {
            if (ch[0].getAttributeValue(TypeAtt.startRowIndex, 0) != 0 || ch[0].getAttributeValue(TypeAtt.startColIndex, 0) != 0)
                throw Failure.assertion("indexes must be 0 for XCSP3-core");
            List<Object> index = items(ch[1].value);
            check(index.size() == 2 && index.stream().allMatch(x -> x instanceof XVar));
            List<List<Lin>> cells = rows(ch[0].value, this::vars);
            transpose(cells);
            int rows = cells.size(), columns = rows == 0 ? 0 : cells.get(0).size();
            Lin row = var(index.get(0)), column = var(index.get(1));
            b.postRel(Builder.Rel.GE, row, Lin.constant(0));
            b.postRel(Builder.Rel.LT, row, Lin.constant(rows));
            b.postRel(Builder.Rel.GE, column, Lin.constant(0));
            b.postRel(Builder.Rel.LT, column, Lin.constant(columns));
            // Row-major selection uses the column count as its stride.
            List<Lin> flat = cells.stream().flatMap(List::stream).toList();
            compare(b.element(flat, row.times(columns).plus(column)), condition);
            return;
        }
        startsAtZero(ch[0]);
        if (!(condition instanceof ConditionRel))
            throw Failure.assertion("Expected list as rhs but got " + setText(condition));
        compare(b.element(vars(ch[0].value), var(ch[1].value)), condition);
    }

    private void noBooleans(List<Lin> xs, String message) {
        for (Lin x : xs) if (x.plainVar() instanceof com.google.ortools.sat.BoolVar)
            throw Failure.type(message + ": " + e.show(xs));
    }

    private void channel(CChild[] ch) {
        check(ch[0].type == TypeChild.list && (ch.length == 1 || ch.length == 2 && (ch[1].type == TypeChild.list || ch[1].type == TypeChild.value)));
        startsAtZero(ch[0]);
        List<Lin> xs = vars(ch[0].value);
        if (ch.length == 1) {
            noBooleans(xs, "Only integer arguments allowed for global constraint Inverse");
            for (int i = 0; i < xs.size(); i++) b.postRel(Builder.Rel.EQ, b.element(xs, xs.get(i)), Lin.constant(i));
            return;
        }
        if (ch[1].type == TypeChild.list) {
            startsAtZero(ch[1]);
            List<Lin> rev = vars(ch[1].value);
            List<Lin> both = new ArrayList<>(xs);
            both.addAll(rev);
            noBooleans(both, "Only integer arguments allowed for global constraint Inverse");
            if (xs.size() > rev.size())
                throw Failure.type("len(fwd) should be equal to len(rev) for the symmetric inverse, or smaller than len(rev) for the asymmetric inverse");
            if (xs.size() == rev.size())
                b.model.addInverse(xs.stream().map(b::var).toArray(IntVar[]::new), rev.stream().map(b::var).toArray(IntVar[]::new));
            else
                for (int i = 0; i < xs.size(); i++) b.postRel(Builder.Rel.EQ, b.element(rev, xs.get(i)), Lin.constant(i));
            return;
        }
        Lin v = var(ch[1].value);
        for (Lin x : xs)
            if (x.lb < 0 || x.ub > 1)
                throw Failure.type("the first argument of a Channel constraint should only contain 0-1 variables/expressions");
        for (int i = 0; i < xs.size(); i++)
            b.postRel(Builder.Rel.EQ, b.lin(b.eq(xs.get(i), 1)), b.lin(b.eq(v, i)));
        b.postRel(Builder.Rel.GE, v, Lin.constant(0));
        b.postRel(Builder.Rel.LT, v, Lin.constant(xs.size()));
    }

    /** An end variable spanning the bounds of s + d. */
    private Lin end(Lin s, Lin d) {
        Lin sum = s.plus(d);
        return Lin.of(b.intVar(sum.lb, sum.ub), sum.lb, sum.ub);
    }

    /** CPMpy's `get_nonneg_args`: a possibly negative argument is replaced by a copy that must be non-negative. */
    private Lin nonNegative(Lin x) {
        if (x.lb >= 0) return x;
        long ub = Math.max(x.ub, 0);
        IntVar iv = b.intVar(0, x.ub >= 0 ? ub : 0);
        b.model.addEquality(iv, x.build());
        return Lin.of(iv, 0, x.ub >= 0 ? ub : 0);
    }

    private IntervalVar interval(Lin s, Lin d, Lin e) {
        return b.model.newIntervalVar(b.affine(s), b.affine(d), b.affine(e), "");
    }

    private void noOverlap(CChild[] ch) {
        check(ch.length == 2 && ch[0].type == TypeChild.origins && ch[1].type == TypeChild.lengths);
        List<Object> origins = items(ch[0].value), lengths = items(ch[1].value);
        check(origins.size() == lengths.size() && !origins.isEmpty());
        if (origins.get(0) instanceof XVar) {
            List<Lin> starts = vars(ch[0].value), durations = vars(ch[1].value);
            List<IntervalVar> tasks = new ArrayList<>();
            boolean zero = false;
            for (int i = 0; i < starts.size(); i++) {
                Lin d = nonNegative(durations.get(i));
                zero |= d.lb <= 0 && 0 <= d.ub;
                tasks.add(interval(starts.get(i), d, end(starts.get(i), durations.get(i))));
            }
            if (zero) {
                // CP-SAT's no-overlap does not let zero-length tasks sit inside others; a unit cumulative does.
                CumulativeConstraint c = b.model.addCumulative(1);
                for (IntervalVar t : tasks) c.addDemand(t, 1);
            } else b.model.addNoOverlap(tasks);
            return;
        }
        List<List<Lin>> s = new ArrayList<>(), d = new ArrayList<>();
        for (Object o : origins) s.add(vars(o));
        for (Object o : lengths) d.add(vars(o));
        int dims = s.get(0).size();
        int n = s.size();
        if (dims == 2) {
            List<List<Lin>> ends = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                List<Lin> row = new ArrayList<>();
                for (int k = 0; k < 2; k++) {
                    Lin end = end(s.get(i).get(k), d.get(i).get(k));
                    b.postRel(Builder.Rel.EQ, s.get(i).get(k).plus(d.get(i).get(k)), end);
                    row.add(end);
                }
                ends.add(row);
            }
            for (int i = 0; i < n; i++)
                for (int j = i + 1; j < n; j++) {
                    List<Literal> apart = new ArrayList<>();
                    for (int k = 0; k < 2; k++) {
                        apart.add(b.rel(Builder.Rel.LE, ends.get(i).get(k), s.get(j).get(k)));
                        apart.add(b.rel(Builder.Rel.LE, ends.get(j).get(k), s.get(i).get(k)));
                    }
                    b.postOr(apart);
                }
            return;
        }
        for (int i = 0; i < n; i++)
            for (int j = i + 1; j < n; j++) {
                List<Literal> apart = new ArrayList<>();
                for (int k = 0; k < dims; k++) {
                    apart.add(b.rel(Builder.Rel.LE, s.get(i).get(k).plus(d.get(i).get(k)), s.get(j).get(k)));
                    apart.add(b.rel(Builder.Rel.LE, s.get(j).get(k).plus(d.get(j).get(k)), s.get(i).get(k)));
                }
                b.postOr(apart);
            }
    }

    private void cumulative(CChild[] ch, List<Literal> guards) {
        check(ch[0].type == TypeChild.origins && ch[1].type == TypeChild.lengths && ch[ch.length - 1].type == TypeChild.condition
            && (ch.length == 4 && ch[2].type == TypeChild.heights || ch.length == 5 && ch[2].type == TypeChild.ends && ch[3].type == TypeChild.heights));
        if (ch.length == 5) throw Failure.assertion("cumulative variant not implemented (and not in XCSP3-core)");
        List<Lin> starts = exprs(ch[0].value, guards), durations = exprs(ch[1].value, guards), heights = exprs(ch[2].value, guards);
        List<Lin> ends = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++) ends.add(end(starts.get(i), durations.get(i)));
        Condition condition = (Condition) ch[3].value;
        if (condition instanceof ConditionRel r && r.operator == TypeConditionOperatorRel.LE) {
            for (Lin d : durations) if (d.lb < 0) throw Failure.type("Durations should be non-negative");
            CumulativeConstraint c = b.model.addCumulative(b.affine(operand(r)));
            for (int i = 0; i < starts.size(); i++)
                c.addDemand(interval(starts.get(i), durations.get(i), ends.get(i)), b.affine(nonNegative(heights.get(i))));
            return;
        }
        // The importer decomposes any other condition itself, comparing its Python list of starts with a time point: a
        // short horizon fails at once, a long one when the model reaches the solver. No such cumulative is solved.
        long horizon = ends.stream().mapToLong(x -> x.ub).max().orElseThrow();
        if (horizon < 100) throw Failure.type("'<=' not supported between instances of 'list' and 'int'");
        if (!(condition instanceof ConditionRel))
            throw Failure.assertion("Expected list as rhs but got " + setText(condition));
        b.defer(new Failure("NotImplementedError", "Not a known var " + e.show(starts)));
    }

    private void binPacking(CChild[] ch) {
        check(ch.length == 3 && ch[0].type == TypeChild.list && ch[1].type == TypeChild.sizes);
        List<Lin> xs = vars(ch[0].value);
        List<Long> sizes = ints(ch[1].value);
        java.util.function.IntFunction<Lin> load = bin -> {
            Lin s = Lin.constant(0);
            for (int i = 0; i < xs.size(); i++)
                s = s.plus(b.lin(b.eq(xs.get(i), bin)).times(sizes.get(broadcast(i, sizes.size(), xs.size()))));
            return s;
        };
        switch (ch[2].type) {
            case condition -> {
                for (int bin = 0; bin < xs.size(); bin++) compare(load.apply(bin), (Condition) ch[2].value);
            }
            case limits -> {
                List<Object> limits = items(ch[2].value);
                if (limits.stream().anyMatch(l -> !(l instanceof Long)))
                    throw new Failure("AttributeError", "'XVar' object has no attribute 'is_bool'");
                for (int bin = 0; bin < limits.size(); bin++)
                    b.postRel(Builder.Rel.LE, load.apply(bin), Lin.constant((Long) limits.get(bin)));
            }
            case loads -> {
                List<Lin> loads = vars(ch[2].value);
                for (int bin = 0; bin < loads.size(); bin++) b.postRel(Builder.Rel.EQ, load.apply(bin), loads.get(bin));
            }
            case conditions -> throw new Failure.Unimplemented("ctr_binpacking_conditions");
            default -> throw Failure.assertion("");
        }
    }

    /** CPMpy's SubCircuitWithStart(start_index=0): a single circuit through node 0, other nodes may loop on themselves. */
    private void circuit(List<Lin> xs) {
        noBooleans(xs, "SubCircuitWithStart global constraint only takes arithmetic arguments");
        int n = xs.size();
        if (n < 2) throw new Failure("CPMpyException",
            "SubCircuitWithStart constraint must be given a minimum of 2 variables for field 'args' as stops to route between.");
        CircuitConstraint circuit = b.model.addCircuit();
        for (int i = 0; i < n; i++) {
            b.postInDomain(xs.get(i), new Domain(0, n - 1));
            for (int j = 0; j < n; j++) if (i != j || i != 0) circuit.addArc(i, j, b.eq(xs.get(i), j));
        }
    }

    // ---------------------------------------------------------------- objectives

    private void objective(OEntry entry) {
        List<Literal> guards = new ArrayList<>();
        Lin obj;
        if (entry instanceof OObjectiveExpr o) {
            XNode<?> root = o.rootNode;
            obj = e.num(root, guards);
        } else if (entry instanceof OObjectiveSpecial o) {
            List<Lin> terms = exprs(o.terms, guards);
            List<Lin> scaled = new ArrayList<>();
            if (o.coeffs == null) scaled.addAll(terms);
            else {
                List<Object> coefs = Arrays.asList(o.coeffs);
                for (int i = 0; i < terms.size(); i++) {
                    Object c = coefs.get(broadcast(i, coefs.size(), terms.size()));
                    scaled.add(c instanceof Long k ? terms.get(i).times(k) : b.mul(var(c), terms.get(i)));
                }
            }
            obj = switch (o.type) {
                case SUM -> b.sum(scaled);
                case MAXIMUM -> b.extremum(scaled, true);
                case MINIMUM -> b.extremum(scaled, false);
                case NVALUES -> nValues(scaled, null);
                case EXPRESSION -> {
                    if (o.coeffs != null && Arrays.stream(o.coeffs).anyMatch(c -> !Long.valueOf(1).equals(c))) throw Failure.assertion("");
                    if (terms.size() != 1) throw Failure.assertion("");
                    yield terms.get(0);
                }
                default -> throw new Failure.Unimplemented("obj_" + (o.minimize ? "minimize" : "maximize") + "_special");
            };
        } else throw Failure.assertion("unsupported objective");
        objective = obj;
        minimize = entry.minimize;
        objectiveGuards = guards;
    }

    /** The definedness literals of the objective that is kept; only it is safened, at the top level. */
    List<Literal> objectiveGuards = List.of();

    void finish() {
        if (objective == null) return;
        b.postAll(objectiveGuards);
        if (minimize) b.model.minimize(objective.build());
        else b.model.maximize(objective.build());
    }
}
