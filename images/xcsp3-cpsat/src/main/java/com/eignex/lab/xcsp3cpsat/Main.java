package com.eignex.lab.xcsp3cpsat;

import com.google.ortools.sat.CpSolver;
import com.google.ortools.sat.CpSolverStatus;
import com.google.ortools.sat.SatParameters;
import org.xcsp.parser.XParser;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Solves one XCSP3 instance with OR-Tools cp-sat and prints source witnesses plus a one-line JSON verdict.
 *
 * <p>Usage: {@code <instance.xml> [time_limit_seconds=10] [workers=1] [seed=3]}. The JVM bench runs this per instance
 * and parses stdout: an {@code o <cost>} line for a solution of an optimisation instance, a {@code v <instantiation>}
 * line with every source variable for any solution, then the JSON verdict with fields version, exit
 * (OPTIMAL|FEASIBLE|UNSATISFIABLE|UNKNOWN|ERROR), runtime (cp-sat wall seconds), objective, maximize, exactObjective,
 * witness, seed, workers and cpModelPresolve. A parse or model failure is an ERROR verdict; a construct the importer
 * has no callback for ends the process with status 1 and no verdict. Everything else the solver or parser prints goes
 * to stderr.
 */
public final class Main {
    public static final String VERSION = "xcsp3-source-preserving-v3";

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        int[] status = {0};
        // Deep expression trees recurse; the main thread's default stack is too small for them.
        PrintStream out = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), false, StandardCharsets.UTF_8);
        Thread worker = new Thread(null, () -> status[0] = run(args, out, System.err), "solve", 1L << 30);
        worker.start();
        worker.join();
        out.flush();
        System.exit(status[0]);
    }

    /** Runs the command line, writing the verdict to [out] and diagnostics to [err]; returns the exit status. */
    static int run(String[] args, PrintStream out, PrintStream err) {
        double timeLimit;
        int workers;
        long seed;
        String path;
        try {
            if (args.length < 1) throw new IllegalArgumentException("list index out of range");
            path = args[0];
            timeLimit = args.length > 1 ? parseFloat(args[1]) : 10.0;
            workers = args.length > 2 ? Math.toIntExact(parseInt(args[2])) : 1;
            seed = args.length > 3 ? parseInt(args[3]) : 3;
        } catch (RuntimeException e) {
            err.println("usage: <instance.xml> [time_limit_seconds] [workers] [seed]: " + e.getMessage());
            return 1;
        }
        PrintStream original = System.out;
        // Parser chatter stays separate from the source witness and JSON verdict.
        System.setOut(err);
        Map<String, Object> result;
        try {
            result = solve(path, timeLimit, workers, seed);
        } catch (Failure.Unimplemented e) {
            err.println("  EXIT because not implemented method:");
            err.println("\t" + e.getMessage());
            err.flush();
            return 1;
        } catch (Throwable e) {
            result = new LinkedHashMap<>();
            result.put("exit", "ERROR");
            result.put("error", describe(e));
            result.put("version", VERSION);
        } finally {
            System.setOut(original);
        }
        @SuppressWarnings("unchecked")
        Map<String, Long> witness = (Map<String, Long>) result.get("witness");
        if (witness != null) {
            Object cost = result.get("exactObjective");
            if (cost != null) out.println("o " + cost);
            List<String> names = new ArrayList<>(), values = new ArrayList<>();
            witness.forEach((name, value) -> {
                names.add(Json.xmlEscape(name));
                values.add(String.valueOf(value));
            });
            String attribute = cost == null ? "" : " cost=\"" + cost + "\"";
            out.println("v <instantiation" + attribute + "><list>" + String.join(" ", names) + "</list><values>"
                + String.join(" ", values) + "</values></instantiation>");
        }
        out.println(Json.write(result));
        out.flush();
        return 0;
    }

    static String describe(Throwable e) {
        if (e instanceof Failure f) return f.kind + ": " + f.getMessage();
        if (e instanceof org.xml.sax.SAXException) return "ParseError: " + message(e);
        if (e instanceof OutOfMemoryError) return "MemoryError: " + message(e);
        if (e instanceof StackOverflowError) return "RecursionError: maximum recursion depth exceeded";
        if (e instanceof ArithmeticException) return "OverflowError: " + message(e);
        return e.getClass().getSimpleName() + ": " + message(e);
    }

    private static String message(Throwable e) {
        return e.getMessage() == null ? "" : e.getMessage();
    }

    static Map<String, Object> solve(String path, double timeLimit, int workers, long seed) throws Exception {
        Native.load();
        XParser parser;
        try (var in = new java.io.FileInputStream(new File(path))) {
            parser = new XParser(DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(in));
        }
        Builder b = new Builder();
        Loader loader = new Loader(b);
        // PyCSP3 rejects several objectives while parsing, before any callback.
        if (parser.oEntries.size() > 1) throw Failure.assertion("For the moment, only mono-objective handled");
        loader.load(parser);
        loader.finish();
        if (b.deferred != null) throw b.deferred;
        boolean hasObjective = loader.objective != null;

        if (timeLimit <= 0) throw Failure.value("Time limit must be positive");
        if (seed < Integer.MIN_VALUE || seed > Integer.MAX_VALUE) throw Failure.value("Value out of range: " + seed);
        CpSolver solver = new CpSolver();
        SatParameters.Builder p = solver.getParameters();
        p.setMaxTimeInSeconds(timeLimit);
        // The bench parallelises across instances, so each container is pinned to its own worker count.
        p.setNumWorkers(workers);
        p.setRandomSeed((int) seed);
        p.setCpModelPresolve(true);
        CpSolverStatus status = solver.solve(b.model);
        String exit = switch (status) {
            case OPTIMAL -> b.model.hasObjective() ? "OPTIMAL" : "FEASIBLE";
            case FEASIBLE -> "FEASIBLE";
            case INFEASIBLE -> "UNSATISFIABLE";
            case UNKNOWN -> "UNKNOWN";
            case MODEL_INVALID -> throw new Failure("Exception",
                "(" + Json.pyRepr("OR-Tools says: model invalid:") + ", " + Json.pyRepr(b.model.validate()) + ")");
            default -> throw new Failure("NotImplementedError", String.valueOf(status));
        };
        boolean solved = exit.equals("OPTIMAL") || exit.equals("FEASIBLE");
        Long objective = solved && hasObjective ? solver.value(loader.objective.build()) : null;
        Map<String, Long> witness = null;
        if (solved) {
            witness = new LinkedHashMap<>();
            for (Expr.Source s : loader.e.sources.values()) witness.put(s.id(), solver.value(s.var()));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", VERSION);
        result.put("exit", exit);
        result.put("runtime", solver.wallTime());
        result.put("objective", objective);
        result.put("maximize", hasObjective ? !loader.minimize : null);
        result.put("exactObjective", objective == null ? null : objective.toString());
        result.put("witness", witness);
        result.put("seed", seed);
        result.put("workers", (long) workers);
        result.put("cpModelPresolve", true);
        return result;
    }

    /** Python's float(): optional surrounding whitespace, decimal or exponent notation, inf and nan. */
    static double parseFloat(String s) {
        String t = s.strip().replace("_", "");
        String l = t.toLowerCase();
        if (l.matches("[+-]?(inf|infinity)")) return l.startsWith("-") ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        if (l.matches("[+-]?nan")) return Double.NaN;
        if (!t.matches("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?"))
            throw new IllegalArgumentException("could not convert string to float: '" + s + "'");
        return Double.parseDouble(t);
    }

    /** Python's int(): optional surrounding whitespace, a sign and digits with single underscores between them. */
    static long parseInt(String s) {
        String t = s.strip();
        if (!t.matches("[+-]?\\d+(_\\d+)*"))
            throw new IllegalArgumentException("invalid literal for int() with base 10: '" + s + "'");
        return new BigDecimal(t.replace("_", "")).longValueExact();
    }
}
