# Cache extraction never touches an installation until the complete download and validation succeed.
# validate_lab_cache <root> install|gradle: the solver installs, or the Gradle wrapper's distribution, under <root>
# are complete: every jar's entries pass unzip's CRC test and the classes they are run by are there.
validate_lab_cache() {
    if [ "$2" = install ]; then
        validate_lab_distribution "$1" com/eignex/klause/cli/MainKt.class \
            klause-cli/build/install/klause-cli-jvm/bin/klause-cli klause-cli/build/install/klause-cli-jvm/bin/klause-cli-jvm \
            klause-cli/build/install/klause-cli/bin/klause-cli klause-cli/build/install/klause-cli/bin/klause-cli-jvm &&
            validate_lab_distribution "$1" com/eignex/klause/bench/target/BenchCli.class klause-bench/build/install/klause-bench/bin/klause-bench
        return
    fi
    local wrapper="$1/.gradle/wrapper" dist jar any=0 bootstrap
    [ -d "$wrapper" ] || { echo 'missing Gradle wrapper cache' >&2; return 1; }
    while IFS= read -r -d '' dist; do
        case "$(basename "$(dirname "$dist")")" in gradle-*) ;; *) continue ;; esac
        any=1
        bootstrap=0
        while IFS= read -r -d '' jar; do
            unzip -tqq "$jar" >/dev/null 2>&1 || { echo "corrupt Gradle library: $jar" >&2; return 1; }
            if lab_jar_has "$jar" org/gradle/launcher/bootstrap/ProcessBootstrap.class; then bootstrap=1; fi
        done < <(find "$dist" -name '*.jar' -print0)
        [ "$bootstrap" = 1 ] || { echo "incomplete Gradle bootstrap: $dist" >&2; return 1; }
    done < <(find "$wrapper" -name lib -print0)
    [ "$any" = 1 ] || { echo 'missing extracted Gradle distribution' >&2; return 1; }
}

# validate_lab_distribution <root> <main class> <launcher>...: the first of the launchers that is there, else the first
# named, is executable, and its distribution's jars are complete, hold <main class> and are all its classpath names.
validate_lab_distribution() {
    local root=$1 main=$2 launcher="" candidate dist jar found=0 refs ref
    shift 2
    for candidate in "$@"; do
        if [ -f "$root/$candidate" ]; then launcher="$root/$candidate"; break; fi
    done
    [ -n "$launcher" ] || launcher="$root/$1"
    [ -f "$launcher" ] && [ -x "$launcher" ] || { echo "missing executable: $launcher" >&2; return 1; }
    dist=$(dirname "$(dirname "$launcher")")
    set -- "$dist"/lib/*.jar
    [ -e "$1" ] || { echo "missing libraries: $dist" >&2; return 1; }
    for jar in "$@"; do
        unzip -tqq "$jar" >/dev/null 2>&1 || { echo "corrupt library: $jar" >&2; return 1; }
        if lab_jar_has "$jar" "$main"; then found=1; fi
    done
    [ "$found" = 1 ] || { echo "missing main class: $main" >&2; return 1; }
    refs=$(grep -oE '\$(APP_HOME|\{APP_HOME\})/lib/[^:[:space:]"'\'']+\.jar' "$launcher" | sed -E 's#^\$(\{APP_HOME\}|APP_HOME)/lib/##') || true
    [ -n "$refs" ] || { echo "missing launcher classpath: $launcher" >&2; return 1; }
    while IFS= read -r ref; do
        [ -f "$dist/lib/$ref" ] || { echo "incomplete classpath: $launcher" >&2; return 1; }
    done <<< "$refs"
}

# lab_jar_has <jar> <entry>: whether the jar holds the entry. grep reads the whole listing, so pipefail sees no SIGPIPE.
lab_jar_has() {
    unzip -Z1 "$1" 2>/dev/null | grep -Fx -- "$2" >/dev/null
}

restore_lab_cache() {
    local cache_stage
    cache_stage=$(mktemp -d) || return 1
    mkdir -p "$cache_stage/content" || { rm -rf "$cache_stage"; return 1; }
    if aws s3 cp "$1" "$cache_stage/archive.zst" --region "$4" --only-show-errors &&
       zstd -dcq "$cache_stage/archive.zst" | tar -x -C "$cache_stage/content" &&
       validate_lab_cache "$cache_stage/content" "$3"; then
        # A failed copy is fatal: do not build against a partly copied dependency cache.
        cp -a "$cache_stage/content/." "$2/" || { rm -rf "$cache_stage"; exit 1; }
        rm -rf "$cache_stage"
        return 0
    fi
    echo "cache rejected: $1" >&2
    rm -rf "$cache_stage"
    return 1
}

publish_lab_cache() {
    local cache_archive
    cache_archive=$(mktemp) || return 0
    # Pack to a local file first: streaming tar into S3 can publish a truncated but valid object.
    if (cd "$2" && tar -c "${@:4}") | zstd -q -T0 > "$cache_archive"; then
        aws s3 cp "$cache_archive" "$1" --region "$3" --only-show-errors || echo "cache upload failed: $1" >&2
    else
        echo "cache packing failed; not published: $1" >&2
    fi
    rm -f "$cache_archive"
}

# validate_lab_provenance <worktree>: the bench's provenance manifest, when it wrote one, names a CLI launcher installed
# in the worktree, and every file of that launcher's distribution it lists is inside it and has the hash it lists.
validate_lab_provenance() {
    local root manifest launcher dist name digest file
    root=$(cd "$1" && pwd -P) || return 1
    manifest="$root/klause-bench/build/provenance.json"
    [ -e "$manifest" ] || return 0
    # Nodes booted before jq joined their packages install it on first use.
    command -v jq >/dev/null || sudo -n env DEBIAN_FRONTEND=noninteractive apt-get install -yq jq >/dev/null 2>&1 ||
        { echo 'jq is missing' >&2; return 1; }
    jq -e '(has("version") | not) or .version == 1' "$manifest" >/dev/null 2>&1 ||
        { echo "unsupported or invalid provenance manifest: $manifest" >&2; return 1; }
    launcher=$(jq -er '.launcher | strings' "$manifest") && launcher=$(realpath "$launcher" 2>/dev/null) ||
        { echo 'provenance names no launcher' >&2; return 1; }
    case "$launcher" in "$root/klause-cli/build/install/"*) ;; *) echo "provenance launcher outside the install: $launcher" >&2; return 1 ;; esac
    [ -f "$launcher" ] || { echo "provenance launcher missing: $launcher" >&2; return 1; }
    dist=$(dirname "$(dirname "$launcher")")
    jq -e '.provenance.distribution | type == "object" and length > 0' "$manifest" >/dev/null 2>&1 ||
        { echo 'empty installed provenance' >&2; return 1; }
    while IFS= read -r -d '' name && IFS= read -r -d '' digest; do
        file=$(realpath "$dist/$name" 2>/dev/null) || { echo "provenance file missing: $name" >&2; return 1; }
        case "$file" in "$dist"/*) ;; *) echo "provenance file outside the distribution: $name" >&2; return 1 ;; esac
        [ -f "$file" ] || { echo "provenance file missing: $name" >&2; return 1; }
        [ "$(sha256sum < "$file" | cut -d' ' -f1)" = "$digest" ] || { echo "provenance hash mismatch: $name" >&2; return 1; }
    done < <(jq -j '.provenance.distribution | to_entries[] | "\(.key)\u0000\(.value)\u0000"' "$manifest")
}

# The corpus in the bucket as one tar per collection (`corpus-packs/<collection>.tar`) and an index of each one's
# signature (`corpus-packs/index.tsv`): a few dozen requests an instance instead of one per file, of which there are
# tens of thousands. A collection's signature is a digest of its files' names and sizes; `.plain` staging copies are
# left out of both. An instance keeps the signature of each pack it holds in `<corpus>/.packs/<collection>`.
lab_corpus_signature() {
    (cd "$1" && find "$2" -type f ! -path '*/.plain/*' -printf '%s %p\n' | LC_ALL=C sort | sha256sum | cut -c1-16)
}

# pull_lab_corpus <bucket> <region> <corpus>: the packs that differ from what the instance holds. Before the first
# push wrote any packs, the files one by one, as they were kept before.
pull_lab_corpus() {
    local index name sig
    mkdir -p "$3/.packs"
    index=$(mktemp) || return 1
    if ! aws s3 cp "s3://$1/corpus-packs/index.tsv" "$index" --region "$2" --only-show-errors 2>/dev/null; then
        rm -f "$index"
        aws s3 sync "s3://$1/corpus" "$3" --region "$2" --only-show-errors --exclude '.plain/*' --exclude '*/.plain/*' --exclude '.packs/*'
        return
    fi
    while IFS=$'\t' read -r name sig; do
        [ -n "$name" ] || continue
        [ "$(cat "$3/.packs/$name" 2>/dev/null)" = "$sig" ] && continue
        if aws s3 cp "s3://$1/corpus-packs/$name.tar" - --region "$2" --only-show-errors | tar -x -C "$3"; then
            printf '%s\n' "$sig" > "$3/.packs/$name"
        else
            echo "corpus pack download failed: $name" >&2
        fi
    done < "$index"
    rm -f "$index"
}

# push_lab_corpus <bucket> <region> <corpus>: a pack of each collection whose files changed since the index was
# written, then the index. A pack that fails to upload keeps its old index line, so no instance pulls a pack it lacks.
push_lab_corpus() {
    local index next pack name sig old changed=0
    index=$(mktemp) && next=$(mktemp) && pack=$(mktemp) || return 1
    aws s3 cp "s3://$1/corpus-packs/index.tsv" "$index" --region "$2" --only-show-errors 2>/dev/null || : > "$index"
    mkdir -p "$3/.packs"
    for dir in "$3"/*/; do
        [ -d "$dir" ] || continue
        name=$(basename "$dir")
        sig=$(lab_corpus_signature "$3" "$name")
        old=$(awk -F'\t' -v n="$name" '$1 == n { print $2 }' "$index")
        if [ "$old" != "$sig" ]; then
            if tar -c -C "$3" --exclude='.plain' "$name" > "$pack" &&
               aws s3 cp "$pack" "s3://$1/corpus-packs/$name.tar" --region "$2" --only-show-errors; then
                changed=1
            else
                echo "corpus pack upload failed: $name" >&2
                sig=$old
            fi
        fi
        [ -n "$sig" ] && printf '%s\t%s\n' "$name" "$sig" >> "$next" && printf '%s\n' "$sig" > "$3/.packs/$name"
    done
    # Collections this instance does not hold keep their lines.
    awk -F'\t' 'NR == FNR { held[$1] = 1; next } !($1 in held)' "$next" "$index" > "$pack" && cat "$pack" >> "$next"
    if [ "$changed" = 1 ]; then
        aws s3 cp "$next" "s3://$1/corpus-packs/index.tsv" --region "$2" --only-show-errors || echo "corpus index upload failed" >&2
    fi
    rm -f "$index" "$next" "$pack"
}
