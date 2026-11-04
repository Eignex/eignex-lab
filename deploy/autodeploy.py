#!/usr/bin/env python3
"""Deploy every green build of main to this lab host, unattended (launchd, as root, every few minutes).

One pass: find the newest successful `build` run of a push to main; when it is newer than the running release and both
of its worker images are published (the `worker-image` workflow), stage its tested native distribution under
releases/<sha8>-ci<run>, switch the fleet's worker release to it (images by digest, native launcher, the old release
added to workerReplaces), refresh the host capacity adapters from the same commit, point releases/current at it and
restart the coordinator, API, runner and capacity adapter. If the services are not back within a minute, the previous
release, queue.json and native-services.json are restored and the services restarted on them. Every pass that changes
anything leaves a receipt under operations/autodeploy-<stamp>/; everything is logged to logs/autodeploy.log.

Nothing here builds code: the bytes deployed are the ones CI tested. The schema only moves forward on main, and a
release that does not start is rolled back before the next pass. Run by hand with --dry-run to see what a pass would do.
"""
import argparse, datetime, fcntl, hashlib, io, json, os, pwd, shutil, subprocess, sys, tempfile, time, urllib.request, zipfile

OWNER = os.environ.get('LAB_AUTODEPLOY_USER', 'rasmusros')
HOME = pwd.getpwnam(OWNER).pw_dir
DATA = os.environ.get('LAB_DATA', f'{HOME}/eignex-lab-data')
REPO = 'Eignex/eignex-lab'
REGISTRY = 'public.ecr.aws'
IMAGE = 'i7d5w3s6/eignex-lab-worker'
RELEASES = f'{DATA}/releases'
QUEUE = f'{DATA}/nomad/queue.json'
NATIVE = f'{DATA}/nomad/native-services.json'
ADAPTERS = f'{DATA}/nomad/host-adapters'
LOG = f'{DATA}/logs/autodeploy.log'
SERVICES = ['com.eignex.lab.nomad-coordinator', 'com.eignex.lab.api', 'com.eignex.lab.runner']
ADAPTER_SERVICE = 'com.eignex.lab.capacity-adapter'
ARCHES = {'x86_64': 'amd64', 'amd64': 'amd64', 'arm64': 'arm64', 'aarch64': 'arm64'}


def log(message):
    line = f'{datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")} {message}'
    print(line, flush=True)
    with open(LOG, 'a') as out:
        out.write(line + '\n')


def github(path, raw=False):
    token = open(f'{DATA}/github-token').read().strip()
    request = urllib.request.Request(f'https://api.github.com/repos/{REPO}/{path}', headers={
        'Authorization': f'Bearer {token}', 'Accept': 'application/vnd.github+json', 'User-Agent': 'eignex-lab-autodeploy'})
    with urllib.request.urlopen(request, timeout=60) as response:
        body = response.read()
    return body if raw else json.loads(body)


def image_digest(tag):
    """The digest of public ECR tag [tag], or None when it is not published."""
    with urllib.request.urlopen(f'https://{REGISTRY}/token/?scope=repository:{IMAGE}:pull', timeout=30) as response:
        token = json.loads(response.read())['token']
    request = urllib.request.Request(f'https://{REGISTRY}/v2/{IMAGE}/manifests/{tag}', method='HEAD', headers={
        'Authorization': f'Bearer {token}',
        'Accept': 'application/vnd.docker.distribution.manifest.v2+json, application/vnd.oci.image.manifest.v1+json'})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.headers['Docker-Content-Digest']
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return None
        raise


def newest_green_build():
    runs = github('actions/workflows/build.yml/runs?branch=main&event=push&status=success&per_page=1')['workflow_runs']
    return runs[0] if runs else None


def running():
    """The running release's directory name and its CI receipt."""
    name = os.readlink(f'{RELEASES}/current')
    receipt = f'{RELEASES}/{name}/ci-artifact.json'
    return name, (json.load(open(receipt)) if os.path.exists(receipt) else {})


def sha256(path):
    digest = hashlib.sha256()
    with open(path, 'rb') as source:
        for chunk in iter(lambda: source.read(1 << 20), b''):
            digest.update(chunk)
    return digest.hexdigest()


def chown_tree(path):
    owner = pwd.getpwnam(OWNER)
    for root, dirs, files in os.walk(path):
        for name in [root] + [os.path.join(root, f) for f in dirs + files]:
            os.lchown(name, owner.pw_uid, owner.pw_gid)


def stage(run):
    """Stage [run]'s native distribution as releases/<sha8>-ci<run>; returns its directory name."""
    name = f'{run["head_sha"][:8]}-ci{run["id"]}'
    target = f'{RELEASES}/{name}'
    if os.path.isdir(target):
        return name
    artifacts = github(f'actions/runs/{run["id"]}/artifacts')['artifacts']
    artifact = next(a for a in artifacts if a['name'] == 'eignex-lab-native-distribution' and not a['expired'])
    outer = zipfile.ZipFile(io.BytesIO(github(f'actions/artifacts/{artifact["id"]}/zip', raw=True)))
    inner_name = next(n for n in outer.namelist() if n.endswith('.zip'))
    staging = tempfile.mkdtemp(prefix=f'.stage-{name}-', dir=RELEASES)
    try:
        package = os.path.join(staging, 'package.zip')
        with outer.open(inner_name) as source, open(package, 'wb') as out:
            shutil.copyfileobj(source, out)
        digest = sha256(package)
        # unzip keeps the launchers' modes; Python's zipfile does not.
        subprocess.run(['unzip', '-q', package, '-d', staging], check=True)
        roots = [d for d in os.listdir(staging) if os.path.isdir(os.path.join(staging, d))]
        assert len(roots) == 1, f'unexpected distribution layout {roots}'
        release = os.path.join(staging, roots[0])
        assert os.access(os.path.join(release, 'bin/eignex-lab'), os.X_OK), 'launcher is not executable'
        with open(os.path.join(release, 'ci-artifact.json'), 'w') as out:
            json.dump({'revision': run['head_sha'], 'workflowRun': run['id'], 'branch': 'main',
                       'package': {'name': artifact['name'], 'bytes': os.path.getsize(package), 'sha256': digest},
                       'deployedBy': 'autodeploy'}, out, indent=2)
        chown_tree(release)
        os.rename(release, target)
    finally:
        shutil.rmtree(staging, ignore_errors=True)
    return name


def write_json(path, data):
    mode = os.stat(path).st_mode & 0o777
    fd, tmp = tempfile.mkstemp(dir=os.path.dirname(path), prefix='.autodeploy-')
    with os.fdopen(fd, 'w') as out:
        json.dump(data, out, indent=2); out.write('\n'); out.flush(); os.fsync(out.fileno())
    os.chmod(tmp, mode)
    owner = pwd.getpwnam(OWNER); os.chown(tmp, owner.pw_uid, owner.pw_gid)
    os.replace(tmp, path)


def switch_workers(release_sha, native_dir, digests):
    """Point every queue target at [release_sha]: images by architecture, the native launcher at [native_dir]."""
    queue = json.load(open(QUEUE)); native = json.load(open(NATIVE))
    old = queue['release']
    if old == release_sha:
        return None
    launcher = f'{RELEASES}/{native_dir}/bin/eignex-lab'
    for target in queue['targets']:
        template = target['template']
        if template.get('platform') == 'mac':
            template['command'][0] = launcher
            template['environment']['LAB_WORKER_LAUNCHER'] = launcher
        else:
            tag = next(t.split(':', 1)[1] for t in target.get('tags', []) if t.startswith('arch:'))
            template['image'] = f'{REGISTRY}/{IMAGE}@{digests[ARCHES[tag]]}'
    queue['release'] = release_sha
    replaces = queue.setdefault('workerReplaces', [])
    if old not in replaces:
        replaces.append(old)
    native['workerLauncher'] = launcher
    write_json(QUEUE, queue); write_json(NATIVE, native)
    return old


def refresh_adapters(sha):
    """The host capacity adapters as deploy/infra/{aws,common} hold them at [sha]."""
    for part in ('aws', 'common'):
        for entry in github(f'contents/deploy/infra/{part}?ref={sha}'):
            if entry['type'] != 'file':
                continue
            path = f'{ADAPTERS}/{part}/{entry["name"]}'
            body = github(f'contents/{entry["path"]}?ref={sha}', raw=False)
            import base64
            data = base64.b64decode(body['content'])
            fd, tmp = tempfile.mkstemp(dir=os.path.dirname(path), prefix='.autodeploy-')
            with os.fdopen(fd, 'wb') as out:
                out.write(data)
            os.chmod(tmp, 0o755 if entry['name'].endswith('.sh') else 0o644)
            owner = pwd.getpwnam(OWNER); os.chown(tmp, owner.pw_uid, owner.pw_gid)
            os.replace(tmp, path)


def point_current(name):
    link = f'{RELEASES}/.current-next'
    if os.path.lexists(link):
        os.remove(link)
    os.symlink(name, link)
    owner = pwd.getpwnam(OWNER); os.lchown(link, owner.pw_uid, owner.pw_gid)
    os.replace(link, f'{RELEASES}/current')


def restart(labels):
    for label in labels:
        plist = f'/Library/LaunchDaemons/{label}.plist'
        subprocess.run(['launchctl', 'bootout', f'system/{label}'], capture_output=True)
        for _ in range(60):
            if subprocess.run(['launchctl', 'print', f'system/{label}'], capture_output=True).returncode != 0:
                break
            time.sleep(1)
        subprocess.run(['launchctl', 'bootstrap', 'system', plist], check=True)


def healthy(timeout=90):
    """Whether coordinator, API and runner are up and the API answers on its local port."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        processes = subprocess.run(['pgrep', '-f', 'MainKt (nomad-coordinator|api|runner)'], capture_output=True, text=True).stdout.split()
        try:
            urllib.request.urlopen('http://127.0.0.1:8420/health', timeout=5)
            answered = True
        except urllib.error.HTTPError:
            answered = True  # an authentication refusal still means the API is serving
        except Exception:
            answered = False
        if len(processes) >= 3 and answered:
            return True
        time.sleep(5)
    return False


def deploy(run, dry_run):
    current, receipt = running()
    if receipt.get('workflowRun') == run['id'] or receipt.get('revision') == run['head_sha']:
        return
    if receipt.get('branch') == 'main' and receipt.get('workflowRun', 0) > run['id']:
        return  # never move backwards
    digests = {arch: image_digest(f'{run["head_sha"]}-{arch}') for arch in ('amd64', 'arm64')}
    if not all(digests.values()):
        log(f'{run["head_sha"][:8]} (run {run["id"]}) waits for its worker images: {digests}')
        return
    log(f'deploying {run["head_sha"][:8]} (run {run["id"]}) over {current}')
    if dry_run:
        return
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
    ops = f'{DATA}/operations/autodeploy-{stamp}'
    os.makedirs(ops, mode=0o700)
    shutil.copy2(QUEUE, f'{ops}/queue.json.before'); shutil.copy2(NATIVE, f'{ops}/native-services.json.before')
    name = stage(run)
    old_release = switch_workers(run['head_sha'], name, digests)
    refresh_adapters(run['head_sha'])
    point_current(name)
    restart(SERVICES + [ADAPTER_SERVICE])
    ok = healthy()
    outcome = {'at': stamp, 'from': current, 'to': name, 'run': run['id'], 'sha': run['head_sha'], 'images': digests,
               'workerRelease': {'from': old_release, 'to': run['head_sha']}, 'healthy': ok}
    if not ok:
        log(f'{name} did not come up; rolling back to {current}')
        point_current(current)
        shutil.copy2(f'{ops}/queue.json.before', QUEUE); shutil.copy2(f'{ops}/native-services.json.before', NATIVE)
        restart(SERVICES)
        outcome['rolledBack'] = healthy()
    json.dump(outcome, open(f'{ops}/receipt.json', 'w'), indent=2)
    log(f'{"deployed" if ok else "rolled back"} {name}; receipt {ops}')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--dry-run', action='store_true')
    args = parser.parse_args()
    os.makedirs(os.path.dirname(LOG), exist_ok=True)
    with open(f'{DATA}/.autodeploy.lock', 'w') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            return
        try:
            run = newest_green_build()
            if run:
                deploy(run, args.dry_run)
        except Exception as error:  # the next pass tries again; the running release stays
            log(f'pass failed: {type(error).__name__}: {error}')
            sys.exit(1)


if __name__ == '__main__':
    main()
