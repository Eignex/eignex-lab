#!/usr/bin/env bash
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$(mktemp -d)"
trap 'rm -rf "$root"' EXIT
printf '%s\n' '{"maxVcpus":16,"pools":[{"id":"general-spot","nodeClass":"linux-spot","vcpus":4,"memoryMiB":8192,"maxNodes":4,"tags":["arch:x86_64"]}]}' > "$root/capacity.json"
printf '%s\n' '{"targets":[{"template":{"nodeClass":"linux-spot","image":"immutable","budget":{"cpuMillis":2000,"memoryMiB":4096}},"tags":["arch:x86_64"],"market":"spot"}]}' > "$root/queue.json"
printf '%s\n' '{"profile":"adapter","pools":[{"pool":"general-spot","group":"spot","renewFunction":"spot-renew"}]}' > "$root/adapter.json"
printf '%s\n' '{"targets":[{"pool":"general-spot","driver":"aws-asg","options":{"aws_asg_name":"spot"}}]}' > "$root/scaler.json"
printf '%s\n' '{"general-on-demand":{"group_name":"on-demand","renew_function":"on-demand-renew"}}' > "$root/fleet.json"
render() { bash "$here/spot-fallback-settings.sh" "$root/capacity.json" "$root/queue.json" "$root/adapter.json" "$root/scaler.json" "$root/fleet.json" "$1"; }
render "$root/output"
jq -e '.maxVcpus == 16 and (.pools|length) == 2 and .pools[0].onDemandFallback == "general-on-demand" and
  .pools[1].market == "on-demand" and .pools[1].sustainedMs == 0 and .pools[1].warmNodes == 0 and
  .pools[0].tags == .pools[1].tags' "$root/output/capacity.json" > /dev/null
jq -e '.targets[1].market == "on-demand" and .targets[1].template.nodeClass == "linux-on-demand" and
  .targets[0].template.budget == .targets[1].template.budget and .targets[0].template.image == .targets[1].template.image' "$root/output/queue.json" > /dev/null
jq -e '.profile == "adapter" and .pools[1] == {pool:"general-on-demand",group:"on-demand",renewFunction:"on-demand-renew"}' "$root/output/aws-adapter.json" > /dev/null
jq -e '.targets[1].pool == "general-on-demand" and .targets[1].options.aws_asg_name == "on-demand"' "$root/output/autoscaler-settings.json" > /dev/null
printf '{}\n' > "$root/fleet.json"
if render "$root/missing" 2> /dev/null; then echo 'accepted missing infrastructure' >&2; exit 1; fi
[[ ! -e "$root/missing" ]]
echo 'ok Spot fallback settings preserve resources and require provisioned pools'
