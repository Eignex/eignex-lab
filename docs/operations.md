# Operations

[Documentation home](../deploy/SETUP.md) · [Cloud nodes](cloud.md) · [HTTP API](api.md)

## Access and trust

All pages and API calls require HTTP Basic authentication. The bundled resource contains only a salted password
hash; `LAB_PASSWORD_HASH` can override it. Run the installed `eignex-lab password` command to generate a new password
and hash, distribute the secret privately, and apply the hash through service configuration or the bundled resource.
Failed attempts are rate-limited. Browser writes from another origin are refused; ordinary curl/client calls work.
Use TLS for access beyond a trusted local network.

Only GitHub repositories owned by **Eignex** run. Each commit must be reachable from `main` or a branch whose pushes
are attributable only to `LAB_TRUSTED_USERS` (default `rasros`). GitHub branch activity establishes the pusher;
commit author names do not. Trust is checked at submission and after resolving the revision for execution, with
failures closed. A read-only token in `$LAB_DATA/github-token` raises GitHub's API allowance and is read on each use.

The optional public relay uses Caddy/TLS and an outbound SSH reverse tunnel, avoiding inbound access to the lab:

```sh
deploy/relay/create.sh lab.example.com user@lab-host
sudo deploy/relay/install-tunnel.sh <relay-ip>
```

Run the first command from an admin AWS session; run the printed tunnel-install step on the lab server.
The script can update DigitalOcean DNS with `~/.config/digitalocean/token`. Relay errors go to
`$LAB_DATA/logs/relay.log`. Verify unauthenticated requests return 401 before exposing an endpoint.
Relay resources use their own name and are excluded from the worker's lab-instance cleanup.

## Storage and retention

Keep the database, mirrors, worktrees, and Gradle home on a fast internal disk (`LAB_DATA`). Job artifacts (`LAB_JOBS`)
and corpora (`LAB_CORPUS`) can live on another volume. `/disk` reports volume access, free space, cached usage,
download holds, database space, and Docker cleanup. Usage walks are spaced apart to limit I/O interference.

| Setting | Default | Behavior |
| --- | --- | --- |
| `LAB_MIN_FREE_GB` | 10 | Hold local work below this free space on a required volume |
| `LAB_TRIM_AGE_DAYS` | 7 | Trim bulk artifacts of ended jobs |
| `LAB_JOBS_TARGET_FREE_GB` | 25 | Trim ended jobs early, largest first, below this target |
| `LAB_TRIM_GRACE_HOURS` | 6 | Protect recently ended jobs from early trimming |
| `LAB_TRIM_CRITICAL_FREE_GB` | `LAB_MIN_FREE_GB` | Override grace below this floor |

Trimming retains records, selection, logs, exit/termination metadata, measurement manifests, compilation reports,
and compressed task summaries. It removes raw stdout/stderr, JFR/HotSpot captures, and profile output. Results stay
in the database. Queued/running jobs and jobs held for download are not trimmed. `lab rm <id>` deletes an ended job
outright; fetch important evidence first or [archive it](measurements.md#retain-a-campaign).

`lab fetch` holds files before listing them, renews a long transfer, and releases the hold afterward. Holds survive
server restarts and expire after two hours without renewal. Low space may still hold the queue while a download
is protected. If the artifact volume is missing or inaccessible, local recovery waits and cloud nodes take no new work.

## External storage

For macOS, mount removable volumes at boot and grant **the services' Java binary** Full Disk Access. The installer
prints its resolved path; repeat the grant after switching JDKs. Without access, filesystem calls can hang and the
lab holds the queue until they answer. A missing `/Volumes/<name>` mount never becomes a plain internal-disk folder.

```sh
sudo defaults write /Library/Preferences/SystemConfiguration/autodiskmount AutomountDisksWithoutUserLogin -bool true
```

To move existing data:

1. Copy jobs and corpora to the new volume with rsync while the lab runs.
2. Stop both services; on macOS use `sudo launchctl bootout system/com.eignex.lab.runner` and the matching `.api`.
3. Sync again to capture changes, then set `LAB_JOBS` and `LAB_CORPUS` in `lab.env`.
4. Rerun `deploy/install.sh`; check `lab health` and `/disk` for holds or volume problems.
5. Remove old copies only after confirming the new locations work.

## Docker

Containerized reference solvers need Docker. The installer uses Colima when installed on macOS or system Docker
on Linux; `LAB_DOCKER=none` opts out. Build needed images once on the server:

```sh
docker build -t klause-scip images/scip
docker build -t klause-clasp images/clasp
docker build -t klause-xcsp3-cpsat:source-preserving-v2 images/xcsp3-cpsat
```

The runner cleans up between jobs, weekly by default (`LAB_DOCKER_CLEANUP_DAYS`), or after `lab docker-cleanup`.
Cleanup skips running containers, prunes old build cache/dangling images, and removes superseded reference tags.
It keeps `LAB_DOCKER_KEEP`, each reference repository's `latest`, and tags named by queued/running commits.
An unreadable commit prevents tag removal. Colima also runs filesystem trim; freed VM space may not shrink its
host disk file. `/disk`, runner logs, and `$LAB_DATA/docker-cleanup.json` show the outcome.

## Execution and recovery

The API and runner are separate processes sharing SQLite WAL with durable commits. Restarted work retains
finished results; interrupted cases rerun after their old process trees are killed. Shared cloud iterations rerun
whole when incomplete. `lab retry <id>` reruns unfinished work of a failed/cancelled job. A failed case is counted
within a completed job; a failed checkout, build, or selection fails the job itself.

Setup network operations and builds retry with bounded backoff; each attempt is logged. A case that executes and
fails is a result rather than an automatic retry. Cancellation interrupts backoff and kills running process trees.
Pause and priority yields wait for started work to finish. Termination reasons are recorded in `.termination`
files and on the job page. On cloud cases, phase metadata distinguishes execution, output transfer, retry, and shutdown.

Cases use `LAB_SOLVE_JAVA_OPTS` (default `-Xmx3g -XX:+UseSerialGC`) plus their core count. Native BLAS/OpenMP
thread limits are 1. `debug` controls `KLAUSE_DEBUG`; [measurement](measurements.md) controls recording separately.
Match `LAB_MAX_PARALLEL` to memory as well as cores. `LAB_SHARED_PATHS` can share selected worktree directories;
ordinary result caching is disabled so every case solves afresh.

Use the [environment example](../deploy/lab.env.example) and
[Config.kt](../src/main/kotlin/com/eignex/lab/Config.kt) for the full settings inventory. Start troubleshooting with
`lab health`, `lab placement <id>`, the job's `setup.log`, and `$LAB_DATA/logs/runner.log`.
