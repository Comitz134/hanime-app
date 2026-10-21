---
id: brain-installed-in-repo
title: "The brain lives in this repository, at BRAIN.md plus brain/"
category: decision
status: active
tags: [process, agents, tooling]
created: "2026-10-21T02:06:36"
updated: "2026-10-21T02:06:46"
---

<!-- compiled_truth -->
The brain is committed to this repository - BRAIN.md, brain/, the skills bundle and the CLI - and the bundled installer must never be run in the project root.

## What was decided

The project keeps a Project Brain in the repository:

- `BRAIN.md` - the read/write contract at the project root.
- `brain/` - six root pages plus `brain/pages/*.md`, plain Markdown, travels in git.
- `skills/brain-*` and `bin/` - the toolbox and the CLI, also in the repo, so a
  fresh clone works with nothing installed and nothing on `PATH`.
- `AGENTS.md` and `CLAUDE.md` - carry the wired brain block. `AGENTS.md` keeps
  its project instructions above the `<!-- BEGIN brain.md -->` block, which the
  CLI updates in place and never rewrites wholesale.

## How to use it

From the project root:

```
node skills/brain-page/bin/brain.mjs list-pages
node skills/brain-page/bin/brain.mjs read-page <id>
node skills/brain-page/bin/brain.mjs read-root architecture
```

Reads and writes go through that CLI only. Never hand-edit a file under
`brain/`: the CLI is what keeps frontmatter well formed and makes a
compiled_truth rewrite append its own timeline entry atomically.

## The hazard worth remembering

`F:/BRAIN AI/brain-install.js` writes its whole bundled manifest into the
current working directory **unconditionally**, and that manifest includes
`README.md`, `package.json` and `LICENSE` at the top level. Run in this checkout,
it would have overwritten this project's README. It was therefore run in
`/f/tmp/brain-stage` and only `BRAIN.md`, `brain/`, `skills/` and `bin/` were
copied across. To reinstall or upgrade the toolkit, do the same: stage it
elsewhere, copy the four paths, never run the installer in the project root.

## Why in-repo

The brain has to outlive sessions, machines and agents. A folder in the
repository is copied by git, read by any agent that reads files, and needs no
service, key or daemon. `[[fork-carried-as-overrides]]` and
`[[phone-release-is-two-publishes]]` were both learned the expensive way and are
exactly the kind of thing that would otherwise have to be re-derived.


## Timeline

- time: 2026-10-21T02:06:36
  kind: decision
  summary: "Created this page: The brain lives in this repository, at BRAIN.md plus brain/"
  source: "install session, 2026-10-21"
  affects: [brain-installed-in-repo]

- time: 2026-10-21T02:06:36
  kind: decision
  summary: "recorded the install location, the CLI entry point, and the installer hazard that shaped it"
  source: "install session, F:/BRAIN AI/brain-install.js"
  affects: [brain-installed-in-repo]

- time: 2026-10-21T02:06:46
  kind: decision
  summary: index summary now reads as a sentence
  source: reworded the opening so the index summary reads as a sentence
  affects: [brain-installed-in-repo]
