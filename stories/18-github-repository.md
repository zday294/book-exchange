# 18 – Put the project on GitHub

## Story
As a developer, I want the project in a GitHub repository with sensible defaults so that work is versioned, reviewable and ready for CI.

## Details
- The project folder is not a git repo yet: `git init` at the project root (`book-exchange/`), with `backend/`, `frontend/`, `db/` and `stories/` as top-level folders (monorepo). The front end has two parts: the Vue app and its Node.js server (story 24).
- Create the GitHub repository (decide: owner/org vs personal, private vs public — private is the safe default given the code will eventually hold deployment config) and push `main`.
- Root `.gitignore` covering Java/Maven or Gradle output (`target/`, `build/`), `node_modules/`, `dist/`, IDE files (`.idea/`, `.vscode/`), `.env*` files, and OS files. Commit an `.env.example` instead of real env files.
- Root `README.md`: what the app is, repo layout, prerequisites (Java version, Node version, Docker), and how to run DB, backend and frontend locally. Link to `stories/`.
- **No secrets in git:** database passwords, API keys and GCP credentials never get committed. Add a note to the README and consider enabling GitHub secret scanning / push protection.
- Branching: `main` is always deployable; work happens on short-lived feature branches and merges via pull request.
- `main` branch protection: require a pull request and passing CI checks (story 19) before merging. Add a PR template with a checklist (tests added, story linked).
- Optional: turn each story into a GitHub issue (or a GitHub Project board) so progress can be tracked; label by area (`backend`, `frontend`, `db`, `infra`).
- Add a license decision (or explicitly none) to the README.

## Acceptance criteria
- [ ] Repository exists on GitHub and `main` contains the stories and the initial project skeleton.
- [ ] `.gitignore` prevents build output, dependencies and env files from being committed (verify with `git status` after a build).
- [ ] README lets a new developer run the app locally from a clean clone.
- [ ] Branch protection on `main` is enabled once CI exists.
- [ ] No secrets in history (quick scan before first push).
