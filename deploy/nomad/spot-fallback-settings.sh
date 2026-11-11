#!/usr/bin/env bash
# Stage equivalent on-demand targets for every Spot pool after provisioning their aws-fleet counterparts.
# Inputs are capacity, queue, AWS adapter, Autoscaler settings, and `tofu output -json pools` of the fallback fleet.
# Writes private files in OUTPUT; never changes the live inputs or launches capacity. Review before installing.
set -euo pipefail
[[ $# == 6 ]] || { echo 'usage: spot-fallback-settings.sh CAPACITY QUEUE AWS_ADAPTER AUTOSCALER FLEET_OUTPUT OUTPUT' >&2; exit 2; }
output="$6"
files="$(jq -n --slurpfile capacity "$1" --slurpfile queue "$2" --slurpfile adapter "$3" --slurpfile scaler "$4" --slurpfile fleet "$5" '
  def fallback: sub("-spot$"; "") + "-on-demand";
  ($capacity[0].pools | map(select((.market // "spot") == "spot"))) as $spot |
  ($spot | map({key: .id, value: {id: (.id | fallback), nodeClass: (.nodeClass | fallback)}}) | from_entries) as $map |
  if ($spot | length) == 0 or any($spot[]; $fleet[0][$map[.id].id] == null) then error("missing provisioned on-demand fallback pools") else . end |
  if any($spot[]; .onDemandFallback != null) or any($capacity[0].pools[]; .id as $id | any($map[]; .id == $id)) then error("fallback already configured") else . end |
  ($spot | map(.id as $source | . + {id: $map[$source].id, nodeClass: $map[$source].nodeClass,
    market: "on-demand", warmNodes: 0, sustainedMs: 0})) as $pools |
  ($queue[0].targets | map(select(.template.nodeClass as $class | any($spot[]; .nodeClass == $class)) |
    .template.nodeClass as $class | ($spot | map(select(.nodeClass == $class)) | .[0].id) as $source |
    .market = "on-demand" | .template.nodeClass = $map[$source].nodeClass)) as $targets |
  if ($targets | length) != ($spot | length) then error("each Spot pool requires one queue target") else . end |
  {
    "capacity.json": ($capacity[0] | .pools = ((.pools | map(if $map[.id] then .onDemandFallback = $map[.id].id else . end)) + $pools)),
    "queue.json": ($queue[0] | .targets += $targets),
    "aws-adapter.json": ($adapter[0] | .pools += ($spot | map(.id as $source | {
      pool: $map[$source].id, group: $fleet[0][$map[$source].id].group_name, renewFunction: $fleet[0][$map[$source].id].renew_function}))),
    "autoscaler-settings.json": ($scaler[0] | .targets += ($spot | map(.id as $source |
      ($scaler[0].targets | map(select(.pool == $source)) | .[0]) as $original |
      if $original.driver != "aws-asg" then error("AWS fallback needs an aws-asg target for every Spot pool") else
      $original | .pool = $map[$source].id | .options.aws_asg_name = $fleet[0][$map[$source].id].group_name end)))
  }')"
umask 077
mkdir -p "$output"
for name in capacity.json queue.json aws-adapter.json autoscaler-settings.json; do
  jq --arg name "$name" '.[$name]' <<< "$files" > "$output/$name"
done
