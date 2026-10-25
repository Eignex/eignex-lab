# Setup

Use macOS or Linux with Git and JDK 17+. Gradle provisions JDK 25; startup requires Koblas's Vector API engine.

```sh
git clone https://github.com/Eignex/eignex-lab.git
cd eignex-lab
deploy/install.sh
```

The installer registers API/runner services. Edit `~/.config/eignex-lab/lab.env` using the
[configuration example](lab.env.example), then rerun installation after setting changes.

## Client

Requires Bash, curl, and jq. Obtain the lab password from the operator and save it in
`~/.config/eignex-lab/password` or `LAB_PASSWORD`. Every API call requires it.

```sh
export LAB_URL=http://lab-host:8420
deploy/lab health
```

## Storage and updates

Data defaults to `~/eignex-lab-data`; logs are under `logs/`, artifacts under `jobs/` (`LAB_JOBS`).
Corpora use `~/.cache/klause-bench/corpus` (`LAB_CORPUS`). On macOS, external volumes must mount at boot and
allow the services' Java binary Full Disk Access. The configuration example includes the mount setting.
Fetch raw artifacts promptly: ended jobs trim after seven days or sooner on low disk. Results remain stored.

Use `deploy/lab update` for code updates between cases. Check `logs/update.log` if an update fails.

## Optional AWS workers

From an admin AWS CLI session with SSH access to the lab Mac:

```sh
INSTANCE_TYPES=c7i.2xlarge,c7g.2xlarge deploy/aws-setup.sh user@lab-host
```

Restart the runner, then adjust pool limits and `tags.<family/type/instance>` labels in
`$LAB_DATA/aws/aws.properties`; changes are reread automatically. Local labels use `LAB_NODE_TAGS` in `lab.env`.
Architecture/SIMD facts are probed, not overridden by labels. See [AwsConfig](../src/main/kotlin/com/eignex/lab/Aws.kt)
for settings and defaults.

A node runs several jobs at once, one case per two physical cores across all of them; each job stays within its own
`parallel`. Profile and measurement jobs get a node to themselves. An on-demand node runs queued profiles first and,
unless `onDemand=true` or started by hand, nothing else, retiring once idle.
