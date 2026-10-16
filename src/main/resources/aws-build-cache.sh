# Cache extraction never touches an installation until the complete download and validation succeed.
validate_lab_cache() {
    python3 - "$1" "$2" <<'PY'
import pathlib, re, sys, zipfile
root, kind = pathlib.Path(sys.argv[1]), sys.argv[2]
if kind == 'install':
    distributions = [(['klause-cli/build/install/klause-cli-jvm', 'klause-cli/build/install/klause-cli'], ['klause-cli', 'klause-cli-jvm'], 'com/eignex/klause/cli/MainKt.class'),
                     (['klause-bench/build/install/klause-bench'], ['klause-bench'], 'com/eignex/klause/bench/target/BenchCli.class')]
    for relatives, names, main in distributions:
        launchers = [root / relative / 'bin' / name for relative in relatives for name in names]
        launcher = next((p for p in launchers if p.is_file()), launchers[0])
        dist = launcher.parent.parent
        assert launcher.is_file() and launcher.stat().st_mode & 0o111, f'missing executable: {launcher}'
        jars = list((dist / 'lib').glob('*.jar'))
        assert jars, f'missing libraries: {dist}'
        found = False
        for jar in jars:
            with zipfile.ZipFile(jar) as archive:
                assert archive.testzip() is None, f'corrupt library: {jar}'
                found |= main in archive.namelist()
        assert found, f'missing main class: {main}'
        refs = re.findall(r'\$(?:APP_HOME|\{APP_HOME\})/lib/([^:\s"\']+\.jar)', launcher.read_text())
        assert refs, f'missing launcher classpath: {launcher}'
        assert all((dist / 'lib' / ref).is_file() for ref in refs), f'incomplete classpath: {launcher}'
else:
    wrapper = root / '.gradle/wrapper'
    assert wrapper.is_dir(), 'missing Gradle wrapper cache'
    distributions = [d for d in wrapper.rglob('lib') if d.parent.name.startswith('gradle-')]
    assert distributions, 'missing extracted Gradle distribution'
    for dist in distributions:
        bootstrap = False
        for jar in dist.rglob('*.jar'):
            with zipfile.ZipFile(jar) as archive:
                assert archive.testzip() is None, f'corrupt Gradle library: {jar}'
                bootstrap |= 'org/gradle/launcher/bootstrap/ProcessBootstrap.class' in archive.namelist()
        assert bootstrap, f'incomplete Gradle bootstrap: {dist}'
PY
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

validate_lab_provenance() {
    python3 - "$1" <<'PYCODE'
import hashlib, json, pathlib, sys
root = pathlib.Path(sys.argv[1]).resolve()
manifest = root / 'klause-bench/build/provenance.json'
if manifest.exists():
    data = json.loads(manifest.read_text())
    assert data.get('version', 1) == 1
    launcher = pathlib.Path(data['launcher']).resolve()
    assert launcher.is_relative_to(root / 'klause-cli/build/install') and launcher.is_file()
    distribution = data['provenance']['distribution']
    assert distribution, 'empty installed provenance'
    for name, digest in distribution.items():
        file = (launcher.parent.parent / name).resolve()
        assert file.is_relative_to(launcher.parent.parent) and file.is_file()
        assert hashlib.sha256(file.read_bytes()).hexdigest() == digest, f'provenance hash mismatch: {name}'
PYCODE
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
