---
slug: background
title: Project background
role: project background
updated: "2026-10-21T02:05:40"
---

# Project background

## Why

The upstream site keeps its catalogue behind an authentication dance that is
different for every surface (catalogue, playback, search), and its players and
streams are pages rather than URLs. Doing that dance in each client means the
same reverse engineering four times, and every client breaking on its own when
upstream changes. So there is exactly one server that owns the dance and speaks
clean JSON and plain HLS to everything else.

## Goals

- Every client (desktop window, phone app, web PWA, Stremio addon) talks only to
  the local proxy, never to the site directly.
- Playback works inside the client's own native player, not in a browser tab or
  an embed frame.
- A published release reaches an installed client on its own: the desktop window
  checks this repository's releases on start, the phone checks the GitHub
  release manifest.
- Reading state (progress, shelf, tracking) survives a phone reinstall through
  the account and through MyAnimeList / AniList.

## Non-goals

- Hosting or mirroring media. The proxy relays what the upstream already serves,
  to the machine that asked for it.
- Keeping the retired Java WebView shell (`android/`, frozen at 1.0.42) in step.
- One proxy per client, or a client that embeds upstream credentials of its own.

## Target user

The owner of this checkout and their household, reading on one phone and one
desktop window, pointed at a server that runs on this PC.
