---
name: release
description: Merge and release a version of jcustomer-sfdc-connector - PR checks, version bump, GitHub release whose jar CI builds, Store pack. Use when the user asks to merge and release.
---

# Release jcustomer-sfdc-connector

The jar of a release is built by CI from the release's tag (`.github/workflows/build.yml`, job
`attach-to-release`), never locally. Releases are GitHub releases `vX.Y.Z`; the module is not on Nexus.

## In the PR (before asking to merge)

1. Version `X.Y.Z` in `pom.xml` (`<version>` of the project), `package.json` and the jar names in
   `README.md` ("Build & deploy").
2. `CHANGELOG.md`: a `## [X.Y.Z] - YYYY-MM-DD` section on top (Added / Changed / Fixed / Removed /
   Security), README lines for the behaviour changed, `.agents/` files when what they say changed,
   Javadoc of the classes touched. No em dash. This repository is public: before writing the commit,
   PR or changelog line of a fix, follow the wording rules of AIStartupKit `jahia-release-and-ci.md`.
3. Locales: `en.json` and `fr.json` in parity, every `t()` key present.
4. The local release checks the CI cannot run, as listed in AIStartupKit `jahia-release-and-ci.md`
   ("Every release goes through a pull request"), including `/jahia-review-java`; blocking findings
   fixed in the PR.
5. CI `Build` green (it runs the unit tests and checks the jar).

## Release

1. Squash-merge: `gh pr merge <n> --squash --subject "<title> (X.Y.Z) (#<n>)"`.
2. Notes: a one-line summary in bold, then the `CHANGELOG.md` section.
3. `git tag -a vX.Y.Z -m "..." <merge sha> && git push origin vX.Y.Z`, then
   `gh release create vX.Y.Z --verify-tag --title "vX.Y.Z - <summary>" --notes-file <notes>` with no
   asset: publishing the release starts the `Build` workflow, which builds the tag, checks that it
   matches the pom version and attaches `jcustomer-sfdc-connector-X.Y.Z.jar`.
4. Read it back: `gh release view vX.Y.Z --json assets` lists the jar; download it and check
   `unzip -p <jar> META-INF/MANIFEST.MF` has `Bundle-Version: X.Y.Z` and no `Jahia-Source-Folders`.

## Store pack

`0.Modules/store-submissions/jcustomer-sfdc-connector/` (outside the repository): `listing.html` (every
Store field, EN and FR), the release jar, `screenshots/`. On every release: replace the jar with the
release asset, update the version in the upload rows, add the version's change log (EN and FR) on top,
and update the description when a feature changed. The user uploads it to the Store; the change log
covers every version since the one the Store has.
