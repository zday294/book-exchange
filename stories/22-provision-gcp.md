# 22 – Provision Google Cloud infrastructure

## Story
As the team, we want the Google Cloud environment created reproducibly so that we can deploy and, if needed, rebuild it.

## Depends on
20 (decision), 21.

## Details
- GCP project (and billing account, budget alert) created per the story 20 decision.
- Infrastructure defined as code (Terraform recommended; documented `gcloud` scripts are an acceptable first step) and stored in `infra/`.
- Resources, assuming the recommended options:
  - Enabled APIs (Cloud Run, Cloud SQL Admin, Artifact Registry, Secret Manager, Cloud Build if used).
  - Artifact Registry repository for container images.
  - Cloud SQL for PostgreSQL instance, database and application user; automated backups on.
  - Secret Manager secrets for DB credentials, with the Cloud Run service account granted access.
  - Backend Cloud Run service (initially with a placeholder image) with the agreed min/max instances, timeout and service account.
  - A second Cloud Run service for the front-end Node server (story 24), with its own service account. It is the public entry point; configure it to call the backend service (service-to-service authentication or internal ingress, per story 20).
  - Least-privilege service accounts: one for the running app, one for CI/CD deploys.
- Run the schema script (or migrations) against Cloud SQL as a documented one-time step.
- Optional: custom domain and managed certificate.
- Terraform state stored remotely (GCS bucket), not committed.

## Acceptance criteria
- [ ] Infra can be created from the repo with documented commands.
- [ ] Cloud SQL is reachable from Cloud Run and not open to the whole internet.
- [ ] No secrets are in the repo or the Terraform files (references to Secret Manager only).
- [ ] Budget alert is active.
- [ ] A hand-deployed image of the backend responds on its health endpoint in the cloud.
