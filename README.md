# OUYA Browser

A controller-first web browser for the OUYA (Android 4.1.2, Tegra 3, 1 GB RAM). It embeds Chromium 53 through
[Crosswalk](https://github.com/crosswalk-project/crosswalk) because the stock Android 4.1 WebView is far too old
for today's HTTPS sites.

> **Status: untested on hardware.** The code was written and checked in a sandbox with no Android SDK, no
> network access to the build repositories and no OUYA. See [What has and hasn't been verified](#what-has-and-hasnt-been-verified).

## Build the APK

The easiest way is GitHub Actions, because it can reach the Android SDK and the Crosswalk Maven repo:

1. Push this folder to a GitHub repository (branch `main`).
2. Open the **Actions** tab. The *Build APK* workflow runs on every push and on demand.
3. Download the `ouya-browser-apk` artifact. Pushing a tag such as `v0.1.0` also publishes the APK as a release.

Or build locally with JDK 17 and the Android SDK (platform 34) installed:

```
./gradlew assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```

## Install on the OUYA

Enable *Settings > Developer > USB debugging* (or ADB over the network), then:

```
adb install -r app-release.apk
```

It appears under **Make** (it declares `tv.ouya.intent.category.APP`). To list it under **Play** instead, change that
category to `tv.ouya.intent.category.GAME` in `app/src/main/AndroidManifest.xml`.

## Controls

| Input | Action |
|---|---|
| Left stick | Move the cursor |
| Right stick | Scroll |
| **O** | Click (hold and move to drag) |
| **U** | Address bar / search |
| **Y** | Menu |
| **A** | Back, then close tab, then exit |
| **L1 / R1** | Previous / next tab |
| **L2 / R2** | Page up / down |
| D-pad up / down | Scroll |
| D-pad left / right | History back / forward |
| **L3** / **R3** | Precision cursor / cycle page zoom |
| Click a text field | Opens the built-in on-screen keyboard |

The touchpad, a USB mouse and a USB/Bluetooth keyboard also work. The menu has tabs, bookmarks, history and settings
(search engine, desktop/mobile mode, zoom, cursor speed, overscan safe area, and more).

## How it stays small

- Only the `armeabi-v7a` Crosswalk libraries are packaged (the OUYA is 32-bit ARM). That is the bulk of the APK.
- No AppCompat/AndroidX: the UI is plain framework views plus a few custom-drawn overlays.
- **One live engine view for all tabs.** Background tabs are remembered URLs and reload when you switch to them. Six tabs
  therefore cost the memory of one. The trade-off is that switching tabs reloads the page and loses scroll position.
- The engine cache is cleared on low-memory signals.

## Limitations you should know about

- **GitHub's JavaScript will not fully run.** GitHub now ships ES2020 syntax (optional chaining, nullish coalescing).
  Crosswalk's newest Chromium is 53 (2016) and does not parse that, so JavaScript-driven parts of GitHub (and many other
  current sites) fail. Server-rendered pages load, but expect missing features. No embeddable engine that runs on Android
  4.1 is new enough. Gecko 68 (GeckoView) is the next-best candidate and would run noticeably more of the modern web at
  the cost of more RAM; it is not implemented here.
- **Certificates.** Android 4.1.2 has a 2012 root store. The app registers 33 current public roots
  (`assets/roots.pem`) with Chromium through an internal hook (`X509Util.addTestRootCertificate`) reached by reflection.
  If that hook is missing in your build, untrusted sites show a prompt with a per-session "Proceed anyway (unsafe)" choice.
  *Settings* shows whether the roots were added. Also check the OUYA's date and time: a wrong clock fails every certificate.
- No downloads, no file upload dialogs, no popups (new windows load in place), no DRM video.
- JavaScript `alert()` / `<select>` dropdowns use Android's own dialogs, which may not respond to the O button on the OUYA.

## What has and hasn't been verified

Verified in the sandbox:

- All Java compiles (Java 8 source level) against **stubs I wrote** for the Android 4.1 and Crosswalk 23 APIs. This
  checks internal consistency only; the stubs encode my belief about those APIs, not the real jars.
- URL resolution, stick dead zone, the on-screen keyboard (every key reachable in every layer; typing URLs, mixed case,
  symbols and spaces using only controller presses), and the list/menu overlay logic.
- All injected JavaScript (click-to-keyboard detection, text insertion that React-style inputs accept, scrolling, zoom) run
  in headless Chromium: 21 checks pass.

**Not verified** (needs your OUYA or CI):

- The real build. If it fails at an `@Override` in `MainActivity` on one of the Crosswalk callbacks
  (`XWalkResourceClient`, `XWalkUIClient`) or on `XWalkNavigationHistory`, my recollection of the 23.53 API is off by
  a signature; the fix is a one-line change.
- Whether Crosswalk's engine starts on the OUYA's Tegra 3 (it needs ARMv7 with NEON; if not, the app shows a message
  listing the CPU features instead of crashing).
- That mouse-wheel scrolling and hover events reach the page. If scrolling does nothing, set
  *Settings > Scroll method > JavaScript*.
- Controller details beyond the documented OUYA mapping (for example whether the triggers report as L2/R2 keys or as axes;
  both are handled).
- Rendering mode: *Compatible* (TextureView) is the default so overlays always draw above the page. *Fast* (SurfaceView)
  may be quicker on the Tegra 3 but is untested.

## Project layout

```
app/src/main/java/app/ouyabrowser/
  MainActivity.java   engine, tabs, input routing, menus
  Pad.java Btn.java   OUYA button/axis mapping
  CursorView.java HudView.java ListOverlay.java OskOverlay.java   custom-drawn UI
  CertTrust.java      extra root certificates
  Web.java            JavaScript snippets run inside pages
  Prefs.java Store.java Urls.java Safe.java Ui.java Overlay.java
app/src/main/assets/  home.html, roots.pem, xwalk-command-line
```
