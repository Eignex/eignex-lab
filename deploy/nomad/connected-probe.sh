#!/usr/bin/env bash
# Real worker -> private API -> durable Store acceptance against an isolated Nomad cluster.
#
# connected-probe.sh
#
# Starts a sandbox (sandbox.sh) in build/nomad-connected on ports 14676-14678 and a loopback OCI registry, builds and
# pushes the worker image from a context that lost its executable bits, and runs `gradlew :verifyConnectedNomad`
# against them. Stops both again however it ends. Needs Docker, curl, jq, unzip and openssl.
# shellcheck disable=SC1091,SC2154 # sandbox.sh, checked on its own, sets root and state
set -o pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/sandbox.sh"

started=0
registered=0
registry=""
succeeded=0

# finish: on failure the failed allocations' error output; then stop the sandbox and the registry.
finish() {
  local job allocation
  if ((started && !succeeded)); then
    for job in $(api jobs 2> /dev/null | jq -r '.[].ID'); do
      for allocation in $(api "job/$job/allocations" 2> /dev/null | jq -r '.[] | select(.ClientStatus == "failed") | .ID'); do
        cli alloc logs -stderr "$allocation" worker >&2
      done
    done
  fi
  if ((started)); then (stop); fi
  if ((registered)); then docker stop "$registry" > /dev/null; fi
}

main() {
  local connected="$root/build/nomad-connected" lock token password trust java_home image context name broken digest node status
  { mkdir -p "$connected" && chmod 700 "$connected"; } || fail "cannot create $connected"
  lock="$connected/probe.lock"
  exec 9>> "$lock" || fail "cannot open $lock"
  if command -v flock > /dev/null; then
    flock -n 9 || fail "another connected probe holds $lock"
  else
    perl -MFcntl=:flock -e 'open(my $f, ">>&=", 9) or die "$!\n"; flock($f, LOCK_EX | LOCK_NB) or exit 1' ||
      fail "another connected probe holds $lock"
  fi
  sandbox_init "$connected" 14676
  registry="eignex-nomad-connected-$(printf %s "$state" | hashicorp_sha256 /dev/stdin | cut -c1-10)"
  trap finish EXIT
  started=1
  start
  run cli acl policy apply probe-coordinator "$root/deploy/nomad/coordinator-policy.hcl" > /dev/null || exit 1
  token="$(run cli acl token create -json -name=probe-coordinator -policy=probe-coordinator | jq -er .SecretID)" ||
    fail "cannot create the coordinator token"
  { (umask 077 && printf %s "$token" > "$state/coordinator.token") && chmod 600 "$state/coordinator.token"; } ||
    fail "cannot write the coordinator token"
  password="$state/store-password"
  (umask 077 && printf %s isolated-probe-keystore > "$password") || fail "cannot write $password"
  run openssl pkcs12 -export -in "$state/global-cli-nomad.pem" -inkey "$state/global-cli-nomad-key.pem" \
    -out "$state/client.p12" -passout "file:$password" > /dev/null || exit 1
  trust="$state/trust.p12"
  rm -f "$trust"
  java_home="$(cd "$root" && run ./gradlew -q printJavaHome)" || exit 1
  run "$java_home/bin/keytool" -importcert -noprompt -alias nomad -file "$state/nomad-agent-ca.pem" \
    -keystore "$trust" -storetype PKCS12 -storepass:file "$password" > /dev/null || exit 1
  chmod 600 "$trust" "$state/client.p12"
  image=127.0.0.1:15007/lab-worker:connected-probe
  run docker run -d --rm --name "$registry" -p 127.0.0.1:15007:5000 --label eignex.lab.nomad.connected=true \
    registry@sha256:01cd312e67026f06f5d89c3e133a4a9adc7cb9eeb9b1194e0b2954dfd770efd2 > /dev/null || exit 1
  registered=1
  (cd "$root" && ./gradlew installDist) || fail "installDist failed"
  # Simulate an archive/build context that lost executable bits, including the packaged JVM launcher.
  context="$(mktemp -d "$state/image-context-XXXXXX")" || fail "cannot create an image context"
  { mkdir -p "$context/build/install" && cp -R "$root/build/install/eignex-lab" "$context/build/install/"; } ||
    fail "cannot copy the installed distribution"
  for name in deploy/nomad/worker.Dockerfile deploy/nomad/worker-entrypoint.sh src/main/resources/nomad/process_supervisor.py; do
    { mkdir -p "$(dirname "$context/$name")" && cp "$root/$name" "$context/$name"; } || fail "cannot copy $name"
  done
  chmod 644 "$context/build/install/eignex-lab/bin/eignex-lab" "$context/deploy/nomad/worker-entrypoint.sh" \
    "$context/src/main/resources/nomad/process_supervisor.py"
  docker build -f "$context/deploy/nomad/worker.Dockerfile" --build-arg WORKER_RELEASE=connected-probe -t "$image" \
    "$context" || fail "cannot build the worker image"
  rm -rf "$context"
  bounded 30 docker run --rm --network none "$image" --smoke-test || fail "the worker image smoke test failed"
  broken="$(bounded 30 docker run --rm --network none --entrypoint sh "$image" -c \
    "chmod a-x /opt/lab/bin/eignex-lab; exec /opt/lab/worker-entrypoint.sh --smoke-test" 2>&1 > /dev/null)"
  status=$?
  ((status == 126)) && [[ "$broken" == */opt/lab/bin/eignex-lab* ]] ||
    fail "a non-executable launcher exited with $status rather than 126 naming it: $broken"
  run docker push "$image" > /dev/null || exit 1
  digest="$(run docker image inspect "$image" | jq -er '.[0].RepoDigests[0]')" || fail "the pushed image has no digest"
  node="$(one_ready_node "the connected probe requires exactly one ready sandbox node")" || exit 1
  (
    cd "$root" &&
      LAB_NOMAD_INTEGRATION=1 LAB_NOMAD_TEST_STATE="$state" LAB_NOMAD_TEST_ADDRESS=https://127.0.0.1:14676 \
        LAB_NOMAD_TEST_IMAGE="$digest" LAB_NOMAD_TEST_API_PORT=18091 \
        LAB_NOMAD_TEST_MHZ="$(jq -r '.Attributes["cpu.usablecompute"]' <<< "$node")" \
        LAB_NOMAD_TEST_CPUS="$(jq -r .NodeResources.Cpu.TotalCpuCores <<< "$node")" \
        bounded 600 ./gradlew :verifyConnectedNomad
  ) || fail "the connected Nomad verification failed"
  succeeded=1
  echo "Connected Nomad worker, signed identity, hard limits, paired commit and restart checks passed."
}

main "$@"
