# DuelArena Branch Workflow

This repository uses a hybrid integration model:

- Long-lived integration branch: `dev` (local branch: `codex/dev`)
- Release branch: `main`
- Short-lived work branches:
  - `codex/feat/<topic>`
  - `codex/fix/<topic>`
  - `codex/chore/<topic>`

## Daily Development

1. Update integration branch:
   - `git switch codex/dev`
   - `git pull`
2. Create a work branch from `codex/dev`:
   - `git switch -c codex/feat/<short-topic>`
3. Implement and validate:
   - `./gradlew -Prequire_hytale=false ciCheck test`
4. Push branch:
   - `git push -u origin codex/feat/<short-topic>`
5. Open PR:
   - `codex/feat/<short-topic> -> dev`
6. Merge strategy to `dev`:
   - Squash merge

## Releases

1. Open PR:
   - `dev -> main`
2. Include release notes in the PR description.
3. Merge to `main` using a merge commit (to preserve release boundary).
4. Tag release on `main`:
   - `git tag vX.Y.Z`
   - `git push origin vX.Y.Z`

## Hotfixes

1. Branch from `main`:
   - `git switch main`
   - `git pull`
   - `git switch -c codex/fix/<topic>`
2. Open PR:
   - `codex/fix/<topic> -> main`
3. Tag patch release on `main`.
4. Back-merge:
   - `main -> dev`

## Protection Policy

- `main`: PR-required, review-required, required checks, direct push blocked.
- `dev`: lighter protection for fast integration; direct push remains possible for maintainers/admins.
