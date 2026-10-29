# 1. Independent source checking

The lab owns checking authority separately from a producer's acceptance. `SourceChecker` binds a retained complete
witness and objective to original model bytes through `sourceHashes`; source mismatches and missing artifacts stay
unchecked. A valid point supplies feasibility evidence. It supplies neither an optimum, a bound nor an independent
proof of a solver's terminal status. Checker policy, implementation identity, source and witness digests accompany
the verdict; changing a checking rule requires a new policy.

## Exact rational MPS

`RationalMpsChecker` routes rational coordinates/objectives directly to `ExactMpsChecker`; other records first use
the existing MIPLIB solchecker. Its unsupported layouts can use the exact route. A legacy valid/invalid result keeps
its original checker and tolerance policy. Exact results identify `lab-mps-exact-rational-v1`, with version/source
SHA-256 and the executing JAR SHA-256 when packaged. The evaluator source is included in that JAR. No Klause MPS
parser, model, arithmetic, point validator or solver is used by the exact route.

The exact route evaluates every declared column and row from the original source. It uses normalized BigInteger
rationals, never floating conversion, decimal approximation or point repair. Its row, bound, integrality and
objective tolerances are zero, including for non-terminating values such as `1/3`. A rounded decimal that fails an
exact row stays invalid. Omitted or duplicate coordinates stay incomplete/malformed; omitted coordinates never
silently become zero. All coordinates must be supplied, including columns whose objective coefficient is zero.

Supported MPS sections are `NAME`, optional `OBJSENSE`, `ROWS`, `COLUMNS`, optional `RHS`, `RANGES`, `BOUNDS`,
and `ENDATA`, in that order. Whitespace field layouts (including ordinary fixed-column records without spaces in
names), one/two row-value pairs, decimal/exponent literals and rational witness values are supported. Missing RHS
means zero; the first `N` row is the objective and later `N` rows are free. `RANGES` follows the standard L/G/E
signed-range rules. The objective is its row activity minus its RHS offset, in original source units for either
MIN or MAX; maximizing does not negate the reported objective. These conventions follow
[Gurobi's MPS specification](https://docs.gurobi.com/projects/optimizer/en/current/reference/fileformats/modelformats.html)
and [IBM's MPS record specification](https://www.ibm.com/docs/en/cofz/12.9.0?topic=standard-records-in-mps-format).

Real columns default to `[0,+infinity)`. Integer marker columns default to `[0,1]` unless explicit bounds replace
that default; supported bound records are LO, UP, FX, FR, MI, PL, BV, LI and UI. BV/LI/UI also declare integrality.
Unknown sections, semi-continuous bounds, indicators, multiple RHS/range/bound vectors and ambiguous extensions
are unsupported, never ignored as a route to acceptance. Syntax/section errors produce `unknown` with `parser
failure`, unsupported features/resource limits produce `unsupported`, deadline expiry produces `not-run` with
`timeout`, incomplete witnesses produce `unknown`, and checked violations produce `invalid`. Only invalid withdraws
a claim. Cancellation propagates instead of publishing a point.

The evaluator owns its temporary parsed model and discards it after the call. Plain or zstd-decoded source is capped
at 16 MiB (zstd window capped at 64 MiB), witness at 8 MiB, line at 8,190 characters, numeric token at 4,095 characters,
decimal scale at +/-4,095, rows and columns at 50,000 each, terms at 200,000, rational operands/intermediates at
16,384 bits (arithmetic temporaries at most 32,769 bits), operations at 2,000,000 and elapsed evaluation at 120 seconds. Deadline/cancellation checks occur while
reading, parsing, checking each coordinate and evaluating each term; each BigInteger operation has bounded operands.
Source lookup remains the existing hash-bound `SourceLocator` contract. A resource refusal supplies no verdict on
feasibility. Receipts record limits, operation count, arithmetic policy and elapsed time without inventing child
process resource receipts for this in-process evaluator.

`lab/mps-check.json` is the repository-owned remote replay manifest. It accepts a bounded list of original records,
source paths/digests and expected verdicts. It freezes those inputs, runs the production router and `SourceChecker`,
and retains original record, source copy, effective control record and full `LabCheck`. A replay validates only those
retained artifacts; it cannot validate an absent historical capture. Campaign manifests and narratives belong outside
source; link the tracker/PR and lab receipts for a particular qualification.
