# Notes for coding agents (Claude Code, Codex, Gemini CLI, OpenCode)

This is a fork of [Free My TV](https://github.com/simbasjungles/free-my-tv) tuned for the **onn 4K Streaming Box**
(Google TV 14). It's a plain Android app in Java, built without Gradle by `build.sh`. Read `README.md` first.

The person you're helping may not be technical. Keep it short and friendly, say what you're about to do on their TV
before you do it, and let them do the taps on the TV.

## Rules

- **The TMDB key is theirs and secret.** It goes only in `config.properties`, which they fill in themselves. Never
  print it and never commit it. The same goes for `*.keystore` and `app/assets/search-app.json`.
- Install with `adb push` + `adb shell pm install -r`. On Android 14, plain `adb install` can hang while Play Protect
  asks something on the TV.
- **Never send key presses or taps without first checking the foreground app.** Use
  `dumpsys activity activities | grep topResumedActivity`. A blind sequence once typed into another app's phone-login
  screen.
- Before the switch to the new home screen, check `adb shell dumpsys wifi | grep -o 'cname=[^ ]*'`. Any network owned
  by `com.google.android.tungsten.setupwraith` gets deleted when that app is turned off. Saved networks out of range
  can be removed with `cmd wifi forget-network <id>`, but only with their OK.
- Turning off setupwraith also takes the Chromecast credentials with it, so tell them about the Cast button before they
  switch.
- **Never uninstall or clear system apps.** The app only uses `pm disable-user`, so *Original* brings everything back.
- Check every visual change with a screenshot from the TV: `adb exec-out screencap -p > shot.png`.

## Code map (what this fork added)

- `HomeView`: Apple TV mode (`apple()`, `drawAppleHero`). The full-screen photo comes from `Backdrop.full`. The hero
  rotates every 15 s, skips photos that aren't ready, and only draws a title once its photo is on screen
  (`shownPick`). The flat tiles are in `TILE_STYLE`, the Wi-Fi pill is `drawNetworkPill`, and the top buttons include
  `NETWORK` and `CLEAN`.
- `Recs`: the feed (`refreshFeedIfStale`). Its mode is `country` (`discover` with `watch_region` and `now_playing`)
  or `taste` (genre weights and the liked titles from `TasteActivity`).
- `CountryActivity` and `TasteActivity`: the two pickers in the drawer (↓).
- `AppSearch`: "Watch in your app" (taps driven by `search-app.json`) and "Free up memory".
- `SettingsActivity`: the light, full-width settings menu.

## Adding the onn TV Stick 2K (coming soon)

Read first and change after:

```bash
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME
```

The stick has less RAM. Check the `Backdrop` sizes and the image cache cap (`images.size() > 14` in `Recs`) before
promising 60 fps.
