package com.eignex.lab.xcsp3cpsat;

import com.google.ortools.sat.IntVar;
import com.google.ortools.sat.LinearArgument;
import com.google.ortools.sat.LinearExpr;
import com.google.ortools.sat.LinearExprBuilder;
import com.google.ortools.sat.Literal;

import java.util.Arrays;

/**
 * An exact integer linear expression over CP-SAT variables and literals, with interval bounds. Every bound is computed
 * with overflow-checked arithmetic: an expression whose bounds leave the 64-bit range is a model CP-SAT cannot hold,
 * and fails the instance instead of wrapping.
 */
final class Lin {
    final LinearArgument[] args;
    final long[] argLb, argUb, coefs;
    final long offset, lb, ub;

    private Lin(LinearArgument[] args, long[] argLb, long[] argUb, long[] coefs, long offset) {
        this.args = args;
        this.argLb = argLb;
        this.argUb = argUb;
        this.coefs = coefs;
        this.offset = offset;
        long lo = offset, hi = offset;
        for (int i = 0; i < args.length; i++) {
            long a = Math.multiplyExact(coefs[i], argLb[i]), b = Math.multiplyExact(coefs[i], argUb[i]);
            lo = Math.addExact(lo, Math.min(a, b));
            hi = Math.addExact(hi, Math.max(a, b));
        }
        this.lb = lo;
        this.ub = hi;
    }

    static Lin constant(long c) {
        return new Lin(new LinearArgument[0], new long[0], new long[0], new long[0], c);
    }

    static Lin of(LinearArgument arg, long lb, long ub) {
        return new Lin(new LinearArgument[]{arg}, new long[]{lb}, new long[]{ub}, new long[]{1}, 0);
    }

    static Lin of(Literal literal) {
        return of(literal, 0, 1);
    }

    boolean isConstant() {
        return args.length == 0;
    }

    boolean isAffine() {
        return args.length <= 1;
    }

    /** The plain variable this expression is, or null. */
    IntVar plainVar() {
        return args.length == 1 && coefs[0] == 1 && offset == 0 && args[0] instanceof IntVar v ? v : null;
    }

    Lin plus(Lin o) {
        int n = args.length, m = o.args.length;
        LinearArgument[] a = Arrays.copyOf(args, n + m);
        long[] l = Arrays.copyOf(argLb, n + m), u = Arrays.copyOf(argUb, n + m), c = Arrays.copyOf(coefs, n + m);
        System.arraycopy(o.args, 0, a, n, m);
        System.arraycopy(o.argLb, 0, l, n, m);
        System.arraycopy(o.argUb, 0, u, n, m);
        System.arraycopy(o.coefs, 0, c, n, m);
        return new Lin(a, l, u, c, Math.addExact(offset, o.offset));
    }

    /** The sum of [xs], built in one pass (repeated [plus] would copy quadratically on long sums). */
    static Lin sum(java.util.List<Lin> xs) {
        int n = 0;
        for (Lin x : xs) n += x.args.length;
        LinearArgument[] a = new LinearArgument[n];
        long[] l = new long[n], u = new long[n], c = new long[n];
        long offset = 0;
        int k = 0;
        for (Lin x : xs) {
            int m = x.args.length;
            System.arraycopy(x.args, 0, a, k, m);
            System.arraycopy(x.argLb, 0, l, k, m);
            System.arraycopy(x.argUb, 0, u, k, m);
            System.arraycopy(x.coefs, 0, c, k, m);
            k += m;
            offset = Math.addExact(offset, x.offset);
        }
        return new Lin(a, l, u, c, offset);
    }

    Lin plus(long k) {
        return new Lin(args, argLb, argUb, coefs, Math.addExact(offset, k));
    }

    Lin times(long k) {
        if (k == 0) return constant(0);
        long[] c = new long[coefs.length];
        for (int i = 0; i < c.length; i++) c[i] = Math.multiplyExact(coefs[i], k);
        return new Lin(args, argLb, argUb, c, Math.multiplyExact(offset, k));
    }

    Lin minus(Lin o) {
        return plus(o.times(-1));
    }

    LinearExpr build() {
        LinearExprBuilder b = LinearExpr.newBuilder();
        for (int i = 0; i < args.length; i++) b.addTerm(args[i], coefs[i]);
        b.add(offset);
        return b.build();
    }

    /** A structural key: equal keys denote the same expression, so reified relations over them can be shared. */
    String key() {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            int index = args[i] instanceof IntVar v ? v.getIndex() : ((Literal) args[i]).getIndex();
            s.append(coefs[i]).append('*').append(index).append(' ');
        }
        return s.append(offset).toString();
    }
}
