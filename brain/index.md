# Brain Index

_Auto-generated. Last updated 2026-10-24T02:16:22.642Z._

- [brain-installed-in-repo](pages/brain-installed-in-repo.md) — category: decision | tags: [process, agents, tooling] | The brain is committed to this repository - BRAIN.md, brain/, the skills bundle and the CLI - and the bundled installer must never be run in
- [desktop-releases-tagged-namespaced](pages/desktop-releases-tagged-namespaced.md) — category: decision | tags: [release, desktop, github] | Desktop builds ship as GitHub prereleases tagged desktop-v<version>, so the phone's manifest URL keeps resolving to phone releases.
- [fork-carried-as-overrides](pages/fork-carried-as-overrides.md) — category: concept | tags: [shell, shell-mobile, upstream, licensing] | shell/ and shell-mobile/ are upstream Nuvio forks kept as overrides/ slices applied over .upstream/, so every edit belongs in overrides/ or
- [manga-downloads-are-files](pages/manga-downloads-are-files.md) — category: decision | tags: [manga, downloads, offline, reader] | The Manga tab can keep chapters on the phone. A saved chapter is a directory of page images under the app's own files (`filesDir/manga-offli
- [manga-extension-repositories](pages/manga-extension-repositories.md) — category: decision | tags: [manga, sources, mihon, extensions, settings] | The Manga tab's catalogues come from repositories, plural. Settings -> Content & discovery -> Manga sources lists them under one plus, and t
- [manga-progress-from-position](pages/manga-progress-from-position.md) — category: decision | status: archived | tags: [manga, shelf, tracking] | The reader stores which chapter numbers it has actually opened, per title, and the
- [manga-read-record](pages/manga-read-record.md) — category: decision | tags: [manga, shelf, tracking, reader] | The reader stores which chapter numbers it has actually opened, per title, and the
- [manga-search-sections](pages/manga-search-sections.md) — category: decision | tags: [manga, search, sources, ui] | A keyword search asks every enabled source and the server answers one interleaved list, each card tagged with `source`, `sourceName` and (ne
- [phone-release-is-two-publishes](pages/phone-release-is-two-publishes.md) — category: decision | tags: [release, phone, github] | A phone release needs publish-apk.mjs and publish-github.mjs; only the GitHub upload is what an installed phone updates from.
