---
id: manga-extensions-survive-r8
title: "A minified build keeps the extension API, Injekt's type tokens and Rhino"
category: decision
status: active
tags: [build, r8, release, manga, extensions]
created: "2026-10-25T00:44:09"
updated: "2026-10-25T00:44:20"
---

<!-- compiled_truth -->
The release build is minified (R8 + resource shrinking), and the manga sources only survive it because `composeApp/proguard-rules.pro` keeps three things that a debug build never needs. Each was found by installing the release APK on the phone and watching one source type fail while the other worked.

## The three rules

- **`-keep class eu.kanade.** { *; }`** - a Mihon extension is a separate APK whose bytecode links against this app's `eu.kanade.tachiyomi.source.*` by name. Renamed host classes are `NoClassDefFoundError` at load time, not at build time.
- **`-keep,allowshrinking class * extends uy.kohesive.injekt.api.FullTypeReference`** plus the already-present `-keepattributes Signature` - Injekt learns which type a registration belongs to by reading the generic superclass of its type token. Without the signature the token constructs with no type information (`IllegalArgumentException: TypeReference constructed without actual type information`), every registration lands under "no type", and the runtime starts while an extension's first `Injekt.get<Application>()` throws `InjektionException` - exactly the failure that was fixed in the debug build, back again in a different disguise. This rule is Mihon's own, verbatim.
- **`-keep class org.mozilla.javascript.** { *; }`** with `-dontwarn java.beans.**`, `-dontwarn java.awt.**`, `-dontwarn javax.swing.**` - Rhino is the engine behind SEKAI's script sources (MangaDex). Its Java-to-JSON bridge names `java.beans`, which Android does not ship; keeping only the engine and letting R8 merge that bridge into a class the runtime initialises turned every script source into `ExceptionInInitializerError` while extensions kept working. Keeping the jar whole also drags in its Swing debugger, hence the AWT/Swing suppressions. All three `-dontwarn` lines are needed or R8 refuses the build.

Mihon's own file begins with `-dontobfuscate`; this fork does not, so every name an external APK can reach has to be kept explicitly (the `kotlin`, `kotlinx`, `okhttp3`, `okio`, `org.jsoup`, `androidx.preference` and `uy.kohesive.injekt` keeps come from Mihon's "common dependencies used in extensions" list).

## How it was checked

On 52070e9f with the minified `androidApp-full-universal-release.apk` (2.0.5 / 205): a fresh install added keiyoushi's `index.pb` through `/manga/sources/add.json`, enabled the Omega Scans extension, and `search.json?q=the` answered 24 Mangafire + 24 MangaDex script + 12 Omega extension cards with no `errors`. `title.json` named the extension's metadata, `chapters.json` returned 153 chapters, `pages.json` returned 7 proxied page URLs and the first one fetched as a 200 `image/jpeg` of 5,555,802 bytes.

## What this does not cover

- A rule change is invisible to `:androidApp:testFullDebugUnitTest` and `:composeApp:testAndroidHostTest` - both run unminified. Only a release APK on a phone exercises them, so a release is not verified until one is installed.
- `publish-apk.mjs` reads the version from the APK, but the APK itself must be rebuilt after a rules change; the Gradle task reports success from a stale package otherwise (R8 runs only when its inputs moved).


## Timeline

- time: 2026-10-25T00:44:09
  kind: decision
  summary: "Created this page: A minified build keeps the extension API, Injekt's type tokens and Rhino"
  source: session that released 2.0.5
  affects: [manga-extensions-survive-r8]

- time: 2026-10-25T00:44:20
  kind: decision
  summary: Rewrote compiled_truth to the new best understanding
  source: brain update-truth
  affects: [manga-extensions-survive-r8]
