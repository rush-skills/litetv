# litetv

This repo is a fork of [cleanyourtv](https://github.com/Gathin23/cleanyourtv). It holds the debloat and the new
Home screen for one TCL Google TV. `README.md` is the upstream front page. The code from cleanyourtv is under the
MIT licence (see `LICENSE`).

These names point to the versions:

- The `merge-upstream` branch holds this setup merged with upstream, with an A/B test between the two versions.
- The `pre-upstream-merge` tag is an exact copy of this setup before the merge.
- `main` is a copy of the upstream `main` branch. Use **Sync fork** on GitHub to update it.

## The TV

| Item | Value |
|---|---|
| Model | TCL, device `G13`, product `G13_2K_GB`, model name "Smart TV" |
| Software | Google TV 14 (SDK 34), build `UKN2.241110.001 AU10` |
| Hardware | MediaTek MT9221, 954 MB of RAM, 32-bit (`armeabi-v7a`) |
| Matching example | `examples/tcl-43s5k` in cleanyourtv: same board and same firmware |

## What changed on the TV

1. **Disabled packages.** `debloat.txt` lists them with the reason for each line:
   - TCL push messages and log upload
   - Silent partner installs and partner tracking
   - Play Games, Google feedback and the Google TV app
   - The Google TV setup wizard
   - The stock launcher (`com.google.android.apps.tv.launcherx`)

   The debloat disabled the packages for user 0 and uninstalled no system package.
2. **Uninstalled apps.** NordVPN and ExpressVPN, on request.
3. **New Home screen.** The cleanyourtv launcher (`com.gathin.tvhome`) is Home. The dock holds
   YouTube, Netflix, Prime Video and JioHotstar. The gear button on the remote opens a quick panel at the top
   right: Picture, Sound, Inputs, Bluetooth, Network, Ad blocking, TV settings, Apps and Launcher settings.
   The Inputs tile and the Input button list HDMI 1, HDMI 2, AV and Live TV in the same panel.
   The Sony LIV button opens Apple TV, and the TCL Channel button opens JioHotstar.
   The quick panel has two versions. See [Compare versions](#compare-versions).
4. **Up Next.** A tile right after the dock opens Continue watching and the latest rows from your apps. A
   Continue watching row also shows above the dock. Press Up from the dock to get to it.
5. **Ad-blocking DNS.** Private DNS uses `dns.adguard-dns.com`. If AdGuard stops answering, the launcher
   switches to the router's DNS and switches back later.
6. **Screensaver.** Aerial Views 1.8.5 (`com.neilturner.aerialviews`) shows aerial videos. The APK came
   from the GitHub release page, not from the Play Store.

These items did not change, on purpose:

- **The TCL browser (BrowseHere):** you use it.
- **Google Assistant, Chromecast and TCL firmware updates:** you use them.
- **Ambient mode:** it stays enabled. Android uses it again if the Aerial Views setting goes away.
- **The 1280x720 display size:** the firmware sets it on purpose for this low-memory TV. A larger size
  makes the UI slower, and text does not get sharper.
- **The factory packages:** never open `com.tcl.factory.view` or `mediatek.factorymenu.ui`.

## Set up the computer

The scripts need Python 3 and adb. The launcher build also needs Java 17 and the Android SDK.

```bash
python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt
brew install openjdk@17 && brew install --cask android-commandlinetools
SDK=/opt/homebrew/share/android-commandlinetools
JAVA_HOME=/opt/homebrew/opt/openjdk@17 $SDK/cmdline-tools/latest/bin/sdkmanager --sdk_root=$SDK "platforms;android-34" "build-tools;34.0.0"
```

Connect to the TV over network ADB on port 5555. You do not need a pairing code for this port.

```bash
adb connect 192.168.1.50:5555
```

The commands in this file use `192.168.1.50`. Find your TV's address in **Settings > Network & Internet**, and use it
in `TV_HOST` for every command.

## Undo the changes

| What you want | Command |
|---|---|
| Turn one app on again | `TV_HOST=192.168.1.50 tools/venv/bin/python tools/tv.py 'pm enable --user 0 <package>'` |
| Give Home back to Google TV and keep the rest | `TV_HOST=192.168.1.50 ./rescue.sh` |
| Undo everything | `TV_HOST=192.168.1.50 ./restore.sh` |
| Undo everything and remove the new launcher | `UNINSTALL_LAUNCHER=1 TV_HOST=192.168.1.50 ./restore.sh` |
| Undo everything and remove Aerial Views | `UNINSTALL_AERIAL_VIEWS=1 TV_HOST=192.168.1.50 ./restore.sh` |

After you turn an app on again, put a `#` in front of its line in `debloat.txt`. Then a later run of
`tools/disable.sh` does not disable it again.

`restore.sh` turns on every package in `debloat.txt` and gives Home back to the Google TV launcher. It
also resets Private DNS, the screensaver and the accessibility settings to their values before the debloat. The undo
record is in `~/.cleanyourtv/`. Keep that folder. If `restore.sh` finds no record for the TV's address, it turns
on every package in the list. The TV had no disabled package before the debloat, so the result is the same.

The full backup from before the first change is in `backup/20261022-034349/`. It holds the package
lists, `getprop` and all settings.

A factory reset also clears every change, because the changes are in user data only.

## Compare versions

Upstream and this fork each built some parts their own way. This build holds both, and upstream's version is the
default. Compare the two on the TV, then keep one.

| Part | Upstream | Ours |
|---|---|---|
| Quick panel | Rows for TV settings, Picture, Network and Apps | Tiles with Inputs, Bluetooth and Ad blocking. The Settings pill and the Input button also open it |
| Launcher settings | Upstream's rows | Sections, with a panel that says what each row does |
| Inputs menu | Upstream's list, with `inputs_connected_only` and the vendor input keys | The list from `TvInputs` |
| Dock padding | 14 dp above and below, 26 dp corners | 22 dp, 30 dp corners |
| Hold OK menu | Move, dock actions, App info | Move and App info |
| Reorder hint | A toast at the start | A bar for the whole mode |

**On the TV:** hold OK on the **Settings** pill and select **Compare versions**. Select a row to switch it. Home uses
the new choice when you go back.

**From the computer:**

```bash
adb shell am broadcast -n com.gathin.tvhome/.AbTestReceiver --es key panel --es value ours
adb shell am broadcast -n com.gathin.tvhome/.AbTestReceiver --es key all --es value upstream
adb shell am broadcast -n com.gathin.tvhome/.AbTestReceiver
```

The keys are `panel`, `settings`, `inputs`, `dock`, `tile_menu` and `reorder_hint`. The last command only prints the
current choices.

Our additions that upstream does not have stay on in both versions: Up Next, the Continue watching row, the DNS
guard, Sort apps, the backgrounds and 3D motion. All upstream improvements stay on too.

Four keys belong to these additions. `up_next` and `continue_row` are in `config.xml`. A theme can also set
`live_background` (`aurora`, `stars`, `waves` or `off`) and `parallax`.

The copies are `QuickPanelActivity`, `OursLauncherSettingsActivity`, `TvInputs` and the marked branches in
`MainActivity`. `Variants` holds the switches. After you decide, remove the version that you do not keep.

## Launcher settings

Hold OK on the **Settings** pill and select **Launcher settings**, or open it from the quick panel. Upstream's screen
has a **More settings** row that opens our screen, and our screen has one that opens upstream's. So both versions
reach every setting. Our screen has these rows:

| Row | What it does |
|---|---|
| Apps per row, Rows below the dock | The size of the grid |
| Sort apps | Custom order, Recently used, Most used or Name, for the apps after the dock |
| Continue watching row | The row of shows above the dock, on or off |
| Reorder apps, Edit dock | Starts that mode on Home. A bar at the bottom tells you which keys to press |
| Background | Plain, Aurora, Scenes, Festive, or a live background: Northern lights, Starfield or Waves |
| Wallpaper | One picture of the background, or all of them in turn. It shows only for a background with several pictures |
| 3D motion | The background moves a little against the focused tile |
| Animations, Advanced effects | How much the tiles move |

To move one app, hold OK on it and select **Move**. Then use the arrows and press OK to put it down.

Recently used and Most used need usage access. `launcher/build-and-install.sh` gives it with
`appops set com.gathin.tvhome GET_USAGE_STATS allow`. Without usage access, Home counts only the apps that you
open from Home.

## Change the dock order

Use one of these ways:

- **On the TV:** hold OK on the **Settings** pill, then select **Edit dock**. You do not need a computer.
- **In the build:** change `dock_apps` in `launcher/app/src/main/res/values/config.xml`. Then run
  `HOME_KEY_SERVICE=1 RESTORE_KEY_SERVICES=1 TV_HOST=192.168.1.50 launcher/build-and-install.sh`.
- **In a theme:** put `"dock_apps": [...]` in a theme's `config.json` (see below).

To find a package name, run `TV_HOST=192.168.1.50 tools/venv/bin/python tools/tv.py 'pm list packages'`.

## Change the wallpaper

1. Make a folder, for example `themes/mine/`.
2. Put a dark JPEG of 1920x1080 in it as `wallpaper.jpg`. Keep it below about 300 KB.
3. Put this `config.json` next to it: `{ "wallpaper": true }`.
4. Push the theme: `TV_HOST=192.168.1.50 tools/theme.sh themes/mine`.

To remove the theme, run `TV_HOST=192.168.1.50 tools/theme.sh --reset`. `themes/README.md` lists all
theme keys, including a rotating wallpaper collection.

## Build and install the launcher

```bash
HOME_KEY_SERVICE=1 RESTORE_KEY_SERVICES=1 TV_HOST=192.168.1.50 launcher/build-and-install.sh
```

The script builds the APK and installs it. Then it gives the launcher these permissions:

- `ACCESS_FINE_LOCATION`, for the Wi-Fi name
- `READ_TV_LISTINGS`, for Up Next
- `GET_USAGE_STATS`, for Sort apps
- `WRITE_SECURE_SETTINGS`, for the DNS fallback and the Bluetooth tile

**Caution:** Always set `RESTORE_KEY_SERVICES=1`. Without it, the script takes `WRITE_SECURE_SETTINGS` back. AdGuard
then stays on, but the DNS fallback stops.

It makes the launcher Home and checks the result. Always set `HOME_KEY_SERVICE=1`: Android switches the
gear-button service off at each install.

**Caution:** Do not press the remote during an install. While Android replaces the launcher, Home can
briefly offer TCL's factory screen.

## Up Next

The **Up Next** tile opens a screen with these rows:

1. **Continue watching**, newest first, with a progress bar and the time left.
2. One row for each "latest" channel that an app publishes, for example "YouTube · Trending".

Select a card to open the show in its app. Up and Down move one row. Back returns to Home.

An app shows up only if it publishes rows to the TV provider. On 2026-10-22 these apps did:

| App | Continue watching | Latest rows |
|---|---|---|
| JioHotstar | yes | yes |
| Prime Video | yes | yes |
| Apple TV | yes | yes |
| Crunchyroll | yes | yes |
| Spotify | yes | yes |
| YouTube | no | yes |
| ZEE5 | no | yes |
| Stremio | no | yes |
| Netflix | no | no |

Netflix publishes nothing, so Up Next cannot show Netflix titles. To turn the tile off, set `up_next`
to `false` in `config.xml` and build again.

## Ad-blocking DNS

Private DNS sends every DNS lookup to AdGuard over TLS. AdGuard does not answer for known ad servers, so
the ads from those servers do not load. It does not block YouTube ads.

In this strict mode, Android has no second server. If the TV cannot reach AdGuard, every app loses the
internet. The launcher's DNS guard prevents this:

1. When AdGuard stops answering for 30 seconds, the guard switches to the router's DNS and shows a
   message.
2. Every 10 minutes it tries AdGuard again. When AdGuard answers, the guard switches strict mode back on.

The guard runs while the launcher runs, and it also checks each time you return to Home. If an app
loses the internet, press Home and wait 30 seconds.

**The switch:** hold OK on the **Settings** pill, then select **Ad-blocking DNS**. The label shows
`on`, `off` or `paused` (the guard uses the router's DNS for now). Google TV's own Settings has no
Private DNS screen.

**Without the launcher:** switch AdGuard off from the computer.

```bash
TV_HOST=192.168.1.50 tools/venv/bin/python tools/tv.py 'settings put global private_dns_mode opportunistic'
```

**Test the guard:** these two commands copy an AdGuard outage and then end it. The TV has no internet
for about 30 seconds.

```bash
adb -s 192.168.1.50:5555 shell am broadcast -n com.gathin.tvhome/.DnsGuardTestReceiver --es host broken.invalid
adb -s 192.168.1.50:5555 shell am broadcast -n com.gathin.tvhome/.DnsGuardTestReceiver --es host '""' --ez retry_now true
```

Only the adb shell can send these commands: the receiver requires `WRITE_SECURE_SETTINGS`.

## Screensaver

Aerial Views is the screensaver. It streams 1080p aerial videos from the internet and keeps no video on
the TV.

- **When it starts:** after 10 minutes with no input. A video that plays stops it from starting.
- **Settings:** open the Aerial Views app. Keep the quality at 1080p and keep local videos off. This TV
  has a 2K panel and little RAM.
- **Updates:** the Play Store does not update an app from GitHub. To update it, get a newer APK from the
  [release page](https://github.com/theothernt/AerialViews/releases). Then install it with `adb install -r`.

Google TV has no menu to choose a screensaver from another app. This command sets it:

```bash
TV_HOST=192.168.1.50 tools/venv/bin/python tools/tv.py 'settings put secure screensaver_components com.neilturner.aerialviews/.ui.screensaver.DreamActivity'
```

If a Google TV update sets the screensaver back to Ambient mode, run the command again. To go back to
Ambient mode yourself, run `settings delete secure screensaver_components` in the same way.

## TCL analytics

`com.tcl.ttvs` holds both TCL's analytics and TCL's firmware updater. Android does not let adb disable
one service inside a system package: the TV answered `Shell cannot change component state`. So the
package stays on, and the firmware updates continue to arrive. You can choose one of these instead:

- Block TCL's analytics servers with a DNS service that has a custom block list, if the updater uses
  other servers.
- Disable `com.tcl.ttvs`, and turn it on again when you want to check for updates.

## Close ADB when you finish

Port 5555 has no encryption. This TV asks before it trusts a new computer (`ro.adb.secure=1`), but do
not leave the port open:

1. On the TV, go to **Settings > System > Developer options**.
2. Switch off **USB debugging**, **Network debugging** and **Wireless debugging**.
3. Select **Revoke USB debugging authorisations**.
4. Restart the TV.
5. On the computer, run `tools/venv/bin/python tools/adb-closed.py 192.168.1.50`. Only "Connection
   refused" proves that the TV closed the port.

Before the next change, switch the debugging options on again.

## Files

| File | Purpose |
|---|---|
| `debloat.txt` | The disabled packages, with the reason for each line |
| `restore.sh` | Full undo |
| `rescue.sh` | Gives Home back to the Google TV launcher |
| `launcher/` | The Home screen, with Up Next, the DNS guard and the A/B test added |
| `tools/` | cleanyourtv tools: `tv.py`, `disable.sh`, `restore.sh`, `theme.sh` and others |
| `themes/` | cleanyourtv themes and the theme key list |
| `backup/`, `shots/` | The backup and the screenshots. Git does not track them. |
| `README.md`, `examples/`, `docs/`, `addons/`, `PROMPT.md` and the other files | The cleanyourtv files. `README.md` has one added line that links here, and `docs/launcher.md` names the new rows |
