---
id: account-settings-push-is-three-args
title: The settings blob push is the older three-argument RPC
category: decision
status: active
tags: [account, sync, supabase, gotcha]
created: "2026-10-25T17:50:16"
updated: "2026-10-25T17:50:36"
---

<!-- compiled_truth -->
This app uses a Nuvio Supabase project whose `sync_push_profile_settings_blob` has exactly three parameters: `p_platform`, `p_profile_id`, `p_settings_json`. The client must not send `p_origin_client_id`.

## What went wrong

The upstream client (newer than this backend) adds `putSyncOriginClientId()` to the push. PostgREST resolves the function by argument names, so the four-argument call is rejected before the table is reached:

`PGRST202: Could not find the function public.sync_push_profile_settings_blob(p_origin_client_id, p_platform, p_profile_id, p_settings_json) ... Perhaps you meant ... (p_platform, p_profile_id, p_settings_json)`

Because a push only happens when a watched setting changes, and every push failed, a signed-in account had **no settings blob at all**. That is why the preferred audio/subtitle languages, the player and the themes appeared to be device-local: the code synced them, the RPC refused the write. The account also had no blob to pull, so a second device started from defaults.

## The fix

The fork carries `core/sync/ProfileSettingsSync.kt` and its `pushToRemoteLocked` sends only the three arguments. Its `pull` also seeds the blob from this device when the account has none, so an account that never had one becomes linked on the next start rather than waiting for a setting change.

## Neighbouring calls, measured

Probing the same project with the publishable key: `sync_push_library_items`, `sync_push_profiles` and `sync_push_provider_credentials` accept both the three- and four-argument shapes (the extra parameter has a default), so they are not affected. `sync_pull_media_servers` does not exist at all on this backend, which is the standing MediaServers step failure in every full sync - unrelated to settings.

## Evidence

Release build on 52070e9f, before: `pull(profileId=1) - no remote settings blob found`, then `could not create the settings blob` with the PGRST202 above. After: `pushToRemoteLocked(profileId=1) - success`, `created the account's settings blob from this device`; the next start logged `remote matches local`. The copy in `overrides/` is authoritative. If upstream is ever re-pinned and this file is not carried, the four-argument call comes back and every setting silently stops syncing again.


## Timeline

- time: 2026-10-25T17:50:16
  kind: decision
  summary: "Created this page: The settings blob push is the older three-argument RPC"
  source: session linking settings to the account
  affects: [account-settings-push-is-three-args]

- time: 2026-10-25T17:50:36
  kind: decision
  summary: "the backend has only the three-argument settings push; sending p_origin_client_id fails with PGRST202 and leaves the account with no blob"
  source: "ProfileSettingsSync.kt, logcat on 52070e9f, POST probes with the publishable key"
  affects: [account-settings-push-is-three-args]

- time: 2026-10-25T17:50:36
  kind: decision
  summary: "drop p_origin_client_id from the settings push and seed a missing blob from the device; verified on the signed-in release build"
  source: "phone 52070e9f, backend hijiurhppikjgammgdmg.supabase.co"
  affects: [account-settings-push-is-three-args]
