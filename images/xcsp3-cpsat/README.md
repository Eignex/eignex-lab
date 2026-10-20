# XCSP3 cp-sat reference image

The klause bench's XCSP3 reference solver. CPMpy reads the original XCSP3 `.xml` and solves it with
OR-Tools cp-sat, so no FlatZinc conversion is involved. The bench runs the image per instance as
`klause-xcsp3-cpsat:source-preserving-v2` and parses the JSON verdict that follows the `o` and `v` lines.
`solve.py` reports `VERSION`, which must match the bench's `Xcsp3CpSatReference.VERSION`; change both together
and retag the image.

```sh
docker build -t klause-xcsp3-cpsat:source-preserving-v2 images/xcsp3-cpsat
docker run --rm --entrypoint python -v "$PWD/images/xcsp3-cpsat:/tests:ro" -w /tests \
  klause-xcsp3-cpsat:source-preserving-v2 test_solve.py
```

The importer uses column-count strides for row-major matrix selection and enforces both source index
bounds. Negative extension tuples keep their complement semantics under source-domain exclusions through
explicit conflict clauses.

`test_solve.py` contains self-authored constant and variable matrix fixtures, grouped conditions and
axis-bound failures. Its weighted expression-sum, contradiction, unconstrained-variable and wide objective
controls are self-authored Apache-2.0 sources.
