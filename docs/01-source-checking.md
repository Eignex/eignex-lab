# 1. Independent source checking

The lab owns checking authority separately from a producer's acceptance. `SourceChecker` binds a retained complete
witness and objective to original model bytes through `sourceHashes`; source mismatches and missing artifacts stay
unchecked. A valid point supplies feasibility evidence. It supplies neither an optimum, a bound nor an independent
proof of a solver's terminal status. Checker policy, implementation identity, source and witness digests accompany
the verdict; changing a checking rule requires a new policy.

## Native optimum contradictions

`OptimumClaims` compares a native optimum with independently checked better points on the same original source
and all its dependencies, effective semantics, objective sense and units, and assumptions. Exact rational affine
maps transport reported objectives into original units before comparison; malformed exact values never fall back
to rounded display values. A point must carry a valid independent receipt with matching source and witness digests
and an identified checker. Failed, unsupported and incomplete checks provide no counterexample.

The whole-source exact integer XCSP3 adapter uses original source costs and its official cost-checking receipt.
Its authority is the published original-source optimum contract. This does not identify the internal proof scope
or supply a missing historical prepared snapshot; those remain unverified. Grid completion supplies no source
optimum claim. Explicit restricted scopes are never replaced by empty original-source assumptions.
MPS comparisons use `MpsNumericalSemantics` identities and the configured-policy verdict in `LabCheck.mpsChecks`,
including the reconstructed original objective, original-model scope and explicit empty assumptions. Its exact
verdict stays separate. Missing numerical declarations do not acquire inferred defaults. Other mapped or restricted
claims declare `optimalityScope` with schema `original-source-optimum-v1`, `semanticsSha256`, an original objective
`{sense, units, identity}`, and `assumptions`. Optional `objectiveMapping` contains exact `scale` and `offset`.
Their point receipt must bind that complete scope and original objective in `labCheck.optimumEvidence`; an original
model point check alone cannot validate additional assumptions.

Derived `labOptimumClaim` assessments distinguish `contradicted`, `unverified`, and `no-counterexample`. A
contradiction withdraws optimum credit while retaining point validity and raw native flags. Missing scope or a lack
of any scope-matched checked point remains unverified. No counterexample in supplied evidence leaves the native
proof independently unverified; no reference search runs during assessment. Claims and checker receipts remain
unchanged in storage and raw case exports.

The job/comparison pages, statistics, CSV qualification, scheduled confirmations and sweep trends consume derived
assessments. Retained checked points from other jobs, seeds, revisions and arms can contradict a claim; runtime or
machine equality is unnecessary for a mathematical counterexample. Proof-performance comparisons retain their
stricter matched execution controls. Contradicted or unverified optima cannot earn proof credit or confirmation
gains. Counts distinguish checked points, raw native claims, contradicted optima and unverified claims. Cached
trends refresh when checking receipts or finished evidence jobs change.

`bench/optimum-claims.json` replays bounded retained case/check receipts through the production assessment and
qualification paths, retaining the original captures, origins, assessments and summary. It accepts `captures` with
`{origin: {job}, case, expected}` entries; expected is `point`, `contradicted`, `unverified` or `no-counterexample`.
The existing `capture-zstd-base64` transport admits larger captures. Expectations assert observed states without
supplying checking authority. Replay performs neither assignment checking nor reference search; the retained
independent receipts provide point evidence.

## Exact rational MPS

`RationalMpsChecker` evaluates every numeric spelling under explicit policies. Its `mpsChecks` receipt retains
`lab-mps-exact-rational-v1` separately from `lab-mps-declared-rational-v1`; no fraction/decimal routing or implicit
legacy tolerance selection occurs. `MpsChecker` remains available as the separately identified legacy solchecker.
Both independent rational policies identify their evaluator source, configured-policy source and executing JAR
SHA-256 when packaged. No Klause parser, model, arithmetic, point validator or solver supplies checking authority.

The declared policy requires `numericalSemantics` with schema `mps-decimal-semantics-v1` and complete declared and
effective contracts: decimal literal authority, absolute/relative tolerance, default integer/real endpoints, exact
integrality and exact objective equality. Unsupported, malformed or absent declarations yield an unqualified
`unknown` configured verdict while retaining the exact verdict. Nothing infers semantics from witness spelling,
solver flags or an absent override banner.

Configured row slack is `max(absolute, relative * max(abs(bound), sum(abs(term activity))))`; column-bound slack
uses the finite bound magnitude. Each row side is checked separately. Default bound overrides apply only to wholly
unranged columns; explicit source bounds remain authoritative. Integrality and objective equality stay exact,
including nonterminating rational witnesses. The zero-tolerance policy permits no slack.

`MpsNumericalSemantics` canonicalizes effective rational values for policy identity. The receipt binds effective
semantics, independent checker rules, original source/witness SHA-256, claimed and reconstructed objectives, source
objective sense, original units, whole-model scope and empty assumptions. Comparison identity excludes witness
spelling and includes the source, effective policy, checker rules and objective scope. Matching identities permit
point comparisons only; they supply no optimum, bound or proof authority. Historical records and strict rejections
remain intact. A conditional replay may add a separately identified supplemental policy with `semanticsOrigin`,
without replacing a retained declaration or qualifying an absent historical one. Supplemental receipts identify
`declarationOrigin=conditional-replay` and `semanticsQualified=false`; their top-level verdict stays `unknown` while
the separate configured verdict records the conditional evaluation. Only a supported actual record declaration has
`declarationOrigin=solver-record` and `semanticsQualified=true`.

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

`bench/mps-replay.json` is the repository-owned remote replay manifest (`lab/mps-check.json` is its compatible alias). It accepts a bounded list of original records,
source paths/digests and expected exact verdicts, plus optional configured expectations and supplemental semantics origins. It freezes those inputs, runs the production router and `SourceChecker`,
and retains original record, source copy, effective control record and full `LabCheck`. A replay validates only those
retained artifacts; it cannot validate an absent historical capture. Campaign manifests and narratives belong outside
source; link the tracker/PR and lab receipts for a particular qualification.

## Weighted Boolean points

The independently owned OPB/WBO/WCNF route evaluates the original source, requires complete Boolean coordinates,
and uses BigInteger objectives and violated soft weights, including WBO's strict top threshold. It supplies point
and cost evidence only. No Klause parser, acceptance code or reference solver provides checking authority.

Its child owns decompression and checking within a 256 MiB heap, 10 CPU seconds, 15-second wall deadline and the
existing Linux 4 GiB address-space envelope. Source/witness, policy/version, JAR/classpath identities, complete
request/input artifacts and existing process receipts bind each check. Incomplete execution stays unchecked.
The [weighted replay contract and source specifications](runs.md) define supported layouts, additional input limits
and the distinction between SDK capture success and an actual checking verdict. `checker/weighted.json` owns that
route; `lab/mps-check.json` owns exact MPS replay.

## Original DIMACS SAT points

`DimacsChecker` registers `DIMACS` under `lab-dimacs-independent-complete-point-v2`, implementation
`eignex-lab-dimacs-complete-point-2`. It independently reads original CNF bytes and evaluates clauses without Klause
parsing, presolve, propagation, acceptance or solving. The executing JAR identifies the implementation and embeds
the evaluator source. Original model SHA-256 is mandatory. The existing source locator verifies the recorded plain
bytes, and the bounded child verifies its own snapshot again after decompression. Missing or changed source stays
`not-run`. A valid receipt establishes only the supplied SAT point; native UNSAT and optimality flags remain
independently unverified. DIMACS optimization/objective records and separate data files are unsupported.

The supported source is a single `p cnf n m` header followed by exactly `m` zero-terminated clauses. Clauses can span
lines or share a line; `c` comment lines and ASCII space/tab/CR/LF are accepted. Empty clauses are false; an empty
formula is true. Every nonzero source literal must name a coordinate in `1..n`. Unknown extensions, malformed or
truncated sources, extra headers/clauses and incorrect counts are `unsupported`, never accepted as a partial model.
All literals and clauses are consumed even after an earlier clause is false or a literal satisfies its clause.
The [SATLIB](https://www.cs.ubc.ca/~hoos/SATLIB/benchm.html) end marker is a standalone `%` line after all declared
clauses, optionally followed by one standalone `0` line. It cannot truncate a clause, replace a missing declared
clause or hide extra clauses before it. Other tokens after this marker are unsupported. Original source hashes
include the marker and trailer; neither ordinary checking nor replay rewrites those bytes.

The explicit **complete assignment** policy requires every original coordinate `1..n`, including unused variables,
exactly once. A witness consists of signed nonzero decimal integers after `v`, optionally on multiple `v` lines,
with one optional final `0`. The lab's complete final line may omit `0`. There is no bit-string interpretation,
implicit false value, extension, search or assignment repair. Duplicate, contradictory, malformed, out-of-range or
incomplete witnesses and false clauses are `invalid`; only invalid withdraws the source claim. Records without a
SAT solution claim receive no SAT check; a missing witness is `not-run`.

The existing point child owns UTF-8 decoding, zstd decompression (64 MiB window), parsing and evaluation within a
256 MiB heap, 10 CPU seconds, 15-second wall deadline and Linux 4 GiB address-space envelope. Snapshot transport and
expanded source are capped at 32 MiB, witness at 8 MiB characters, tokens at 32 characters, declared variables at
1,000,000, clauses at 2,000,000 and literal evaluations at 8,000,000. The evaluator retains only the source text,
bounded scanner tokens and a byte per declared coordinate. Input coverage/resource caps are `unsupported` with a
resource-limit reason; failed execution, timeout, memory excess, incomplete output or cleanup are `not-run`. Neither
state is mathematical rejection or acceptance. The parent requires a qualified existing process receipt before
using any child verdict. Receipts bind source/witness hashes, full request text and its hash, policy/version, JAR,
Java executable/classpath and source snapshot hashes, resource limits and completion/cleanup evidence.

`CaseChecks.afterCase` uses this registry when ordinary runner source validation is requested. `CaseChecks.recheck`
uses the same adapter on ended stored cases without solving. `checker/dimacs.json` replays frozen captures through
both actual paths and retains original/effective records, source, origin, ordinary/recheck receipts and resource
identity. Each explicit capture supplies `model` text, original `record`, a `control` object that may replace only
`finalWitness`, an `expected` checking state and `origin`. Pass a JSON list as the `captures` parameter; bounded
`capture-zstd-base64` transport is also accepted. Expected states are assertions on the checks, not authority for
acceptance. CI exercises positive, false-clause, incomplete, contradictory, resource and source-mismatch controls
using the already-built distribution; unit fixtures require no network or external tool provisioning.
