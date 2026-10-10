# Cache extraction never touches an installation until the complete download and validation succeed.
validate_lab_cache() {
    python3 - "$1" "$2" <<'PY'
import pathlib, re, sys, zipfile
root, kind = pathlib.Path(sys.argv[1]), sys.argv[2]
if kind == 'install':
    distributions = [('klause-cli/build/install/klause-cli-jvm', 'klause-cli-jvm', 'com/eignex/klause/cli/MainKt.class'),
                     ('klause-bench/build/install/klause-bench', 'klause-bench', 'com/eignex/klause/bench/target/BenchCli.class')]
    for relative, name, main in distributions:
        dist = root / relative
        launcher = dist / 'bin' / name
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
