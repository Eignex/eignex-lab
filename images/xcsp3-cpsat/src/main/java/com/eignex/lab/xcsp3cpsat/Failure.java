package com.eignex.lab.xcsp3cpsat;

/**
 * A per-instance failure reported as the ERROR verdict. [kind] keeps the exception name the CPMpy importer reported for
 * the same case (AssertionError, KeyError, TypeError...), so the verdict's `error` text reads the same way.
 */
final class Failure extends RuntimeException {
    final String kind;

    Failure(String kind, String message) {
        super(message);
        this.kind = kind;
    }

    static Failure assertion(String message) {
        return new Failure("AssertionError", message);
    }

    static Failure type(String message) {
        return new Failure("TypeError", message);
    }

    static Failure value(String message) {
        return new Failure("ValueError", message);
    }

    /** Python renders a missing mapping key with its repr, so `KeyError: 'fdiv'`. */
    static Failure key(String key) {
        return new Failure("KeyError", "'" + key + "'");
    }

    /** The importer has no callback for this XCSP3 construct; the process exits 1 without a verdict. */
    static final class Unimplemented extends RuntimeException {
        Unimplemented(String what) {
            super(what);
        }
    }
}
