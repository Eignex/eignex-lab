set -eu
echo "arch=$(uname -m)"
case "$(uname -s)" in
Linux)
  awk -F: '/^(model name|Hardware|Processor)[[:space:]]*:/ { sub(/^[ \t]+/, "", $2); print "model=" $2; exit }' /proc/cpuinfo
  # Keep features common to every reported CPU rather than unioning a heterogeneous machine's flags.
  awk -F: '/^(flags|Features)[[:space:]]*:/ {
    cpus++; delete seen; n=split($2, flags, /[ \t]+/);
    for (i=1; i<=n; i++) if (flags[i] != "" && !seen[flags[i]]++) counts[flags[i]]++;
  } END {
    printf "features="; for (flag in counts) if (counts[flag] == cpus) printf "%s ", flag;
    printf "\ndetected=%d\n", (cpus > 0);
  }' /proc/cpuinfo
  ;;
Darwin)
  model=$(sysctl -n machdep.cpu.brand_string 2>/dev/null || true)
  echo "model=$model"
  features=$(sysctl -n machdep.cpu.features machdep.cpu.leaf7_features machdep.cpu.extfeatures 2>/dev/null || true)
  features=$(printf '%s' "$features" | tr '[:upper:]' '[:lower:]' | sed -e 's/avx1\.0/avx/g' -e 's/avx2\.0/avx2/g' -e 's/sse4\./sse4_/g')
  for feature in neon:hw.optional.neon sve:hw.optional.arm.FEAT_SVE sve2:hw.optional.arm.FEAT_SVE2 sme:hw.optional.arm.FEAT_SME sme2:hw.optional.arm.FEAT_SME2; do
    if [ "$(sysctl -n "${feature#*:}" 2>/dev/null || true)" = 1 ]; then features="$features ${feature%%:*}"; fi
  done
  printf 'features=%s\n' "$(printf '%s' "$features" | tr '\n' ' ')"
  if [ -n "$features" ]; then echo detected=1; else echo detected=0; fi
  ;;
*) echo detected=0 ;;
esac
