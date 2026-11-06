# BYD Arrival

An unofficial Android app that controls a BYD car from your phone. It offers a home-screen widget with one-tap buttons, a climate start that adapts to the temperature, custom button combinations, and an optional auto-start when you walk up to the car.

**[Download the latest APK](https://github.com/iliagerman/byd-arrival/releases/latest/download/byd-arrival.apk)** · **Source code:** https://github.com/iliagerman/byd-arrival

> **Unofficial software, use at your own risk.** This project isn't affiliated with or endorsed by BYD. It talks to BYD's private cloud API, which can change or stop working at any time. It sends real commands to a real car, including unlocking the doors, sometimes automatically and without asking for a PIN. Read [Security and privacy](#security-and-privacy) before you turn those features on.

## Will it work with my car?

You need:

- An Android phone running Android 13 or newer.
- A BYD car already linked to a BYD account in the official app, with a remote-control PIN set.

**Regions:** you choose your country at sign-in, from 101 countries, and the app selects the matching BYD server, one of 16. Only one Atto 3 in Israel has actually used the app. The other servers respond, but signing in on them hasn't been tried. Other BYD models may also support fewer commands.

## What it can do

| Button | What it sends |
|---|---|
| **Start car** | Smart climate start, described [below](#start-car). BYD has no remote engine-start command, so starting the A/C is what powers the car on. |
| **Turn off** | A/C off, and nothing else. On the Atto 3 this also shuts the car down. |
| **Unlock** / **Lock** | Unlocks or locks the doors. |
| **Open trunk** / **Close trunk** | Opens or closes the trunk. |
| **A/C on** | Starts the A/C at one fixed temperature that you choose. |
| **Driver seat heat** / **Passenger seat heat** | Turns that seat's heating on. |
| **Close windows** | Closes the windows. |
| **Flash lights** | Flashes the lights. |
| **Find car** | Flashes the lights and sounds the horn. |
| **Locate car** (home screen only) | Asks the car for its position and opens it in your maps app. |

**How well each one is tested:**

- **Confirmed on the author's Atto 3:** unlock, lock, A/C on, A/C off and locate.
- **Not yet confirmed on a car:** trunk, fan speed, seat heat, close windows, flash lights and find car. They use command names from the open-source [pyBYD](https://github.com/jkaberg/pyBYD) project.

**Not available:**

- A true engine shutdown, because its command isn't publicly known.
- Camera streaming, because BYD's API doesn't expose it for this car.

## Install

1. On your phone, open the [download link](https://github.com/iliagerman/byd-arrival/releases/latest/download/byd-arrival.apk).
2. Open the downloaded file. If Android asks, allow your browser to install unknown apps.
3. Google Play Protect may warn that it doesn't recognise the app, because it isn't on the Play Store. Choose to install anyway if you trust this source.

To check a download, compare it with the SHA-256 in that [release's notes](https://github.com/iliagerman/byd-arrival/releases/latest). Every build is signed with this certificate (SHA-256):

```
624a0b7fe91c8460919106c2d14db9f68804153c30decc8fb901ce8ef6d2e346
```

## First launch

1. **Sign in.** Choose your country, then enter your BYD email, password and six-digit control PIN. The country selects the server for you; change the server only if sign-in fails. The popup stays open until BYD accepts the login.
2. **Pick your car.** If the account has only one car, it's selected for you.
3. **Set up auto-start.** A short walkthrough explains auto-start, lets you set its distances, and turns it on. You can skip it and reopen it later with Settings → **Walk me through setup**.
4. **Grant permissions.** The app asks for each in turn:

| Permission | Why |
|---|---|
| Location, "Allow all the time" | Measures your distance to the car while the screen is off, and reads your Wi-Fi name. Only auto-start and the trusted Wi-Fi list need it. |
| Notifications | Shows command results and update notices. |
| Unrestricted battery | Stops Android from pausing auto-start. |
| Nearby devices | Only for a Bluetooth beacon. Asked only when you scan for one. |

Settings → **Permissions** shows a green ✓ or red ✗ for each, with a button to grant what's missing.

## Using the app

### Widget

Long-press your home screen → Widgets → **BYD Arrival**, or use Settings → **Add widget to home screen**.

- Tap a button to run it. There's no PIN or password prompt, because the app uses your saved PIN.
- A badge shows the result on the last button you pressed: grey `…` while running, green ✓ when BYD confirms, red ✗ if it failed. The notification has the full message.
- The top line shows the auto-start state: a shield icon means armed, a ring means not armed. For example: `Armed · 31 m · 16:37:04`, or `Off` when monitoring is stopped.
- To choose the buttons, go to Settings → **Widget** → **Choose buttons**. You can pick up to 10, with 5 per row, in the order you tick them. Custom actions can be included.

### Home screen

Shows the latest status, the auto-start state, a large **Start car** button and a tile for every action. Commands are never retried automatically. If a result is unknown, check the car before trying again.

### Start car

Start car reads the cabin temperature (or the outside temperature if the car has no cabin reading), picks a level and starts the A/C. If the car reports neither, it uses the Mild level. You can edit everything under Settings → **START CAR**:

| Level | Default range | A/C | Fan | Seat heat |
|---|---|---|---|---|
| Freezing | below 17 °C | 26 °C | 3 | driver |
| Cold | 17–20 °C | 26 °C | 3 | off |
| Mild | 20–24 °C | 20 °C | 3 | off |
| Warm | 24–27 °C | 18 °C | 5 | off |
| Hot | above 27 °C | 17 °C | 7 | off |

- **Seat heat:** driver and passenger can be switched separately for each level.
- **Temperature limits:** the car reports the range it accepts (17–33 °C on the Atto 3). A value outside that range gives an error and isn't sent.
- **Engine-on time:** 10 to 30 minutes in steps of 5, default 30. It applies to **Start car** and **A/C on**.

### Custom actions

Settings → **Custom actions** → **New custom action**. Give it a name and tick the steps; they run in the order you tick them, and stop at the first failure. For example, "Off & Lock" could be Turn off, then Lock. A custom action can be a widget button or the auto-start action.

### Auto-start near the car

This runs an action when your phone comes close to the parked car. It's off until you start it. The walkthrough after your first login covers the setup, or you can do it yourself under Settings → **AUTOMATION**:

1. Choose what **Auto-start runs** (default: Start car).
2. Set the distances and timing:
   - **Start the car when within:** default 15 m.
   - **Re-arm after getting further than:** default 20 m.
   - **Minimum minutes between automatic starts:** default 5.
3. Tap **Start monitoring**. The app reads the car's position, then follows your phone's location.
4. Walk away. The next time you come within the start distance, the action runs on your car.

You can't save values that don't work together, because the app adjusts them for you and shows the result:

- The start distance stays between 3 and 495 m.
- The re-arm distance stays at least 5 m beyond the start distance, up to 500 m.
- The minimum time stays between 1 and 60 minutes.
- A blank field uses its default.

How it behaves:

- **It fires once per arrival.** After firing, it waits until you've clearly left. The whole accuracy margin of a reading must be past the re-arm distance, so 30 m ±15 m doesn't count when the re-arm distance is 20 m.
- **It must see you leave first.** It starts disarmed, so turning it on beside the car does nothing.
- **Inaccurate readings can't fire it.** Readings less accurate than the re-arm distance are ignored.
- **A failed command stops monitoring,** with a message explaining why.
- **It restarts by itself** after an app update, after the phone restarts, and if Android stops the app. The event line then says so, for example `Restarted after the phone restarted`.
- **It keeps trying when it can't reach the car.** If BYD has no position for the car or the network isn't up yet (common right after a restart), it tries again after 1 minute, then less often, up to every 15 minutes. Only **Stop**, a failed car command, signing in again or forgetting credentials turn it off.
- **It shows when readings stop.** If no location reading arrives for 10 minutes, the status says `No reading since 02:09:09` instead of the last distance.
- **Restart monitoring after parking somewhere new.** The car's position is only read when monitoring starts.

Phone location is often several metres off, and worse indoors. Very short distances may not work reliably.

### Trusted Wi-Fi (home, office)

Under **Arm when connected to Wi-Fi**, connect to a network and tap **Add current Wi-Fi**. While monitoring is on:

- **The car is never started while you're connected to a listed network,** however close you are and however weak the signal.
- **A strong signal from a listed network arms the trigger,** as if you'd walked away. Nothing is sent to the car. A strong signal is one at or above **Strong signal from**, default −70 dBm. If you join on a weak signal and it gets stronger, for example as you walk inside, it arms then.
- **A weak signal is ignored,** for example your home Wi-Fi reaching the car. This way, sitting in the car can't arm the trigger and cause a repeat start.

So you can walk out of your home, lose the Wi-Fi, reach the car, and it starts.

To choose the value, tap **Add current Wi-Fi** inside and again at the car; each time it shows the signal where you are. Pick a value between the two. The field is saved when you leave Settings and applies straight away.

Android doesn't report every small signal change, and may report fewer while the screen is off. If you join on a weak signal and walk in without using the phone, it may only arm once you do.

### Bluetooth beacon (optional)

Auto-start can use a small Bluetooth beacon left in the car instead of GPS. Use a plain iBeacon or Eddystone beacon with a fixed address. Tracker tags change their address and won't work, and a car's own Bluetooth usually can't be found by this scan.

Under Settings → **Bluetooth beacon**, scan and tap your beacon, set the signal threshold (default −65 dBm), then tap **Start Bluetooth monitoring**. It fires after three strong readings in a row and re-arms after 60 seconds out of range. Signal strength is only a rough guide to distance, and Bluetooth signals can be spoofed.

## Updates

The app looks for new versions at launch, about every 12 hours in the background, and when you tap Settings → **Check for updates**.

- At launch, a dialog offers the update.
- In the background, you get one notification per new version.

Tapping either one downloads the APK in your browser. Open the file to install it. Your sign-in and settings are kept. Settings also shows your current version.

## Security and privacy

- **Your credentials stay on the phone, encrypted.** The email, password and PIN are encrypted with a key held in Android Keystore, and are excluded from Android backups.
- **They can be used while the phone is locked.** Auto-start needs this. It also means anyone who can reach your widget can control your car without entering a PIN. Keep a screen lock on your phone.
- **Auto-start can act without you.** Anyone carrying your unlocked or locked phone near the car can trigger it, and the action you chose may unlock the doors. GPS and Bluetooth signals can be spoofed.
- **The network traffic is limited.** The app connects only to BYD's server for your region and to GitHub (for the version check and downloads). There are no analytics or ads, and your location is only used on the phone to work out the distance.
- **To erase your data,** use Settings → **Forget credentials**, or uninstall the app.

## Troubleshooting

- **"App not installed" when updating:** an older test build was signed with a different key. Uninstall it and install again. You'll need to sign in again.
- **Sign-in fails:** check that your account works in the official app, then check the country and server in the sign-in popup. If your country's server is rejected, try the server for a neighbouring region. To change region later, use Settings → **Sign in or change region**.
- **The distance never changes or the time stops advancing:** location updates aren't arriving. Check that location is on and set to "Allow all the time", and that the battery setting is unrestricted.
- **Auto-start didn't fire:** look at the event line on the home screen, such as `Armed by GPS: 41 m ±8 m`, `Armed by Wi-Fi home: -58 dBm`, `Wi-Fi home too weak to arm: -78 dBm` or `Triggered at 12 m`. Also check whether you're still on a trusted Wi-Fi, and whether the minimum time has passed.

## For developers

Written in plain Java with no third-party runtime libraries. You need JDK 17 and the Android SDK (API 34).

```sh
git clone https://github.com/iliagerman/byd-arrival.git
cd byd-arrival
tools/test-core.sh            # JVM tests, no Android SDK needed
./gradlew assembleDebug       # debug APK in app/build/outputs/apk/debug/
./gradlew lintDebug
```

If you already have a copy of this folder without its `.git` directory, connect it to the repository without touching your files:

```sh
git init -b main
git remote add origin https://github.com/iliagerman/byd-arrival.git
git fetch origin
git reset origin/main
git branch --set-upstream-to=origin/main main
```

The `tools/` folder holds read-only account checks that send no car commands. They read `USERNAME`, `PASSWORD`, `PIN` and `COUNTRY` from environment variables.

### Release pipeline

Every push to `main` runs [`.github/workflows/release.yml`](.github/workflows/release.yml). Pushes that change only Markdown files are skipped.

1. Runs the tests, then builds and lints a signed release APK.
2. Sets the build number to the commit count plus 10. That number is the app's version.
3. Checks that the APK was signed with the expected certificate.
4. Publishes a release tagged `v<number>` with the APK as `byd-arrival.apk`. The app compares this tag with its own build number.

The pipeline needs two repository secrets: `RELEASE_KEYSTORE_BASE64` (a PKCS12 keystore with the alias `byd-arrival`) and `RELEASE_KEYSTORE_PASSWORD`.

Android only installs an update signed with the same key, so losing the key means every user must uninstall and reinstall.

**If you fork this project:** create your own keystore and secrets, replace `SIGNING_CERT_SHA256` in the workflow, and change `REPO` in `Updates.java`.

## License

[MIT](LICENSE). You can use, copy, change, sell and redistribute this project however you like. The only condition is keeping the copyright and license notice. It comes with no warranty.

## Credits

- [pyBYD](https://github.com/jkaberg/pyBYD) by Joel Kåberg: the BYD API details and the Bangcle codec tables.
- [hass-byd-vehicle](https://github.com/jkaberg/hass-byd-vehicle): the regional servers and the country-to-server table.
- [ioBroker.byd](https://github.com/TA2k/ioBroker.byd): a second reference for the command names.

The first two are MIT-licensed. Their notices are in [`THIRD_PARTY_LICENSE.txt`](app/src/main/assets/THIRD_PARTY_LICENSE.txt), which also ships inside the APK.
