# 23 – Continuous deployment from GitHub

## Story
As a developer, I want merging to `main` to deploy the app automatically so that releases are boring and repeatable.

## Depends on
19, 21, 22.

## Details
- GitHub Actions workflow on push to `main` (after CI passes):
  1. Build and tag the backend image (with the git SHA) and push to Artifact Registry.
  2. Deploy that image to Cloud Run.
  3. Build and tag the front-end image (Vue build + Node server, story 24), push it, and deploy it to its own Cloud Run service, pointing at the backend URL.
  4. Smoke test: call the backend health endpoint, load the home page through the front-end service, and call an `/api` route through the proxy; fail the workflow if either is bad.
- **Authentication to GCP without long-lived keys:** use GitHub OIDC with Workload Identity Federation, not a downloaded service account key stored as a GitHub secret.
- GitHub Environments (`production`) hold non-secret config; optional manual approval before deploy.
- Database changes: run migrations as part of deploy (per the story 20 decision) before the new revision takes traffic.
- Rollback: documented steps to route Cloud Run traffic back to the previous revision; images are retained in Artifact Registry.
- Deploy status visible on the commit/PR, and a failure notification.

## Acceptance criteria
- [ ] Merging to `main` results in the new version live without manual steps.
- [ ] No GCP key files exist in GitHub secrets (OIDC only).
- [ ] A failing smoke test marks the deploy as failed.
- [ ] Rollback to the previous revision is documented and tried once.
- [ ] Backend and front end deploy independently (a front-end-only change doesn't redeploy the backend).
- [ ] Deployed app works end to end: create exchange, submit a book, claim it, and see the update live in a second browser.
