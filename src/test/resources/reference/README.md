# Reference controls

`affine-objective.mzn` is a self-authored deterministic MiniZinc control for the
lab's native reference CLI and identity receipts. Its minimum is -5 at x = 3.
It needs no data, includes, network or source generation. Run it only through the
lab; unit tests use deterministic metadata fixtures without invoking a solver.
