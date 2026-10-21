---
slug: roadmap
title: Roadmap
role: milestones
updated: "2026-10-21T03:27:03"
---

# Roadmap

Provisional. The first item is done in the tree and not yet released; the next is
the one `AGENTS.md` names as the agreed step; the rest are candidates, not
commitments.

## Milestones

```mermaid
gantt
  title Roadmap (provisional)
  dateFormat YYYY-MM-DD
  section Grounded
  Record reads per chapter instead of inferring :done, a0, 2026-10-21, 1d
  Port the film backend to TMDB on the phone    :a1, 2026-10-21, 14d
  section Candidate
  Finish the shelf backfill in one pass         :b1, after a1, 14d
  Unify the release scripts behind one command  :b2, after b1, 14d
```

- **Done: record reads per chapter.** The title page and the read ink on the
  chapter list come from a stored set of chapter numbers, so a reader who jumps
  is counted by what they opened (see [[manga-read-record]]). The
  trackers keep their own meaning and are told the chapter number.
- **Port `Fmovies.java` to TMDB.** Stated as the next step in `AGENTS.md`: the
  Java twin still scrapes f-movies.org and omits the `source` field, so the
  phone labels films `f-movies`.
- **Candidates.** Measuring a long imported shelf in one pass instead of fifteen
  rows per visit, and unifying the two publish scripts so a phone release cannot
  be half-published again (see [[phone-release-is-two-publishes]]).
