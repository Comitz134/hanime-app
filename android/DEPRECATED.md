# DEPRECATED — the WebView phone shell

This directory is the phone app's **first** implementation: a Java shell that
puts the server's web client inside a WebView and adds a device library and an
in-app player around it. It is kept for reference and for the record of how the
catalogue's upstreams were reverse-engineered; it is not developed any further.

| | |
| --- | --- |
| Last release | **1.0.42** (versionCode 43) |
| Package | `app.hanime.shell` |
| Signed by | `keystore/release.jks` (alias `shell`) |
| Successor | **`shell-mobile/`** — the Kotlin + Compose app, `app.hanime.shell` 2.0.0 |

## What "deprecated" means here

*   **No new builds from this tree.** `android/app/build.gradle` and the Java
    under `android/app/src` are frozen at 1.0.42. The updater endpoint they
    read is the repository's latest release, which is now the Kotlin app.
*   **Existing installs migrate, they are not stranded.** The successor keeps
    this app's package id and is signed with the same key, so the release the
    updater already consults installs *over* this app. Nothing needs to be
    uninstalled first; the account is signed in again in the new app, because
    preferences are not carried between the two implementations.
*   **A phone that does not update stays on 1.0.42.** It keeps working while
    its catalogue upstreams do; it simply receives no fixes. Installing the
    current release by hand does the same thing the updater would.

The source stays in the repository because the Kotlin app *carries* parts of it
(`Catalog`, `Anime`, `Token`, `Streams`, `HlsRelay`, `Signer`, `Manga` — see
[`../shell-mobile/NOTICE.md`](../shell-mobile/NOTICE.md)). That is also why the
GPL-3.0 obligation on the successor's distribution is met by this checkout: the
complete corresponding source of both is here.
