# HTTP API

[Documentation home](../deploy/SETUP.md) · [Run specifications](runs.md) · [Measurements](measurements.md)

All routes require HTTP Basic authentication, including `/health`. Use the client in [deploy/lab](../deploy/lab)
for routine work. Browser writes from another origin are refused. A browser may receive HTML for job/file routes;
use `?json` to request data. Unknown run-specification fields are rejected.

## Submit and inspect

| Method and path | Request or result |
| --- | --- |
| `POST /experiments` | [Run spec](runs.md) → ID, placement, host, and any cap notes |
| `GET /jobs` | Job summaries; `limit`, `before`, `name`, `active=true`, `commands=true` filters |
| `GET /jobs/{id}` | Job details and command states |
| `GET /jobs/{id}/wait` | Wait until the job ends |
| `GET /placement`, `/placement/{id}` | Read-only queue position, estimates, active hosts, and launch blockers |
| `GET /experiments/{id}/arms` | Arm configurations and resolved commits |
| `GET /experiments/{id}/cases` | Case records |
| `GET /experiments/{id}/cases.jsonl` | Streamed JSON Lines, including current `labCheck`, witness, host, and output hashes |
| `GET /experiments/{id}/cases.csv` | Case CSV |
| `GET /experiments/{id}/bench.csv` | One arm's results; `arm` and optional `seed` |
| `GET /experiments/{id}/stats` | Paired statistics and intervals |
| `GET /compare?jobs=7,8` | Comparison page |

`POST /experiments` also accepts `{"analyze":{"job":7}}` for compilation analysis or `{"recheck":{"job":7}}`
for witness rechecking. [Archive analysis](measurements.md#retain-a-campaign) names `analyze.archive`, `sha256`,
and optionally `manifest`/`manifestSha256`. Capture-dependent requests return 409 when their inputs are unavailable.

## Control and retain

| Method and path | JSON body or behavior |
| --- | --- |
| `POST /jobs/{id}/pause`, `/resume`, `/cancel`, `/retry`, `/delete` | Control an existing job; delete requires it to have ended |
| `POST /jobs/{id}/priority` | `{"priority":1}` |
| `POST /jobs/{id}/parallel` | `{"parallel":2}`; recording modes require 1 |
| `POST /jobs/{id}/description` | `{"description":"question being tested"}` |
| `POST /jobs/{id}/host` | Operator override `{"host":"local"}` / `cloud` / `auto` for queued, unplanned work |
| `GET /jobs/{id}/files` | File listing |
| `GET /jobs/{id}/files/{path}` | File bytes; `tail=<bytes>` for logs |
| `POST /jobs/{id}/hold` | Hold files for download; optional `ttlSec` query, maximum 7200 |
| `POST /jobs/{id}/hold/{lease}` | Renew a hold |
| `DELETE /jobs/{id}/hold/{lease}` | Release a hold |

## Schedules and server

| Method and path | JSON body or result |
| --- | --- |
| `POST /schedules` | `name`, `ref`, `intervalSec`, and `experiment` → ID |
| `GET /schedules` | Saved schedules |
| `POST /schedules/{id}` | Optional `experiment`, `intervalSec`, `nextCheckInSec` |
| `POST /schedules/{id}/check`, `/launch`, `/delete` | Check ref now, launch even if unchanged, or remove schedule |
| `GET /trend` | Schedule runs; optional `name` and `all=1` |
| `GET /references`, `/experiments/{id}/reference` | [Reference coverage and comparisons](validation.md#interpret-and-refresh-references) |
| `POST /references/retire` | `collection`, `solver`, optional `budgetMs` |
| `GET /problem?collection=<c>&problem=<p>` | References and lab runs for one problem |
| `GET /health`, `/disk` | Host, queue, volume, and storage reports |
| `GET /cloud/nodes` | Current worker report and observed node tags |
| `POST /cloud/nodes` | Optional `size`, `market` → manual launch request; 202 accepted, 409 refused |
| `POST /update`, `/docker-cleanup` | Request maintenance at the runner's next safe point |

Returned types and route details are defined in [Api.kt](../src/main/kotlin/com/eignex/lab/Api.kt),
[Store.kt](../src/main/kotlin/com/eignex/lab/Store.kt), and [Waiting.kt](../src/main/kotlin/com/eignex/lab/Waiting.kt).
