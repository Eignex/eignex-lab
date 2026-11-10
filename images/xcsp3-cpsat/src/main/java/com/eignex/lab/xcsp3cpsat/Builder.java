package com.eignex.lab.xcsp3cpsat;

import com.google.ortools.sat.BoolVar;
import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.IntVar;
import com.google.ortools.sat.LinearArgument;
import com.google.ortools.sat.Literal;
import com.google.ortools.util.Domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CP-SAT model construction with CPMpy's semantics: Boolean expressions are literals that are fully reified when
 * nested, integer expressions are exact [Lin]s, and a partial function (division or modulo by a possibly zero divisor)
 * is made total with a definedness literal that the nearest enclosing Boolean expression conjoins, so an undefined
 * subterm makes that expression false. At the top level that literal is simply enforced.
 */
final class Builder {
    final CpModel model = new CpModel();
    final Literal TRUE = model.trueLiteral();
    final Literal FALSE = TRUE.not();
    private final Map<String, Literal> relations = new HashMap<>();
    /** The first failure CPMpy raises only once the whole model reaches the solver, after every callback ran. */
    Failure deferred;

    void defer(Failure f) {
        if (deferred == null) deferred = f;
    }

    // ---------------------------------------------------------------- variables

    IntVar intVar(long lb, long ub) {
        return model.newIntVar(lb, ub, "");
    }

    BoolVar boolVar() {
        return model.newBoolVar("");
    }

    Lin lin(IntVar v, long lb, long ub) {
        return Lin.of(v, lb, ub);
    }

    Lin lin(Literal l) {
        if (l == TRUE) return Lin.constant(1);
        if (l == FALSE) return Lin.constant(0);
        return Lin.of(l);
    }

    /** A fresh variable equal to [e] (the variable itself when [e] already is one). */
    Lin define(Lin e) {
        if (e.isConstant() || e.plainVar() != null) return e;
        IntVar t = intVar(e.lb, e.ub);
        model.addEquality(t, e.build());
        return Lin.of(t, e.lb, e.ub);
    }

    /** [e] as an affine argument, which is what CP-SAT's non-linear constraints take. */
    LinearArgument affine(Lin e) {
        return (e.isAffine() ? e : define(e)).build();
    }

    IntVar var(Lin e) {
        if (e.plainVar() != null) return e.plainVar();
        IntVar t = intVar(e.lb, e.ub);
        model.addEquality(t, e.build());
        return t;
    }

    // ---------------------------------------------------------------- relations

    enum Rel {
        LT, LE, GE, GT, NE, EQ;

        static Rel of(String name) {
            return valueOf(name.toUpperCase());
        }

        Rel negate() {
            return switch (this) {
                case LT -> GE;
                case LE -> GT;
                case GE -> LT;
                case GT -> LE;
                case NE -> EQ;
                case EQ -> NE;
            };
        }
    }

    /** The values of a - b for which [r] holds, or null when its truth is decided by the bounds of a - b. */
    private static Boolean decided(Rel r, Lin d) {
        return switch (r) {
            case LT -> d.ub < 0 ? Boolean.TRUE : d.lb >= 0 ? Boolean.FALSE : null;
            case LE -> d.ub <= 0 ? Boolean.TRUE : d.lb > 0 ? Boolean.FALSE : null;
            case GE -> d.lb >= 0 ? Boolean.TRUE : d.ub < 0 ? Boolean.FALSE : null;
            case GT -> d.lb > 0 ? Boolean.TRUE : d.ub <= 0 ? Boolean.FALSE : null;
            case EQ -> d.lb == 0 && d.ub == 0 ? Boolean.TRUE : d.lb > 0 || d.ub < 0 ? Boolean.FALSE : null;
            case NE -> d.lb == 0 && d.ub == 0 ? Boolean.FALSE : d.lb > 0 || d.ub < 0 ? Boolean.TRUE : null;
        };
    }

    private static Domain domain(Rel r) {
        return switch (r) {
            case LT -> Domain.lowerOrEqual(-1);
            case LE -> Domain.lowerOrEqual(0);
            case GE -> Domain.greaterOrEqual(0);
            case GT -> Domain.greaterOrEqual(1);
            case EQ -> new Domain(0);
            case NE -> new Domain(0).complement();
        };
    }

    Literal rel(Rel r, Lin a, Lin b) {
        Lin d = a.minus(b);
        Boolean known = decided(r, d);
        if (known != null) return known ? TRUE : FALSE;
        String key = r + ":" + d.key();
        Literal cached = relations.get(key);
        if (cached != null) return cached;
        BoolVar lit = boolVar();
        model.addLinearExpressionInDomain(d.build(), domain(r)).onlyEnforceIf(lit);
        model.addLinearExpressionInDomain(d.build(), domain(r.negate())).onlyEnforceIf(lit.not());
        relations.put(key, lit);
        relations.put(r.negate() + ":" + d.key(), lit.not());
        return lit;
    }

    void postRel(Rel r, Lin a, Lin b) {
        Lin d = a.minus(b);
        Boolean known = decided(r, d);
        if (known == Boolean.TRUE) return;
        if (known == Boolean.FALSE) {
            post(FALSE);
            return;
        }
        model.addLinearExpressionInDomain(d.build(), domain(r));
    }

    Literal eq(Lin a, long v) {
        return rel(Rel.EQ, a, Lin.constant(v));
    }

    Literal inDomain(Lin a, Domain values) {
        if (values.isEmpty()) return FALSE;
        if (a.isConstant()) return values.contains(a.offset) ? TRUE : FALSE;
        Domain range = new Domain(a.lb, a.ub);
        if (range.isIncludedIn(values)) return TRUE;
        if (!range.overlapsWith(values)) return FALSE;
        String key = "in" + values + ":" + a.key();
        Literal cached = relations.get(key);
        if (cached != null) return cached;
        BoolVar lit = boolVar();
        model.addLinearExpressionInDomain(a.build(), values).onlyEnforceIf(lit);
        model.addLinearExpressionInDomain(a.build(), values.complement()).onlyEnforceIf(lit.not());
        relations.put(key, lit);
        return lit;
    }

    void postInDomain(Lin a, Domain values) {
        if (a.isConstant()) {
            if (!values.contains(a.offset)) post(FALSE);
            return;
        }
        model.addLinearExpressionInDomain(a.build(), values);
    }

    static Domain values(Collection<Long> values) {
        return Domain.fromValues(values.stream().mapToLong(Long::longValue).toArray());
    }

    // ---------------------------------------------------------------- Boolean connectives

    Literal not(Literal l) {
        return l == TRUE ? FALSE : l == FALSE ? TRUE : l.not();
    }

    Literal and(List<Literal> ls) {
        List<Literal> rest = new ArrayList<>();
        for (Literal l : ls) {
            if (l == FALSE) return FALSE;
            if (l != TRUE) rest.add(l);
        }
        if (rest.isEmpty()) return TRUE;
        if (rest.size() == 1) return rest.get(0);
        BoolVar r = boolVar();
        List<Literal> clause = new ArrayList<>();
        for (Literal l : rest) {
            model.addImplication(r, l);
            clause.add(l.not());
        }
        clause.add(r);
        model.addBoolOr(clause);
        return r;
    }

    Literal or(List<Literal> ls) {
        List<Literal> negated = new ArrayList<>();
        for (Literal l : ls) negated.add(not(l));
        return not(and(negated));
    }

    Literal xor(List<Literal> ls) {
        boolean parity = false;
        List<Literal> rest = new ArrayList<>();
        for (Literal l : ls) {
            if (l == TRUE) parity = !parity;
            else if (l != FALSE) rest.add(l);
        }
        if (rest.isEmpty()) return parity ? TRUE : FALSE;
        BoolVar r = boolVar();
        rest.add(parity ? r : r.not());
        model.addBoolXor(rest);
        return r;
    }

    /** The literal of a == b for two literals. */
    Literal iff(Literal a, Literal b) {
        if (a == TRUE) return b;
        if (b == TRUE) return a;
        if (a == FALSE) return not(b);
        if (b == FALSE) return not(a);
        BoolVar r = boolVar();
        model.addBoolXor(new Literal[]{a, b, r});
        return r;
    }

    void post(Literal l) {
        if (l != TRUE) model.addBoolOr(new Literal[]{l});
    }

    void postAll(List<Literal> ls) {
        for (Literal l : ls) post(l);
    }

    void postOr(List<Literal> ls) {
        List<Literal> rest = new ArrayList<>();
        for (Literal l : ls) {
            if (l == TRUE) return;
            if (l != FALSE) rest.add(l);
        }
        model.addBoolOr(rest);
    }

    // ---------------------------------------------------------------- integer functions

    Lin abs(Lin a) {
        if (a.lb >= 0) return a;
        if (a.ub <= 0) return a.times(-1);
        IntVar t = intVar(0, Math.max(Math.negateExact(a.lb), a.ub));
        model.addAbsEquality(t, affine(a));
        return Lin.of(t, 0, Math.max(-a.lb, a.ub));
    }

    Lin mul(Lin a, Lin b) {
        if (a.isConstant()) return b.times(a.offset);
        if (b.isConstant()) return a.times(b.offset);
        long[] c = {Math.multiplyExact(a.lb, b.lb), Math.multiplyExact(a.lb, b.ub),
            Math.multiplyExact(a.ub, b.lb), Math.multiplyExact(a.ub, b.ub)};
        long lo = Math.min(Math.min(c[0], c[1]), Math.min(c[2], c[3]));
        long hi = Math.max(Math.max(c[0], c[1]), Math.max(c[2], c[3]));
        IntVar t = intVar(lo, hi);
        model.addMultiplicationEquality(t, affine(a), affine(b));
        return Lin.of(t, lo, hi);
    }

    /**
     * The divisor of a division or modulo as an expression without 0 in its domain. When [b] may be 0, a copy without 0
     * stands in for it and the literal "b != 0" joins [guards]; when it can only be 0 the guard is false.
     */
    private Lin divisor(Lin b, List<Literal> guards) {
        if (b.lb > 0 || b.ub < 0) return b;
        if (b.lb == 0 && b.ub == 0) {
            guards.add(FALSE);
            return Lin.constant(1);
        }
        Domain nonZero = new Domain(b.lb, b.ub).intersectionWith(new Domain(0).complement());
        IntVar copy = model.newIntVarFromDomain(nonZero, "");
        Literal defined = rel(Rel.NE, b, Lin.constant(0));
        model.addEquality(copy, b.build()).onlyEnforceIf(defined);
        guards.add(defined);
        return Lin.of(copy, nonZero.min(), nonZero.max());
    }

    Lin div(Lin a, Lin b, List<Literal> guards) {
        Lin d = divisor(b, guards);
        if (d.isConstant() && d.offset == 1 && b.lb == 0 && b.ub == 0) return Lin.of(intVar(0, 1), 0, 1);
        if (d.isConstant() && a.isConstant()) return Lin.constant(a.offset / d.offset);
        if (d.isConstant() && d.offset == 1) return a;
        List<Long> divisors = new ArrayList<>(List.of(d.lb, d.ub));
        if (d.lb <= -1) divisors.add(-1L);
        if (d.ub >= 1) divisors.add(1L);
        long lo = Long.MAX_VALUE, hi = Long.MIN_VALUE;
        for (long x : new long[]{a.lb, a.ub})
            for (long y : divisors) {
                if (y == 0) continue;
                long q = Math.divideExact(x, y);
                lo = Math.min(lo, q);
                hi = Math.max(hi, q);
            }
        IntVar t = intVar(lo, hi);
        model.addDivisionEquality(t, affine(a), affine(d));
        return Lin.of(t, lo, hi);
    }

    Lin mod(Lin a, Lin b, List<Literal> guards) {
        Lin d = divisor(b, guards);
        if (d.isConstant() && d.offset == 1 && b.lb == 0 && b.ub == 0) return Lin.of(intVar(0, 1), 0, 1);
        // Truncated remainders do not depend on the sign of the divisor: x mod y = x mod |y|.
        Lin m = d;
        if (d.ub < 0) m = d.times(-1);
        else if (d.lb < 0) {
            long most = Math.max(Math.negateExact(d.lb), d.ub);
            IntVar t = intVar(1, most);
            model.addAbsEquality(t, affine(d));
            m = Lin.of(t, 1, most);
        }
        if (m.isConstant() && a.isConstant()) return Lin.constant(a.offset % m.offset);
        long most = Math.subtractExact(m.ub, 1);
        long lo = a.lb >= 0 ? 0 : Math.max(a.lb, -most);
        long hi = a.ub <= 0 ? 0 : Math.min(a.ub, most);
        IntVar t = intVar(lo, hi);
        model.addModuloEquality(t, affine(a), affine(m));
        return Lin.of(t, lo, hi);
    }

    Lin extremum(List<Lin> xs, boolean max) {
        if (xs.isEmpty()) throw Failure.value((max ? "max" : "min") + "() arg is an empty sequence");
        if (xs.size() == 1) return xs.get(0);
        boolean constant = xs.stream().allMatch(Lin::isConstant);
        if (constant) {
            long best = xs.get(0).offset;
            for (Lin x : xs) best = max ? Math.max(best, x.offset) : Math.min(best, x.offset);
            return Lin.constant(best);
        }
        long lo = Long.MAX_VALUE, hi = Long.MIN_VALUE;
        if (max) {
            lo = Long.MIN_VALUE;
            for (Lin x : xs) {
                lo = Math.max(lo, x.lb);
                hi = Math.max(hi, x.ub);
            }
        } else {
            hi = Long.MAX_VALUE;
            for (Lin x : xs) {
                lo = Math.min(lo, x.lb);
                hi = Math.min(hi, x.ub);
            }
        }
        IntVar t = intVar(lo, hi);
        LinearArgument[] args = xs.stream().map(this::affine).toArray(LinearArgument[]::new);
        if (max) model.addMaxEquality(t, args);
        else model.addMinEquality(t, args);
        return Lin.of(t, lo, hi);
    }

    /** if b then x else y, as an integer. */
    Lin ite(Literal b, Lin x, Lin y) {
        if (b == TRUE) return x;
        if (b == FALSE) return y;
        long lo = Math.min(x.lb, y.lb), hi = Math.max(x.ub, y.ub);
        IntVar t = intVar(lo, hi);
        model.addEquality(t, x.build()).onlyEnforceIf(b);
        model.addEquality(t, y.build()).onlyEnforceIf(b.not());
        return Lin.of(t, lo, hi);
    }

    /** arr[index], posted at the top level, where CP-SAT's element also keeps the index within the array. */
    Lin element(List<Lin> arr, Lin index) {
        long lo = Long.MAX_VALUE, hi = Long.MIN_VALUE;
        for (Lin x : arr) {
            lo = Math.min(lo, x.lb);
            hi = Math.max(hi, x.ub);
        }
        if (arr.isEmpty()) {
            lo = 0;
            hi = 0;
        }
        IntVar t = intVar(lo, hi);
        model.addElement(affine(index), arr.stream().map(this::affine).toArray(LinearArgument[]::new), t);
        return Lin.of(t, lo, hi);
    }

    Lin sum(List<Lin> xs) {
        return Lin.sum(xs);
    }

    Lin count(List<Literal> ls) {
        List<Lin> terms = new ArrayList<>(ls.size());
        for (Literal l : ls) terms.add(lin(l));
        return Lin.sum(terms);
    }
}
