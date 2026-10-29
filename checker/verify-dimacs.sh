#!/usr/bin/env bash
# CI integration controls; use the already-built distribution, no setup/provisioning/network.
set -euo pipefail
mkdir -p "$PWD/build/dimacs-controls"
scratch=$(mktemp -d "$PWD/build/dimacs-controls/run-XXXXXX")
classpath="$PWD/build/install/eignex-lab/lib/*"
run() { java -Xmx256m -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -cp "$classpath" com.eignex.lab.DimacsCheckBenchKt "$@"; }
model=$'p cnf 2 2\n1 0\n2 0\n'
resource=$'p cnf 1000001 0\n'
model_sha=$(printf '%s' "$model" | sha256sum | cut -d ' ' -f1)
resource_sha=$(printf '%s' "$resource" | sha256sum | cut -d ' ' -f1)
jq -n --arg model "$model" --arg resource "$resource" --arg model_sha "$model_sha" --arg resource_sha "$resource_sha" '
  def capture($source; $sha; $witness; $expected): {
    model: $source, record: {solver:"klause",kind:"satisfy",feasible:true,proven:false,finalWitness:$witness,sourceHashes:{model:$sha}},
    control:{}, expected:$expected, origin:"deterministic CI control"
  };
  {parameters:{captures: ([capture($model; $model_sha; "v 1 2 0"; "valid"),
    capture($model; $model_sha; "v -1 2 0"; "invalid"),
    capture($model; $model_sha; "v 1 0"; "invalid"),
    capture($model; $model_sha; "v 1 -1 2 0"; "invalid"),
    capture($resource; $resource_sha; "v 1"; "unsupported"),
    capture($model; ("0" * 64); "v 1 2 0"; "not-run")] | tojson)}}' > "$scratch/request.json"
run plan "$scratch/request.json" > "$scratch/plan.json"
for index in 0 1 2 3 4 5; do
  jq ".cases[$index]" "$scratch/plan.json" > "$scratch/case.json"
  run run "$scratch/case.json" "$scratch/out-$index"
  jq -e '.status == "success"' "$scratch/out-$index/result.json"
  if (( index < 5 )); then
    jq -e '.details | [.ordinary, .recheck] | all(.resourceReceipts | length == 1)' "$scratch/out-$index/result.json"
  fi
done
