# Orbit: a private browser for the Mudita Kompakt

Firefox's browser engine (GeckoView) in one app, built for E Ink, with Proton VPN built in for the browser's own traffic.
It replaces Read & Listen: Kindle, Audible and Google open as default tabs every time Orbit starts.

## Why Firefox's engine
The Kompakt's built-in browser engine (used by every app that shows web pages, Proton Mail included) can no longer play
Audible: it refuses Widevine and AAC audio. Orbit brings its own engine, which plays through the phone's own Widevine
(L3) and audio decoders. Audible accepting it is likely, not certain: if Audible still does not play, try ☰ → Desktop
site on the Audible tab (it is the default for Audible) and the other way round.

## Build it (Android Studio)
1. Unzip, then File → Open → this folder. Let Gradle sync.
   - The first sync downloads Firefox's engine (about 100 MB) and may take a while.
   - If Android Studio says the Android Gradle plugin is too new, update Android Studio (Help → Check for Updates).
   - If it asks to install Android SDK Platform 37 (or "missing SDK"), click the install link.
2. Build → Generate Signed App Bundle or APK → APK → **Create new…** a key just for Orbit (e.g. `orbit-release.jks`,
   alias `orbit`; keep the file and passwords safe) → release. Use this same key for every Orbit build.
3. Install `app/release/app-release.apk` on the Kompakt (Mudita Center → Manage Files → App Installers, or adb).

(With automatic updates set up, you can skip Android Studio entirely: install the first `orbit.apk` from your GitHub
releases instead.)

## Automatic updates (security fixes, no rebuilding)
Firefox's engine gets security fixes every few weeks. A free GitHub build checks Mozilla **every day**; when there is a
new engine it builds Orbit with it, signs it with your Orbit key and publishes it. Orbit on the phone looks for it at
start and every 12 hours, downloads it, checks it is signed with **your** key (Android also refuses any other), and
installs it **when you leave Orbit and nothing is playing**. The first update asks you twice (allow Orbit to install
its updates; confirm the update); after that they install by themselves.

One-time setup (about 15 minutes, on the computer):
1. On github.com (a free account): **New repository**, name `orbit`, **Public** (so the phone can download the updates
   without a password; there is nothing private in it: the key stays in GitHub's encrypted secrets).
2. In the new repository: **uploading an existing file** → drag in everything from this unzipped folder (including the
   `.github` folder) → Commit.
3. Make Orbit's signing key if you have not yet (Android Studio, step 2 above). Turn the key file into text: in
   Windows PowerShell, in the folder with the file:
   `[Convert]::ToBase64String([IO.File]::ReadAllBytes("orbit-release.jks")) | Set-Clipboard`
4. Repository → **Settings → Secrets and variables → Actions → New repository secret**, four times:
   - `ORBIT_KEYSTORE_BASE64`: paste (the text from step 3)
   - `ORBIT_KEYSTORE_PASSWORD`: the key store password
   - `ORBIT_KEY_ALIAS`: the alias (e.g. `orbit`)
   - `ORBIT_KEY_PASSWORD`: the key password
5. **Actions** → *Orbit build* → **Run workflow**. About 15 minutes later **Releases** has `orbit.apk`.
6. Download that `orbit.apk` and install it on the phone with Mudita Center (this first time only).

From then on: ☰ → **About Orbit** shows the engine version and where updates come from; ☰ → **Check for updates** looks
now (or **Install the update** when one is waiting); ☰ → Settings → *Install security updates by themselves* switches
the automatic install off. The phone asks GitHub twice a day whether there is a new release (through the VPN when it is
on). If GitHub ever stops the daily check (it pauses schedules in repositories with no activity for 60 days), the build
makes a small commit every 45 days to prevent that.

Without automatic updates, rebuild in Android Studio every month or two: the project picks up the newest Firefox engine
by itself (`geckoviewVersion=+` in gradle.properties). If a new engine ever stops the project from building, put the
last good version number there (e.g. `161.0.20261118180411`); the GitHub build always uses the exact newest one.

## Using it
- **Top:** the tab count (tap for all tabs), the address/search box, and **VPN** (● on, … connecting, ! problem).
- **Bottom:** ◀ ▶ back/forward · ▲ ▼ one screen (in Kindle: one page) · ⟳ reload (✕ stops) · ☰ menu.
- **Volume buttons:** turn Kindle pages on the Kindle tab; everywhere else they set the media volume.
- **Default tabs:** ☰ → Add this page to the default tabs, or ☰ → Default tabs… to rename (tap the name), reorder (▲),
  remove (✕) or reset to Kindle, Audible and Google.
- **Audio** keeps playing with the screen off or in another tab; the notification has play/pause and back/forward.
- Links in other apps (e.g. an email) can open in Orbit: choose Orbit when the phone asks which app.

## Proton VPN (no Proton app)
1. ☰ → VPN… → **Get one from Proton**: Proton's site opens. Sign in (a free account works).
2. Downloads → WireGuard configuration: a name, platform Android, a server, Create, then Download.
3. Orbit recognises the file, stores it encrypted, and offers to turn the VPN on. The phone asks once to allow
   Orbit to set up a VPN: OK.

Only Orbit's traffic goes through the VPN; the rest of the phone is untouched. While the VPN is on, nothing loads
unless it is connected: if it drops, browsing pauses (the pages are closed and come back with the VPN). Several
configurations (servers) can be kept; tap one in ☰ → VPN… to switch. If downloading in Orbit does not work, make the
file on a computer, copy it to the phone, and use ☰ → VPN… → Import a file.

## Privacy (always on)
- Strict tracker blocking: ad networks, analytics, social-media trackers, fingerprinters, cryptominers, email trackers.
- Every site gets its own cookies (a site cannot follow you onto another); tracking codes are removed from links.
- HTTPS only; encrypted DNS (Quad9); sites are told not to sell or share your data (Global Privacy Control).
- Location, notifications, camera, microphone: refused. Pop-ups a page opens by itself: blocked.
- No Google Safe Browsing lookups, no usage data sent anywhere.
- ☰ → Settings… turns strict blocking, HTTPS only or encrypted DNS off (after a restart); per tab, ☰ → Tracker blocking
  on this tab turns blocking off for a site that breaks.
- ☰ → Forget all sign-ins and site data signs you out everywhere.

Amazon still knows it is you once you sign in to Kindle or Audible; Orbit keeps everyone else out.

## Files
`OrbitApp` (engine + privacy settings), `MainActivity` (screen, tabs, menus, kill switch), `Tab`, `Prompts` (page
dialogs), `Downloads` (files, WireGuard configs), `Vpn` (WireGuard tunnel limited to this app), `Secrets` (Keystore
encryption), `PlaybackService` (background audio), `Updater` + `UpdateReceiver` (self-updates),
`.github/workflows/orbit.yml` (the daily GitHub build).
