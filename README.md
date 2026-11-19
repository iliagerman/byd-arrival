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
| **Start car** | Smart climate start, described [below](#start-car). BYD has no remote engine-start command, so starting the A/C is what powers the car on. This is remote mode: to drive, you need the key, or tap **Turn off**, then **Unlock**, then start the car yourself. |
| **Turn off** | A/C off, and nothing else. On the Atto 3 this also shuts the car down. |
| **Unlock** / **Lock** | Unlocks or locks the doors. |
| **Close & lock** | Turns the car off (A/C off), closes the windows and the tailgate, then locks. Every step is sent even if one fails, so a failure can't leave the car unlocked; the result lists what failed. |
| **Open trunk** / **Close trunk** | Opens or closes the trunk. |
| **A/C on** | Starts the A/C at one fixed temperature that you choose. |
| **Driver seat heat** / **Passenger seat heat** | Turns that seat's heating on, at high. |
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
| Nearby devices | Only for Bluetooth beacons. Asked when you first scan for one. |

Settings → **Permissions** shows a green ✓ or red ✗ for each, with a button to grant what's missing.

## Using the app

### Widget

Long-press your home screen → Widgets → **BYD Arrival**, or use Settings → **Add widget to home screen**.

- Tap a button to run it. There's no PIN or password prompt, because the app uses your saved PIN.
- A badge shows the result on the last button you pressed: grey `…` while running, green ✓ when BYD confirms, red ✗ if it failed. The notification has the full message.
- The top line shows the car's battery level next to its name. The app reads it from the car every 30 minutes, after each command, and when you open the app (tapping the top line opens it) if the last reading is over 5 minutes old. Reading it sends nothing to the car.
- The top line shows the auto-start state: a shield icon means armed, a ring means not armed. For example: `Armed · 31 m · 16:37:04`, or `Off` when monitoring is stopped.
- The buttons are Start car, Turn off, Unlock, Lock and Close & lock until you choose others under Settings → **Widget** → **Choose buttons**. You can pick up to 10, with 5 per row, in the order you tick them. Custom actions can be included.

### Home screen

Shows the latest status, the auto-start state, a large **Start car** button, a tile for every action and **Recent actions**. Commands are never retried automatically, with two exceptions where BYD refuses a request without acting on it. Signing in with the same account elsewhere, for example in the official app, ends this app's session, so the app signs in again and sends the request once more. And when the car is still busy with the previous command (code 6024, for example windows still closing during Close & lock), the app waits 5 seconds and sends it again, up to 3 times. Automatic actions (auto-start and the walk-away action) are tried once more after 20 seconds when the network failed before any command was sent, for example while checking the PIN; the car can't have acted then. If a result is unknown, check the car before trying again.

**Recent actions** lists the last 40 actions, newest first. Each shows when it ran, what triggered it, what ran and how it ended:

- **Triggers:** `App`, `Widget`, `Bluetooth: CP27-60BC at -52 dBm`, `Distance: 12 m from the car`, `Walk away by Bluetooth: beacon out of range` or `Walk away by distance: 230 m ±8 m`.
- **Results:** a green ✓ with BYD's confirmation and what Start car chose, a red ✗ with the error, or a grey – for an automatic action the app deliberately didn't send, with the reason (for example `Not sent: the car moved 640 m since you were at it, so someone else may be driving it`).

### Start car

Start car reads the cabin temperature, picks the matching range and starts the A/C with that range's settings.

- **No cabin reading:** the car has none while it's asleep, and then reports 0 °C outside, which isn't real. So the app asks a free weather service (Open-Meteo, no account) for the outside temperature at the car's last known position, rounded to about 1 km. If that fails too, it uses the Mild range. The result says which it used, for example `no cabin reading, outside 21 °C (weather) → Mild`.
- Edit everything under Settings → **START CAR**. Every change is saved straight away, so there's no Save button.

| Range | Default | A/C | Fan | Seat heat |
|---|---|---|---|---|
| Freezing | below 17 °C | 24 °C | 3 | driver high |
| Cold | 17 to 20 °C | 26 °C | 3 | off |
| Mild | 20 to 24 °C | 17 °C | 3 | off |
| Warm | 24 to 28 °C | 17 °C | 5 | off |
| Hot | 28 °C and above | 17 °C | 7 | off |

A range includes its start and stops just before the next one starts: Mild covers 20 °C up to, but not including, 24 °C.

- **Where each range starts:** set with **−** and **+**. The range below then ends there automatically, so there's one border to set per range and they can't overlap or leave a gap. Moving a border past the next one pushes that one along, keeping every range at least 1 °C wide.
- **A/C and fan:** also set with **−** and **+**, within what the car accepts: A/C 17–33 °C on the Atto 3, fan 1–7. Values from older versions below 17 °C become 17 °C.
- **Seat heat:** **Off**, **Low** or **High**, set separately for the driver and the passenger in each range, also with **−** and **+**. Off leaves that seat as it is. Settings from older versions, where seat heat was on or off, become High (what was sent) or Off.
- **Engine-on time:** 10 to 30 minutes in steps of 5, default 30. It applies to **Start car** and **A/C on**.
- **A/C on button:** its own temperature, also 17–33 °C, with the fan on automatic.

### Custom actions

Settings → **Custom actions** → **New custom action**. Give it a name and tick the steps; they run in the order you tick them, and stop at the first failure. For example, "Off & Lock" could be Turn off, then Lock. A custom action can be a widget button, the auto-start action or the walk-away action.

### Auto-start near the car

This unlocks and starts the car when your phone comes close to the parked car. It's off until you start it. The walkthrough after your first login covers the setup, or you can do it yourself under Settings → **AUTOMATION**:

1. Choose what **When you arrive, run** (default: Start car). It always unlocks the doors first; if your custom action already includes Unlock, it runs in your order instead. Choose Unlock if you drive without the key: Start car and A/C on put the car in remote mode, and BYD then asks for the key before you can drive, even after another unlock (the official app's unlock too). With the car off, an unlock lets you start it yourself.
2. Set the distances and timing:
   - **Start the car when within:** default 15 m.
   - **Re-arm after getting further than:** default 20 m.
   - **Minimum minutes between automatic starts:** default 5.
   - **Check every:** default 5 seconds. How often the app measures your distance. Shorter reacts sooner but uses more battery. Android can deliver location readings less often than asked, especially indoors.
3. Tap **Start monitoring**. The app reads the car's position, then follows your phone's location.
4. Choose **When you walk away, run** (default: Close & lock), or Nothing to turn it off.
5. Walk away. The next time you come within the start distance, the car unlocks and the action runs.

You can't save values that don't work together, because the app adjusts them for you and shows the result:

- The start distance stays between 3 and 495 m.
- The re-arm distance stays at least 5 m beyond the start distance, up to 500 m.
- The minimum time stays between 1 and 60 minutes.
- The check interval stays between 1 and 60 seconds.
- A blank field uses its default.

How it behaves:

- **It fires once per arrival.** After firing, it waits until you've clearly left. The whole accuracy margin of a reading must be past the re-arm distance, so 30 m ±15 m doesn't count when the re-arm distance is 20 m.
- **It must see you leave first.** It starts disarmed, so turning it on beside the car does nothing. After an app update, a phone restart or Android stopping the app, both the beacon and the distance trigger keep whether they were armed, so being near the car at that moment doesn't leave them disarmed.
- **Inaccurate readings can't fire it.** Readings less accurate than the re-arm distance are ignored.
- **A failed command stops monitoring,** with a message explaining why. A failed walk-away action doesn't, because it can't repeat before your next arrival.
- **One start and one walk-away per stay.** Whichever trigger notices your arrival first (a beacon or the distance) starts the car, and the other stays silent until you've left. Leaving works the same way: whichever notices first that you walked away runs the walk-away action, once. That's the beacon going out of range (usually within about a minute), or getting past the re-arm distance.
- **The walk-away action runs only when you walked away from the parked car.** You must have been with the car since it last ran (an auto-start, or you drove it); if someone else then drives it off, that stay is over and it doesn't run.
  - **By distance:** BYD must report the car still at its spot and you on foot. If the beacon was at full strength in the last 15 seconds, you're still at the car whatever GPS says, so the beacon decides instead.
  - **By beacon:** GPS must agree you're away, you mustn't have been in a vehicle for a minute, and BYD's position (reported after you parted) must put the car within 50 m of where your phone was when the beacon was last at full strength. If your wife drops you off and drives on, the car has left that spot by then, so nothing is sent.
  - **Last check:** BYD's live data is read just before sending, and nothing is sent unless the car reports standing still. A car that's switched on but standing (left on, or running remote A/C) is turned off and locked, which is what walking away is for. If you come back and the car starts again meanwhile, it's dropped. A failed read, or another action still running, is tried again every 30 seconds, 3 times.
  - Arming by a trusted Wi-Fi never runs it, though walking away afterwards still can.
- **It restarts by itself** after an app update, after the phone restarts, and if Android stops the app. The event line then says so, for example `Restarted after the phone restarted`.
- **It keeps trying when it can't reach the car.** If BYD has no position for the car or the network isn't up yet (common right after a restart), it tries again after 1 minute, then less often, up to every 15 minutes. Only **Stop**, a failed car command, signing in again or forgetting credentials turn it off.
- **It shows how good the reading is.** The time next to the distance is when the reading was taken, so a stale one looks stale. A reading too rough to use shows its margin, for example `1991 m ±1500`; it can't start or arm anything. Readings more than 30 seconds late are ignored too. The app asks both GPS and Google's combined location, because the combined one sometimes falls back to a rough network position while GPS works.
- **It keeps a log.** Readings (distance, margin, source, age, speed when in a vehicle, never coordinates), events, statuses, each car position read from BYD (how old it was and how far from your phone) and each new sign-in after BYD ended the session go to `Android/data/local.byd.arrival/files/readings.log`, about 40 hours in all, readable over ADB. Settings → **Save log to Downloads** saves all of it as a text file you can attach anywhere; **Share log** sends the last 600 lines.
- **It shows when readings stop.** If no location reading arrives for 10 minutes, the status says `No reading since 02:09:09` instead of the last distance.
- **It follows the car.** When you get past the re-arm distance after a start, or after being at the car, the app asks BYD where the car is, at most once a minute. If the car moved, you drove it: the app saves its new spot (the event line says `Car moved 1991 m: following it`) and waits for you to walk away from there. If it's still where it was, you walked away, and the trigger arms. Once armed and away, it stops asking, so a parked car is rarely asked. While you're moving faster than 12 km/h, it also checks every minute whether the car is moving with you (less often, up to every 15 minutes, while it keeps finding the car where it was, as on a bus; each new trip starts again at every minute). When a trip ends, after 2 minutes without moving faster than walking, one more check follows the car to where it was parked, even if checks during the drive failed. That check only follows the car: it never arms or runs the walk-away action, because sitting in traffic looks the same as having parked, because it only sees you leave after a good reading beside the car, and GPS is often too rough there. While it follows the car, the widget shows `With the car` instead of a distance.
- **Someone else may drive the car.** The app tells the difference: the car moving near you while you travel means you're driving it; the car moving while you're elsewhere means someone else is, and then only its position is updated (`Car moved 2140 m without you, 2515 m from you: position updated`). Every 15 minutes the app also asks BYD where the car is, so it keeps track while someone else has it.
- **It checks the car is really there before unlocking.** When you come within the start distance, BYD is asked where the car is first. Only if it's within the start distance plus 30 m of you do the unlock and start run; otherwise the position is updated and nothing is sent (`Not unlocking: the car is 2100 m from you`). This adds a few seconds.
- **The walk-away action runs only if you were with the car.** You must have been at its spot or travelled in it before walking away, so it never locks or turns off the car while someone else is using it.
- **It never starts the car, arms or runs the walk-away action while you're moving faster than walking (12 km/h).** Right after you drive off, BYD can still report the car at its spot, which looked like walking away; the next check, a minute later, sees the car moving with you.

Phone location is often several metres off, and worse indoors. Very short distances may not work reliably.

### Distance check and abroad mode

- **Wrong distances restart tracking.** In Israel the car is never more than 700 km away, so a reading farther than **Restart tracking beyond** (default 700 km, 100 to 5000 km) means the car's position or your phone's location was read wrong; it has happened, for example 500 km while beside the car. Israel is about 420 km long at most (Metula to Eilat), so to catch a wrong reading like that, set it to 450 km. The reading is ignored and tracking restarts as after an app update: the car's position is read from BYD again and location readings start afresh. It restarts at most every 5 minutes, and the event line says, for example, `Read 800 km from the car, which can't be right: restarting tracking`. A new distance is saved when you leave Settings and applies straight away.
- **Abroad mode turns that off.** When you travel, switch on **Abroad mode**: tracking pauses, with no auto-start, no requests to BYD, no location readings and no restarts, until you switch it off. The widget shows `Abroad mode`. Widget buttons still work.

### Trusted Wi-Fi (home, office)

Under **Arm when connected to Wi-Fi**, connect to a network and tap **Add current Wi-Fi**. Saved networks are listed above the button; tap the bin icon next to one to remove it. While monitoring is on:

- **The car is never started while you're connected to a listed network,** however close you are and however weak the signal.
- **A strong signal from a listed network arms the trigger,** as if you'd walked away. Nothing is sent to the car. A strong signal is one at or above **Strong signal from**, default −70 dBm. If you join on a weak signal and it gets stronger, for example as you walk inside, it arms then.
- **A weak signal is ignored,** for example your home Wi-Fi reaching the car. This way, sitting in the car can't arm the trigger and cause a repeat start.

So you can walk out of your home, lose the Wi-Fi, reach the car, and it starts.

To choose the value, tap **Add current Wi-Fi** inside and again at the car; each time it shows the signal where you are. Pick a value between the two. The field is saved when you leave Settings and applies straight away.

Android doesn't report every small signal change, and may report fewer while the screen is off. If you join on a weak signal and walk in without using the phone, it may only arm once you do.

### Bluetooth beacons in the car

A small beacon kept in the car is the first trigger: auto-start fires when a beacon's signal reaches its strength **or** when you're within the start distance, whichever comes first, and the other then counts as having fired too. Because the beacon is in the car, a strong signal proves the car is there, so unlike the start distance, BYD isn't asked first, which makes it quicker. Trusted Wi-Fi, the minimum time between starts and "never while driving" apply to it too.

Under Settings → **Bluetooth beacons in the car**:

1. Tap **Scan for beacons (20 s)**. **Nearby** lists what the phone hears, beacons first and strongest first, so hold your beacon next to the phone and it's at the top. Each row says what it is: an iBeacon with its major and minor, an Eddystone beacon, or another device.
2. Tap **Add** next to your beacon. It moves to **Your beacons**, which lists only the beacons you chose. You can add more than one; any of them triggers.
3. Put the beacon in the car, stand where the car should start (for example beside the driver's door) and tap **Adjust** next to it. The dialog shows the trigger strength, the signal where you stand right now (green when it's strong enough to start the car, red when not) and how the strength was set.
   - **Calibrate here (10 s)** listens for 10 seconds and moves the strength to 3 dB under the middle reading, so that spot triggers reliably. It shows what it heard: the middle reading, the range and how many readings.
   - Change the strength by hand with the slider or the **−**/**+** buttons (1 dB each). Weaker (more negative) starts the car farther away; stronger means you must be closer.
   - **Save** keeps it. Each beacon in **Your beacons** then shows its strength and how it was set, for example "Calibrated 18 Nov 14:02: middle reading −60 dBm, range −66 to −55 dBm, 25 readings" or "Set by hand 18 Nov 14:05". Until then a beacon uses −70 dBm.
4. Choose how often to **Listen for your beacons**: nonstop (the default; notices you within a second or two) or every 10, 15, 20, 30 or 60 seconds, listening for 5 seconds each time to save battery. The car can then start up to that many seconds after you arrive. It applies straight away.
5. Tap **Start monitoring**. Monitoring uses GPS and your beacons together.

How it behaves:

- An iBeacon is recognised by its UUID, major and minor, because its address may change; any other beacon by its address. Use a beacon that broadcasts all the time; tracker tags change their address, and a car's own Bluetooth usually can't be found by this scan.
- It fires after three strong readings in a row. It's ready again after the beacon was out of range (more than 10 dB under its strength, or not heard) for 60 seconds, or for two listening rounds when they're more than 30 seconds apart, and GPS agrees you're away from the car (a reading from the last minute, clear of the start distance, and not in a vehicle for a minute). Arming by distance doesn't arm the beacon: it arms only when it's out of range itself, so a beacon still strong 25 m away can't start the car again right after you walked away. A weak signal while you sit in the car or drive can't start it again either.
- When the beacon goes out of range after you were at the car, BYD is asked where the car is. That position becomes the car's spot, and the walk-away action may run (see the auto-start rules above). If you come back to the car while BYD is being asked, nothing is sent.
- The log notes how often it listens and each beacon's strength whenever monitoring starts.
- Signal strength is only a rough guide to distance, and Bluetooth signals can be spoofed.
## Updates

The app looks for new versions at launch, about every 12 hours in the background, and when you tap Settings → **Check for updates**.

- At launch, a dialog offers the update.
- In the background, you get one notification per new version.

Tapping either one downloads the APK in your browser. Open the file to install it. Your sign-in and settings are kept. Settings also shows your current version.

## Security and privacy

- **Your credentials stay on the phone, encrypted.** The email, password and PIN are encrypted with a key held in Android Keystore, and are excluded from Android backups.
- **They can be used while the phone is locked.** Auto-start needs this. It also means anyone who can reach your widget can control your car without entering a PIN. Keep a screen lock on your phone.
- **Auto-start can act without you.** Anyone carrying your unlocked or locked phone near the car can trigger it, and it unlocks the doors. GPS and Bluetooth signals can be spoofed.
- **The network traffic is limited.** The app connects only to BYD's server for your region, to GitHub (for the version check and downloads) and, when Start car gets no cabin reading from the car, to Open-Meteo for the outside temperature. Open-Meteo gets the car's position rounded to about 1 km, nothing else. There are no analytics or ads, and your phone's location is only used on the phone to work out the distance.
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
