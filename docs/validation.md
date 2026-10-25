# Validation and reference results

[Documentation home](../deploy/SETUP.md) · [Run specifications](runs.md) · [Measurements](measurements.md)

## Witness checking

Set `param.source-validation=true` in an experiment's `base` to check final witnesses against their original source.
The runner adds this request to scheduled Klause solver runs and their confirmations. Reference, profile, and
analysis jobs are excluded from that automatic addition.

The lab runs independent external checkers locally after a case returns, including cases solved on cloud nodes.
It verifies source hashes before checking. Corpus inputs must match the recorded plain bytes; repository fixtures
are read at the arm's pinned commit. MiniZinc model/data inputs must match as a pair. Missing or mismatched sources
remain unchecked. Tools are provisioned under `$LAB_DATA/checkers`; MiniZinc/Gecode and Z3 may need installation
or a binary override. A missing tool does not fail the solver job.

| Source | External checker | Main coverage limits |
| --- | --- | --- |
| XCSP3 | Pinned official XCSP3 solution checker, bundled with the lab | Lexicographic multi-objectives unsupported |
| MPS | Bundled MIPLIB solchecker source, compiled with the lab's input-length patch | Exact finite decimals only, up to 4095 characters; SOL lines up to 8190 characters |
| Integer MiniZinc | MiniZinc with Gecode | Float/approximate witnesses, `X_INTRODUCED_*` variables, and incompatible DZN output unsupported |
| SMT-LIB | Dolmen; Z3 fallback for algebraic `root-obj` models Dolmen cannot read | Model checking only; incremental sources and uninterpreted functions unsupported in the fallback |
| DIMACS, OPB, WCNF | No lab witness checker | Solver reports remain unchecked |

MPS values are never rounded to fit the checker. Its rational arithmetic uses upstream's default 1e-5 tolerances.
For algebraic SMT models, Z3 receives the original assertions plus exact pins for every declared constant; missing
values, tool errors, or an undecided result cannot earn validation. SMT invocations retain bounded process-resource
receipts, input hashes, and generated pinned input bytes for audit. No cvc5 cross-check of `root-obj` values is claimed.

| Check state | Effect on a solver claim |
| --- | --- |
| `valid` | Witness accepted; displayed as checked |
| `invalid` | Claim withdrawn from scoring; displayed as invalid solution |
| `unknown` | Claim retained, marked check indeterminate |
| `not-run` | Claim retained, marked unchecked; tool/source missing, failed, or timed out |
| `unsupported` | Claim retained, marked unchecked; checker cannot cover it |

**Witness validation does not prove optimality or infeasibility.** Only a definite rejection withdraws a claim;
checker failures and unsupported inputs do not turn into solver failures. Check provenance and policy differences
when comparing runs. Reference results are separate evidence and are never created from Klause witness checks.

Read verdicts in `lab cases <id> --json` as `record.labCheck`. They retain the policy, checker/version/binary hash,
source/witness hashes, time, reason, and available diagnostics/resource receipts. Raw bench records stay unchanged;
`lab-cases.jsonl` from a fetch carries the current lab verdicts.

To check an ended job's stored claims again, including a job whose bulk output was trimmed:

```sh
deploy/lab recheck 7
```

Rechecking builds and solves nothing. It replaces earlier lab verdicts and records counts in `recheck.json`.
Checker pins, exact policy identifiers, and format-specific behavior are defined alongside their implementations in
[checker/](../src/main/kotlin/com/eignex/lab/checker/) and
[Validation.kt](../src/main/kotlin/com/eignex/lab/Validation.kt).

## Reference solvers

An arm with `backend: "reference"` runs an independent solver and stores its result per collection/problem/solver.
Set `solver` to choose another supported solver:

| Source | Default | Other choices |
| --- | --- | --- |
| DIMACS/OPB/WCNF | clasp container | Kissat for CNF |
| XCSP3 | Source-preserving CP-SAT container | See [image details](../images/xcsp3-cpsat/README.md) |
| SMT-LIB | Z3 | cvc5 |
| MPS | SCIP container | HiGHS |
| MiniZinc | CP-SAT | Installed MiniZinc solvers such as Chuffed |

Reference solvers run through the lab's installed launcher. The Klause bench is used for selection, not for the
reference solve. Build container images using [operations](operations.md#docker); native binaries come from `PATH`
or `LAB_Z3`, `LAB_CVC5`, `LAB_KISSAT`, and `LAB_HIGHS`. `LAB_Z3_MEMORY_MB` defaults to 2048.
Checker versions and solver options form the recorded identity. Whole-source SMT reference verdicts also require
complete resource receipts; incomplete execution remains undecided.

Committed [reference specifications](../deploy/specs/) cover routine backfills and frozen QF_NRA suites. Pin a
compatible Klause commit when executing a versioned catalog. `reference=any` keeps frozen membership independent
of existing verdicts or frontend declines; a fetched archive's logic tag alone does not certify polynomial membership.

## Interpret and refresh references

The Reference page compares outcomes against stored verdicts, highlights contradictions and worse incumbents,
and shows coverage. Timing splits an equal score only when budgets match and the lab arm used one core; other
comparisons are quality-only. Run a paired experiment at the same budget for a speed comparison.

Rows count only against the same source-file identity. A proof contradicted by another solver's solution is set
aside for comparisons while both rows remain as evidence. Stronger results normally survive weaker reruns;
changed source or solver-policy identities replace the old row. A stored reference is evidence, not an independent
proof certificate.

```sh
deploy/lab references bacp
deploy/lab references retire collection-name solver-name 300
```

Retiring marks matching rows stale without deleting their history. Then use a reference experiment with
`reference: "missing"` to backfill that solver, for example at a new budget. When all arms name the same solver,
the selection filter reads that solver's rows only. The [reference adapters](../src/main/kotlin/com/eignex/lab/reference/)
define each solver's options, result qualification, and source-preservation policy.
