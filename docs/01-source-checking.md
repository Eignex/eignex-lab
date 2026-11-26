# 1. Independent source checking

The lab checks retained assignments against original source bytes through pinned external checkers: the official
XCSP3 solution checker, Dolmen for SMT-LIB models and VeriPB for OPB and WCNF points. The only lab-owned evaluator
is the complete DIMACS SAT assignment check. MiniZinc, MPS and WBO have no lab assignment checker. Reference solver
reports retain their native claims; MPS assignments are neither evaluated nor repaired by the lab.

`SourceChecker` binds a complete witness and objective to original model bytes through `sourceHashes`; source
mismatches and missing artifacts stay unchecked. A valid point supplies feasibility evidence, never an optimum,
bound or independent proof of a solver's terminal status. Checker policy, implementation identity, source and
witness digests accompany each verdict. Changing a checking rule requires a new policy. Historical receipts remain
stored under their original policies, including retired checker receipts; removal does not rewrite evidence.

## Solved and fully decided results

A satisfaction problem is fully decided by either SAT or UNSAT. Optimization counts an incumbent as solved and a
native optimum or infeasibility result as fully decided. Missing, unsupported or indeterminate point checks do not
suppress native completion credit. A checker rejection or a scope-matched checked counterexample withdraws the
rejected claim. Float approximations require source acceptance and supply no original-source completion claim.
Summary and trend APIs retain the `proven` field for the fully decided count or share. Native completion claims
and independent receipts remain separate evidence: counting completion does not create a checked proof.

## Native optimum contradictions

`OptimumClaims` compares native optima with independently checked better points on matching original sources,
effective semantics, objective sense, units and assumptions. Exact rational affine maps transport objectives into
original units; malformed exact values never fall back to rounded display values. Points require valid independent
receipts with matching source and witness digests and an identified checker. Failed or incomplete checks supply no
counterexample. The default integer source contract is XCSP3 under its official cost-checking policy and semantic
identity SHA-256 of `xcsp3-integer-source-v1`. It does not depend on the producer's LP `exact` switch.

Mapped or restricted claims declare `optimalityScope` with schema `original-source-optimum-v1`,
`semanticsSha256`, original objective `{sense, units, identity}` and `assumptions`. Optional
`objectiveMapping` contains exact `scale` and `offset`. The independent point receipt must bind that complete
scope and original objective in `labCheck.optimumEvidence`. An original-model point check alone cannot validate
additional assumptions.

A gap optimum carries `optimalityBound` in exact original units: lower for minimization, upper for maximization.
Only a checked point strictly beyond that bound contradicts the claim. A better incumbent inside the gap or equal
to the bound does not. `optimalityTolerance` describes the producer's declared and effective gap; comparison uses
the retained bound without reconstructing it from tolerances. Missing or null bounds retain exact-optimum semantics.
Malformed bounds, scope or objective metadata leave independent assessment unavailable.

Derived `labOptimumClaim` assessments distinguish `contradicted`, `unverified` and `no-counterexample`.
An unverified assessment describes missing independent evidence and leaves native completion counting. Absence of
counterexamples supplies no independent proof. A contradiction withdraws optimum credit while retaining point
validity and raw native flags. Stored claims and receipts remain unchanged. Retained checked points from other
jobs, seeds, revisions and arms can contradict a claim without machine or runtime equality. Proof-performance
comparisons retain their execution controls. Summaries, trends and confirmations share these assessments.
Receipt revisions and finished evidence jobs invalidate cached scoring records and trends across store connections.

`bench/optimum-claims.json` replays bounded retained receipts through assessment and scoring, with no assignment
checking or reference search. Its `captures` entries contain `{origin: {job}, case, expected}`; expected is
`point`, `contradicted`, `unverified` or `no-counterexample`. Expected states assert observations without
supplying checking authority. The bounded `capture-zstd-base64` transport is shared with DIMACS replay: compressed
parameters are limited to 120 KiB and decoded text to 24 MiB.

## OPB and WCNF points

`PbChecker` registers `OPB` under `lab-opb-veripb-3.0.2-point-v1` and `WCNF` under `lab-wcnf-veripb-3.0.2-point-v2`.
[VeriPB](https://gitlab.com/MIAOresearch/software/VeriPB) 3.0.2 (MIT OR Apache-2.0) publishes no binaries, so the
`veripb` workflow builds the crates.io crate (SHA-256 `c1b9da43…c4e28b9`) with Rust 1.89.0 and `cargo build --locked`
into this repository's `veripb-v3.0.2` release: static Linux x86-64 and macOS arm64. The lab downloads its platform's
binary into `$LAB_DATA/checkers/veripb-3.0.2/` and checks it against the SHA-256 pinned in `VeriPb`.

The lab writes a four-line proof: `sol` with the record's final witness, `output NONE`, then `conclusion SAT` or,
for an optimization claim, `conclusion BOUNDS <-10^100> <claimed objective>` (negated for a `max:` objective, which
VeriPB reads negated). VeriPB requires the witness extended by unit propagation to be a full assignment satisfying
every constraint, and its objective value to equal the claim exactly. The lower bound is trivially implied, so a
valid receipt establishes a feasible point with that objective and nothing about optimality or UNSAT claims.
The proof is retained in the receipt's `inputs`.

Witnesses are competition `v` lines, translated literal by literal without interpretation: OPB names negated by `-`
(or `~`); WCNF as one MaxSAT Evaluation 2024 bit string (character `i` is variable `i`) or signed variable numbers
with an optional final `0`. Other lines or tokens are `invalid`, as are VeriPB's rejections: a constraint conflict,
a propagated assignment that falsifies a constraint, a contradictory witness or an objective other than the claim.
A fractional claim is `unknown`. VeriPB reads linear OPB and WCNF with `h` lines; WBO, nonlinear OPB and the older
`p wcnf` format are `unsupported` in VeriPB's words. An objective variable the witness leaves unassigned is `unknown`.

VeriPB turns a WCNF soft clause `C` with several literals, the `i`th clause of the file counting hard ones, into the
constraint `C or ~_b<i>` with objective term `~_b<i>`; no witness assigns `_b<i>`. A falsified `C` propagates it
false. For each such clause the witness satisfies, the lab reads the source as VeriPB's parser does and logs `_b<i>`
true after the witness, the only value with which that clause costs nothing. VeriPB still checks every constraint and
the exact objective, so a misnumbered variable can only be rejected, never accepted. Policy `v1` logged none, which
left every point satisfying such a clause `unknown`.

VeriPB runs under the bounded tool envelope Dolmen uses, with 60 CPU seconds, a 65-second deadline, a 4 GiB
address space and complete output and cleanup evidence. Anything else it prints is `unknown`; a timeout or
incomplete envelope is `not-run`.

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
uses the same adapter on ended stored cases without solving. Its `recheck.json` counts every completed case and
lists per-case `outcomes`, including `unsupported` formats, `not-run` failures and `skipped` records without a
Klause solution claim. `checked` counts replacement lab receipts; `states` counts all reported outcomes. Unsupported
formats and attempts that produce no receipt leave earlier receipts and producer records intact. Historical
recheck reports without `outcomes` remain readable. Checker availability is optional: an unsupported format is a
normal outcome and does not fail the recheck job. There is no expectation that every source format has a checker.
`checker/dimacs.json` replays frozen captures through
both actual paths and retains original/effective records, source, origin, ordinary/recheck receipts and resource
identity. Each explicit capture supplies `model` text, original `record`, a `control` object that may replace only
`finalWitness`, an `expected` checking state and `origin`. Pass a JSON list as the `captures` parameter; bounded
`capture-zstd-base64` transport is also accepted. Expected states are assertions on the checks, not authority for
acceptance. CI exercises positive, false-clause, incomplete, contradictory, resource and source-mismatch controls
using the already-built distribution; unit fixtures require no network or external tool provisioning.
