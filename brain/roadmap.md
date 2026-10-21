---
slug: roadmap
title: Roadmap
role: milestones
updated: "2026-10-21T02:05:54"
---

# Roadmap

Provisional. Only the first two items are grounded in an existing decision or a
stated next step; the rest are candidate slices, not commitments.

## Milestones

```mermaid
gantt
  title Roadmap (provisional)
  dateFormat YYYY-MM-DD
  section Grounded
  Port the film backend to TMDB on the phone :a1, 2026-10-21, 14d
  Record reads per chapter instead of inferring :a2, after a1, 14d
  section Candidate
  Keep manga progress exact across a jump    :b1, after a2, 14d
  Unify the release scripts behind one command :b2, after b1, 14d
```

- **Port `Fmovies.java` to TMDB.** Stated as the next step in `AGENTS.md`: the
  Java twin still scrapes f-movies.org and omits the `source` field, so the
  phone labels films `f-movies`.
- **Record reads per chapter.** The shelf's progress is inferred from a single
  position (see [[manga-progress-from-position]]); a per-chapter record makes it
  exact when a reader jumps around.
- **Candidates.** Unifying the two publish scripts so a phone release cannot be
  half-published again, and finishing the shelf backfill so a long imported
  shelf measures itself in one pass instead of fifteen rows per visit.
