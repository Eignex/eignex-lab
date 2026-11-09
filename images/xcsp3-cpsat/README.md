# XCSP3 cp-sat reference image

The lab's independent XCSP3 reference solver. A JVM program parses the original XCSP3 `.xml` with XCSP3-Java-Tools
and solves it with OR-Tools cp-sat through its Java bindings, so no FlatZinc conversion is involved and the image holds
no Python. The bench runs the image per instance as `eignex-lab-xcsp3-cpsat:source-preserving-v3` and parses the
`o` and `v` lines and the JSON verdict that follows them. `Main.VERSION` must match the bench's
`Xcsp3CpSatReference.VERSION` and the image tag (`Xcsp3CpSatReferenceTest` checks both); change them together.

```sh
docker build --target test images/xcsp3-cpsat        # the JUnit suite, on the image's own JVM and OR-Tools build
docker build -t eignex-lab-xcsp3-cpsat:source-preserving-v3 images/xcsp3-cpsat
docker run --rm -v "$PWD/images/xcsp3-cpsat/src/test/resources:/in:ro" \
  eignex-lab-xcsp3-cpsat:source-preserving-v3 /in/smoke.xml 10 1 3
```

Arguments are `<instance.xml> [time_limit_seconds=10] [workers=1] [seed=3]`. The output contract is the one of the
CPMpy importer this replaced (policy `xcsp3-source-preserving-v2`): for a solution an `o <cost>` line (optimisation
only) and a `v <instantiation>` line with every declared variable, then one JSON line in Python's `json.dumps` form
with `version`, `exit` (OPTIMAL, FEASIBLE, UNSATISFIABLE, UNKNOWN or ERROR), `runtime`, `objective`, `maximize`,
`exactObjective`, `witness`, `seed`, `workers` and `cpModelPresolve`. A model the importer rejects is an ERROR verdict
naming the same kind of failure; a construct it has no callback for (symbolic variables, `minimumArg`, `flow`,
`clause`, binPacking with `conditions`, covered precedence, product and lex objectives) ends the process with status 1
and no verdict. Parser and JVM diagnostics go to stderr.

The loader follows PyCSP3's dispatch and the CPMpy callbacks constraint by constraint, including their limits: integer
`if` terms and non-`le` cumulatives fail as CPMpy 1.1 did, circuits keep node 0 in the circuit, binPacking constrains
bins `0..n-1`, and several objectives are rejected. Partial functions follow CPMpy's relational semantics: an undefined
division or modulo makes its nearest Boolean context false. The importer uses column-count strides for row-major
matrix selection and enforces both source index bounds, and negative extension tuples keep their complement semantics
under source-domain exclusions through explicit conflict clauses. Two deliberate differences: reified and soft
constraints are rejected instead of being posted unreified, and a table whose tuples all fall outside the domains (the
XCSP3 parser drops them) is unsatisfiable for supports and void for conflicts, as CPMpy treated the kept tuples.

Every build input is pinned: the Temurin base images by digest, OR-Tools 9.15.6755 (the release the CPMpy image
pinned), protobuf-java and the XCSP3-Java-Tools revision of `xcsp3-tools/build.gradle.kts` by SHA-256. A training
solve of `smoke.xml` at build time fills an AOT cache that later container starts map.

`SolveTest` holds the cases of the former `test_solve.py` (self-authored constant and variable matrix fixtures,
grouped conditions, axis-bound failures, weighted expression-sum, contradiction, unconstrained-variable and wide
objective controls, all Apache-2.0), the command-line contract, and one self-authored instance per constraint family
whose verdicts and optima are those the CPMpy image produced for the same files.
