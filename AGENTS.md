# Working in this repository

## Standing instructions

**Refresh the installed desktop app after every change.**

The desktop app (SEKAI) is installed on this machine at
`C:\Users\micro\AppData\Local\Programs\SEKAI`, and it is a jpackage *app-image*:
a folder, not something an installer keeps in step. So the installed app only
changes if it is rebuilt and re-mirrored, and the rule is that it is never left
behind the tree. After every change — source, server, build config, or
documentation — run:

```bash
./shell/install-desktop.sh
```

It applies `shell/overrides/` over the build tree (`shell/.upstream`), runs
`:composeApp:createDistributable`, mirrors the result into the install folder,
and verifies the copy against the build file for file. `--stop` stops a running
window first (its jars are locked); `--no-build` installs the last build. It
needs JDK 17 and takes seconds when the build is already warm.

Details, including what the script deliberately does not regenerate, are in
[`shell/README.md`](shell/README.md).

## Where things are

| path | what it is |
| --- | --- |
| `server/` | the Node catalogue server and the web client (`client/` builds `public/index.html`) |
| `shell/` | the desktop app: `overrides/` is this fork's slice of NuvioDesktop, `sync.sh` fetches upstream and applies it, `install-desktop.sh` installs the result |
| `android/`, `mobile/` | the phone clients |
| `README-ANDROID-BUILD.md` | the long-form build and release documentation |
