# 🔥 Fireplace

Your phone as a hand warmer — fully offline.
No internet. No accounts. No ads.

Android 8.0+ · Kotlin · tuned for Nothing Phone (3a) Pro

## How it works

The phone has no heater, so the app makes heat by running short bursts of CPU work (a duty cycle)
and keeps the temperature in check with a safety loop that runs every second.

| Part | What it does |
|---|---|
| `HeatService` | Foreground service: runs the CPU workers, reads temperatures, stops itself when unsafe |
| `ThermalGovernor` | Raises the duty cycle slowly, lowers it fast; also backs off on Android's thermal status and thermal-headroom forecast |
| `HeatLevel` | Low / Medium / High presets (target battery temperature + max duty) and the safety limits |
| `FireView` | Animated fireplace that follows the current power |
| `MainActivity` | Heat level, timer, light / put out |

## The fire goes out by itself when

- battery temperature reaches **43 °C** (`HeatLevel.HARD_CEILING_C`)
- Android reports **moderate thermal throttling** or worse
- a **charger is connected** (charging + load = extra battery heat)
- battery is **20 % or lower**
- the battery temperature can't be read
- the timer ends

## Build

Open in Android Studio (JDK 17+), let Gradle sync, run on a real phone (emulators don't heat).

## Tuning

Everything you'll want to tweak is in `HeatLevel.kt`: target temperatures, max power per level,
hard ceiling, minimum battery. Start low and raise them only after watching the temperature readout.

## Good to know

- Battery temperature lags behind the chip, so the app deliberately targets low numbers.
- Expect gentle warmth around the upper-middle of the back, and noticeable battery drain.
- Don't use it under a pillow, in a thick case, or in direct sun.
