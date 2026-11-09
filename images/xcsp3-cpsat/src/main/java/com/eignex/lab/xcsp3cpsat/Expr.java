package com.eignex.lab.xcsp3cpsat;

import com.google.ortools.sat.BoolVar;
import com.google.ortools.sat.IntVar;
import com.google.ortools.sat.Literal;
import com.google.ortools.util.Domain;
import org.xcsp.common.Types.TypeExpr;
import org.xcsp.common.predicates.XNode;
import org.xcsp.common.predicates.XNodeLeaf;
import org.xcsp.parser.entries.XVariables.XVar;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * XCSP3 source variables and the translation of XCSP3 expression trees (intension predicates, expression terms of
 * globals, objectives), operator by operator as CPMpy's XCSP3 importer maps them (its `funcmap`).
 */
final class Expr {
    /** A source variable: a 0/1 domain is a Boolean variable, as in CPMpy, so it may stand where a Boolean is expected. */
    record Source(String id, IntVar var, long lb, long ub, boolean bool) {
        Lin lin() {
            return Lin.of(var, lb, ub);
        }
    }

    final Builder b;
    /** Source variables in declaration order: the order of the witness. */
    final Map<String, Source> sources = new LinkedHashMap<>();

    Expr(Builder b) {
        this.b = b;
    }

    Source source(XVar x) {
        Source s = sources.get(x.id);
        if (s == null) throw Failure.key(x.id);
        return s;
    }

    Source declare(String id, Domain domain) {
        long lb = domain.min(), ub = domain.max();
        boolean bool = lb == 0 && ub == 1;
        IntVar var = bool ? b.model.newBoolVar(id) : b.model.newIntVarFromDomain(domain, id);
        Source s = new Source(id, var, lb, ub, bool);
        sources.put(id, s);
        names.put(var.getIndex(), id);
        return s;
    }

    private final Map<Integer, String> names = new java.util.HashMap<>();

    /** A list of terms as CPMpy prints it in messages, e.g. `[x[0], x[1]]`. */
    String show(List<Lin> xs) {
        List<String> parts = new ArrayList<>();
        for (Lin x : xs) {
            IntVar v = x.plainVar();
            parts.add(v != null && names.containsKey(v.getIndex()) ? names.get(v.getIndex())
                : x.isConstant() ? String.valueOf(x.offset) : x.key());
        }
        return "[" + String.join(", ", parts) + "]";
    }

    // ---------------------------------------------------------------- typing

    private static final Set<TypeExpr> BOOLEAN_OPERATORS = Set.of(TypeExpr.LT, TypeExpr.LE, TypeExpr.GE, TypeExpr.GT,
        TypeExpr.NE, TypeExpr.EQ, TypeExpr.IN, TypeExpr.NOTIN, TypeExpr.NOT, TypeExpr.AND, TypeExpr.OR, TypeExpr.XOR,
        TypeExpr.IFF, TypeExpr.IMP);

    boolean isBool(XNode<?> n) {
        if (n.type == TypeExpr.VAR) return source((XVar) ((XNodeLeaf<?>) n).value).bool;
        if (n.type == TypeExpr.IF) return n.sons.length == 3 && isBool(n.sons[1]) && isBool(n.sons[2]);
        return BOOLEAN_OPERATORS.contains(n.type);
    }

    private static String name(XNode<?> n) {
        return n.type.name().toLowerCase();
    }

    private static void arity(XNode<?> n, int k) {
        int given = n.sons == null ? 0 : n.sons.length;
        if (given != k) throw Failure.type("<lambda>() takes " + k + " positional arguments but " + given + " were given");
    }

    // ---------------------------------------------------------------- integer terms

    Lin num(XNode<?> n, List<Literal> guards) {
        switch (n.type) {
            case LONG:
                return Lin.constant((Long) ((XNodeLeaf<?>) n).value);
            case VAR:
                return source((XVar) ((XNodeLeaf<?>) n).value).lin();
            default:
        }
        if (isBool(n)) return b.lin(lit(n));
        switch (n.type) {
            case NEG: {
                arity(n, 1);
                return num(n.sons[0], guards).times(-1);
            }
            case ABS: {
                arity(n, 1);
                return b.abs(num(n.sons[0], guards));
            }
            case ADD: {
                Lin s = Lin.constant(0);
                for (XNode<?> son : n.sons) s = s.plus(num(son, guards));
                return s;
            }
            case SUB: {
                arity(n, 2);
                return num(n.sons[0], guards).minus(num(n.sons[1], guards));
            }
            case MUL: {
                Lin p = num(n.sons[0], guards);
                for (int i = 1; i < n.sons.length; i++) p = b.mul(p, num(n.sons[i], guards));
                return p;
            }
            case DIV:
            case MOD: {
                arity(n, 2);
                Lin x = num(n.sons[0], guards), y = num(n.sons[1], guards);
                if (x.isConstant() && y.isConstant()) {
                    // Two Python integers: floor division and modulo, not the solver's truncation.
                    if (y.offset == 0) throw new Failure("ZeroDivisionError", "integer division or modulo by zero");
                    return Lin.constant(n.type == TypeExpr.DIV ? Math.floorDiv(x.offset, y.offset)
                        : Math.floorMod(x.offset, y.offset));
                }
                return n.type == TypeExpr.DIV ? b.div(x, y, guards) : b.mod(x, y, guards);
            }
            case SQR: {
                arity(n, 1);
                Lin x = num(n.sons[0], guards);
                return b.mul(x, x);
            }
            case POW: {
                arity(n, 2);
                Lin base = num(n.sons[0], guards), exponent = num(n.sons[1], guards);
                if (!exponent.isConstant())
                    throw Failure.type("Power operator requires a constant integer exponent, not: " + n.sons[1]);
                return pow(base, exponent);
            }
            case MIN:
            case MAX: {
                List<Lin> xs = new ArrayList<>();
                for (XNode<?> son : n.sons) xs.add(num(son, guards));
                return b.extremum(xs, n.type == TypeExpr.MAX);
            }
            case DIST: {
                arity(n, 2);
                return b.abs(num(n.sons[0], guards).minus(num(n.sons[1], guards)));
            }
            case IF: {
                arity(n, 3);
                // CPMpy 1.1 has no decomposition for an integer if-then-else: it fails once the model reaches the solver.
                b.defer(new Failure("NotImplementedError", "('Decomposition for', IfThenElseNum(" + n.sons[0] + ","
                    + n.sons[1] + "," + n.sons[2] + "), 'not available')"));
                return b.ite(lit(n.sons[0]), num(n.sons[1], guards), num(n.sons[2], guards));
            }
            case SET:
                throw Failure.type("a set is not an integer expression: " + n);
            default:
                throw Failure.key(name(n));
        }
    }

    Lin pow(Lin base, Lin exponent) {
        if (!exponent.isConstant())
            throw Failure.type("Power constraint takes an integer number as second argument, not: " + exponent.key());
        long e = exponent.offset;
        if (base.isConstant()) {
            if (e < 0) throw Failure.value("negative exponents are not integers: " + e);
            BigInteger p = BigInteger.valueOf(base.offset).pow(Math.toIntExact(e));
            return Lin.constant(p.longValueExact());
        }
        if (e < 0) throw Failure.value("Power constraint only supports non-negative integer exponents, not: " + e);
        if (e == 0) return Lin.constant(1);
        Lin p = base;
        for (long i = 1; i < e; i++) p = b.mul(p, base);
        return p;
    }

    // ---------------------------------------------------------------- Boolean terms

    /** The literal equivalent to Boolean term [n]. */
    Literal lit(XNode<?> n) {
        switch (n.type) {
            case VAR: {
                Source s = source((XVar) ((XNodeLeaf<?>) n).value);
                if (!s.bool) throw nonBoolean(s.id);
                return (BoolVar) s.var;
            }
            case LONG: {
                long v = (Long) ((XNodeLeaf<?>) n).value;
                if (v == 0 || v == 1) return v == 1 ? b.TRUE : b.FALSE;
                throw nonBoolean(String.valueOf(v));
            }
            case LT:
            case LE:
            case GE:
            case GT: {
                arity(n, 2);
                List<Literal> guards = new ArrayList<>();
                Lin x = num(n.sons[0], guards), y = num(n.sons[1], guards);
                return guarded(guards, b.rel(Builder.Rel.of(name(n)), x, y));
            }
            case EQ:
            case NE: {
                List<Literal> guards = new ArrayList<>();
                List<Lin> xs = nums(n.sons, guards);
                if (xs.size() == 2) return guarded(guards, b.rel(Builder.Rel.of(name(n)), xs.get(0), xs.get(1)));
                Literal all = guarded(guards, allEqual(xs));
                return n.type == TypeExpr.EQ ? all : b.not(all);
            }
            case IN:
            case NOTIN: {
                arity(n, 2);
                List<Literal> guards = new ArrayList<>();
                Lin x = num(n.sons[0], guards);
                List<Lin> set = set(n.sons[1], guards);
                Literal in = member(x, set);
                return guarded(guards, n.type == TypeExpr.IN ? in : b.not(in));
            }
            case NOT:
                arity(n, 1);
                if (!isBool(n.sons[0]) && n.sons[0].type != TypeExpr.LONG)
                    throw Failure.type("Not operator is only allowed on boolean expressions: " + n.sons[0]);
                return b.not(lit(n.sons[0]));
            case AND:
                return b.and(lits(n.sons));
            case OR:
                return b.or(lits(n.sons));
            case XOR:
                return b.xor(lits(n.sons));
            case IFF: {
                List<Literal> guards = new ArrayList<>();
                List<Lin> xs = nums(n.sons, guards);
                List<Literal> pairs = new ArrayList<>();
                for (int i = 0; i + 1 < xs.size(); i++) pairs.add(b.rel(Builder.Rel.EQ, xs.get(i), xs.get(i + 1)));
                return guarded(guards, b.and(pairs));
            }
            case IMP:
                arity(n, 2);
                return b.or(List.of(b.not(lit(n.sons[0])), lit(n.sons[1])));
            case IF: {
                arity(n, 3);
                if (!isBool(n)) break;
                Literal c = lit(n.sons[0]);
                return b.or(List.of(b.and(List.of(c, lit(n.sons[1]))), b.and(List.of(b.not(c), lit(n.sons[2])))));
            }
            default:
        }
        if (n.type == TypeExpr.SET || !isKnown(n.type)) throw Failure.key(name(n));
        throw nonBoolean(String.valueOf(n));
    }

    private static Failure nonBoolean(String term) {
        return new Failure("Exception", "Non-Boolean argument '" + term + "' to 'all'");
    }

    private static boolean isKnown(TypeExpr t) {
        return switch (t) {
            case NEG, ABS, ADD, SUB, MUL, DIV, MOD, SQR, POW, MIN, MAX, DIST, IF -> true;
            default -> false;
        };
    }

    /** The term [n] as it stands in the source: a Boolean term must hold, an integer one is rejected. */
    void post(XNode<?> n) {
        switch (n.type) {
            case AND:
                for (XNode<?> son : n.sons) post(son);
                return;
            case OR:
                b.postOr(lits(n.sons));
                return;
            case LT:
            case LE:
            case GE:
            case GT:
            case EQ:
            case NE: {
                if (n.sons.length != 2 && (n.type == TypeExpr.EQ || n.type == TypeExpr.NE)) break;
                arity(n, 2);
                List<Literal> guards = new ArrayList<>();
                Lin x = num(n.sons[0], guards), y = num(n.sons[1], guards);
                b.postRel(Builder.Rel.of(name(n)), x, y);
                b.postAll(guards);
                return;
            }
            case IN:
            case NOTIN: {
                arity(n, 2);
                List<Literal> guards = new ArrayList<>();
                Lin x = num(n.sons[0], guards);
                List<Lin> set = set(n.sons[1], guards);
                if (set.stream().allMatch(Lin::isConstant)) {
                    Domain d = constants(set);
                    b.postInDomain(x, n.type == TypeExpr.IN ? d : d.complement());
                } else b.post(n.type == TypeExpr.IN ? member(x, set) : b.not(member(x, set)));
                b.postAll(guards);
                return;
            }
            case IMP:
                arity(n, 2);
                b.postOr(List.of(b.not(lit(n.sons[0])), lit(n.sons[1])));
                return;
            default:
        }
        b.post(lit(n));
    }

    // ---------------------------------------------------------------- helpers

    Literal guarded(List<Literal> guards, Literal l) {
        if (guards.isEmpty()) return l;
        List<Literal> all = new ArrayList<>(guards);
        all.add(l);
        return b.and(all);
    }

    List<Lin> nums(XNode<?>[] nodes, List<Literal> guards) {
        List<Lin> xs = new ArrayList<>();
        for (XNode<?> n : nodes) xs.add(num(n, guards));
        return xs;
    }

    List<Literal> lits(XNode<?>[] nodes) {
        List<Literal> ls = new ArrayList<>();
        for (XNode<?> n : nodes) ls.add(lit(n));
        return ls;
    }

    Literal allEqual(List<Lin> xs) {
        List<Literal> pairs = new ArrayList<>();
        for (int i = 0; i + 1 < xs.size(); i++) pairs.add(b.rel(Builder.Rel.EQ, xs.get(i), xs.get(i + 1)));
        return b.and(pairs);
    }

    /** The members of a `set(...)` term, without repetition. */
    private List<Lin> set(XNode<?> n, List<Literal> guards) {
        if (n.type != TypeExpr.SET) throw Failure.assertion("Expected list as rhs but got " + n);
        List<Lin> members = new ArrayList<>();
        if (n.sons == null) return members;
        Set<String> seen = new LinkedHashSet<>();
        for (XNode<?> son : n.sons) {
            Lin m = num(son, guards);
            if (seen.add(m.key())) members.add(m);
        }
        return members;
    }

    static Domain constants(List<Lin> xs) {
        return Domain.fromValues(xs.stream().mapToLong(x -> x.offset).toArray());
    }

    /** x is one of [set]. */
    Literal member(Lin x, List<Lin> set) {
        if (set.stream().allMatch(Lin::isConstant)) return b.inDomain(x, constants(set));
        List<Literal> eqs = new ArrayList<>();
        for (Lin m : set) eqs.add(b.rel(Builder.Rel.EQ, x, m));
        return b.or(eqs);
    }
}
