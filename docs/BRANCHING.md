# DuelArena Branch Workflow

This repository uses a hybrid integration model:

- Long-lived integration branch: `dev`
- Release branch: `main`
- Short-lived work branches:
  - `feat/<topic>`
  - `fix/<topic>`
  - `chore/<topic>`

## Daily Development

1. Update integration branch:
   - `git switch dev`
   - `git pull`
2. Create a work branch from `dev`:
   - `git switch -c feat/<short-topic>`
3. Implement and validate:
   - `./gradlew -Prequire_hytale=false ciCheck test`
4. Push branch:
   - `git push -u origin feat/<short-topic>`
5. Open PR:
   - `feat/<short-topic> -> dev`
6. Merge strategy to `dev`:
   - Squash merge

## Temporary Branch Lifecycle

- Work branches are temporary.
- Delete temporary branches immediately after merge or close (local and remote).
- Keep only `main` and `dev` as long-lived branches.
- Keep GitHub setting "Automatically delete head branches" enabled.

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
   - `git switch -c fix/<topic>`
2. Open PR:
   - `fix/<topic> -> main`
3. Tag patch release on `main`.
4. Mandatory back-merge:
   - `main -> dev`

## PR Ref Rules

- PR head/base arguments must be remote branch names, not local aliases.
- Examples:
  - `gh pr create --head main --base dev`
  - `gh pr create --head feat/my-change --base dev`

## Cleanup Checkpoint

- Removed temporary bootstrap safety branch at commit `e0cae51`.
- Cleanup date: 2026-02-15.

## Protection Policy

- `main`: PR-required, required check `ciCheck`, direct push blocked, required approvals currently `0` for solo maintainer flow.
- `dev`: required check `ciCheck`, lighter protection for fast integration.
