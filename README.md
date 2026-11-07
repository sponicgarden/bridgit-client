# Bridgit for Android, iPhone and Mac

**Designed for your favorite agent.**
Build distributed applications with real-world senses and networked intelligence.

Copyright 2026 Sponic Garden, Inc. Source available under the
[PolyForm Noncommercial License 1.0.0](LICENSE): free for any noncommercial use.

**Bridgit** turns an Android device into one your own AI agents can use: Claude,
Grok, Codex or any agent that can make HTTP or MCP calls. With your OK, paired
agents can take a photo, record a short clip, read the screen, send you
questions and notices, and run small applets. Each agent has its own key, every
feature switch starts off, the owner sets each agent's permissions on the
device, and every request is logged with the agent's name. **Bridgit Portal**
(`ios/`) is the companion iPhone app; `apple/` holds the shared Apple code and the Mac app.

- Website and download: https://bridgit.cc
- For agents: https://bridgit.cc/llms.txt and https://bridgit.cc/learn/index.json
- Releases (APK and SHA-256): [GitHub releases](../../releases) or https://bridgit.cc/download/

This repository holds the source of the apps. It is published from Bridgit's
development repository with each release, so it has one commit per release
rather than the full development history.

## What's here

| Folder | What it is |
|---|---|
| `app/` | The Android app (`cc.bridgit.app`) |
| `ios/` | Bridgit Portal for iPhone |
| `apple/` | Shared Apple code (BridgitCore, the applet runtime) and the Mac app |
| `site-content/` | Content both apps bundle and bridgit.cc serves: agent docs, sample cards, applet recipes, the first-run tour |
| `tools/bridgit` | The helper script agents can download from a device |

## Build the Android app

Needs JDK 17 and the Android SDK (platform 35, build-tools 35).

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk   # or sdk.dir in local.properties
./gradlew assemblePrivateDebug     # app/build/outputs/apk/private/debug/
```

Debug builds install alongside the official app as `cc.bridgit.app.debug`.
Release signing reads `keystore.properties` (not included); without it the
release APK is unsigned. Official releases are signed with the certificate whose
SHA-256 is `6bbd482d18efdc58ca28de8358405602ebb0405b479cabd36b37ff8242042f61`, so a
build signed with another key can't update an installed official build.

Some unit tests read files that only exist in the development repository; the
app itself builds from this repository alone.

## Build the iOS app

Open `ios/BridgitPortal/BridgitPortal.xcodeproj` in Xcode (or regenerate it with
`xcodegen` from `ios/BridgitPortal/project.yml`) and build the `BridgitPortal` scheme.

## Hosted services

The relay, AI photo and sound checks, applet backends, bug reports and remote
view run on Bridgit's servers and are not open source. The app works without
them over your own Tailscale network; the hosted services are optional.

## License

[PolyForm Noncommercial License 1.0.0](LICENSE); see also [NOTICE](NOTICE).
You may read, build, modify and share this code for any noncommercial purpose:
personal use, study, research, hobby projects, and use by charities, schools and
public bodies. Commercial use, including selling it, bundling it into a paid
product or running it as a paid service, needs a separate licence from Sponic
Garden, Inc. (support@bridgit.cc). The official app from bridgit.cc stays free to use.

The licence grants no rights to the Bridgit name or logo: if you publish a
modified version, give it a different name and icon.

Third-party components keep their own licences: Ktor and Netty, CameraX and
AndroidX, Material Components, ZXing and Kotlin coroutines (Apache 2.0) and the
Sentry Android SDK (MIT). Google ML Kit text recognition is **not open source**:
it is distributed under the [ML Kit Terms of Service](https://developers.google.com/ml-kit/terms).

Issues and suggestions are welcome as GitHub issues. Pull requests can't be
merged here directly, because this repository is regenerated from the
development repository; a change we accept is applied there and appears with
the next release.
