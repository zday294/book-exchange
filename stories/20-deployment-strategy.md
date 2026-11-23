# 20 – Deployment strategy decision (Google Cloud)  *(spike)*

## Story
As the team, we want a written decision on how to host the app on Google Cloud so that we can build the deployment stories with confidence, at low cost.

## Output
A short decision document (`docs/deployment.md`) recording the choice, the reasons, an architecture sketch, a rough monthly cost estimate and the open risks. Time-boxed spike; no production infrastructure required.

## Workload profile (shapes the decision)
- Small, seasonal traffic: a handful of exchanges, tens of users each, mostly active for a few days around the holidays. Scale-to-zero is attractive.
- Holds long-lived connections for live updates (story 15, SSE).
- Needs Postgres, a static Vue front end, and the backend making outbound HTTP calls (cover lookup, story 14).

## Options to evaluate

**Backend**
| Option | Notes |
|---|---|
| **Cloud Run** (recommended starting point) | Containers, scales to zero, simple deploys, cheap for this profile. Request timeout is configurable (up to 60 min) so SSE works, but streams will reconnect periodically; the page already re-syncs on reconnect (story 15). |
| App Engine (flexible/standard) | Managed, but less natural for a Spring container and no real cost advantage. |
| GKE / Compute Engine VM | Far more operational work than this app justifies. |

**Database**
| Option | Notes |
|---|---|
| **Cloud SQL for PostgreSQL** (recommended) | Managed Postgres, backups, private connectivity from Cloud Run. Always-on instance means a fixed monthly cost even when idle; smallest tier is fine. |
| AlloyDB | Overkill. |
| Postgres on a VM | Cheapest but we'd own backups and patching. |

**Front end** — decided: the front end has its own Node.js server (story 24), so it deploys as a container, not as static files.
| Option | Notes |
|---|---|
| **Second Cloud Run service** (recommended) | Same hosting model, tooling and pipeline as the backend; scales to zero; the Node server serves the Vue build and proxies `/api/**` to the backend service. |
| Same Cloud Run service, two containers (sidecar) | Possible, but couples scaling and deploys; not recommended. |
| Firebase Hosting / Cloud Storage + CDN | Rejected: only suits static files with no Node server. |

The browser only ever talks to the front-end service, so the backend can be restricted to requests from it (Cloud Run service-to-service authentication, or internal-only ingress through a VPC connector) instead of being open to the internet. Evaluate that as part of this spike.

## Questions the spike must answer
0. **Proxying the live-update stream.** The Node server sits between the browser and the backend, so it must pass the SSE stream (story 15) through unbuffered, and Cloud Run's request timeout applies to both hops. Verify this early.
1. **Live updates across instances.** The event stream (story 15) is held in memory per backend instance. If Cloud Run runs more than one instance, a claim handled by instance A won't reach viewers connected to instance B. Options: cap max instances at 1 (simplest, fine at this scale, but then no scale-out and brief gaps on deploy); fan events out through Postgres `LISTEN/NOTIFY`; or use Pub/Sub / Redis (Memorystore). Recommendation to validate: `max-instances=1` initially, with `LISTEN/NOTIFY` as the upgrade path.
2. **Cold starts.** Spring on Cloud Run starts slowly. Compare min-instances=1 (small idle cost), JVM startup flags/CDS, or Spring native image. Decide whether cold starts are acceptable.
3. **Database connectivity and cost:** Cloud SQL connector / private IP vs public IP with authorized networks; connection pool sizing against Cloud SQL limits.
4. **Schema management in deployed environments.** The `db/init.sql` script won't scale beyond the first deploy; recommend adopting Flyway or Liquibase before the first real deployment (and decide who runs migrations: app startup vs CI step).
5. **Environments:** is a separate staging environment worth the cost, or one production environment plus local dev? Separate GCP projects are the cleanest isolation.
6. **Domain and HTTPS:** custom domain or the default `*.run.app` / Firebase URL; managed certificates.
7. **Secrets:** Secret Manager for DB credentials; no secrets in images or in git (story 18).
8. **Cost guardrails:** budget alert and, if needed, request/instance caps. Provide a rough monthly estimate.
9. **Observability:** Cloud Logging by default, uptime check on `/actuator/health`, an alert on error rate.

## Acceptance criteria
- [ ] `docs/deployment.md` states the chosen backend, database and front-end hosting, with reasons and rejected alternatives.
- [ ] Each of the questions above has an answer or a recorded decision to defer.
- [ ] Architecture diagram (simple boxes and arrows is fine).
- [ ] Monthly cost estimate for idle and for a busy holiday week.
- [ ] Follow-up stories 21–24 are adjusted to match the decision.
