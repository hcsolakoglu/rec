# Keep REC running on MIUI (checklist)

MIUI can kill REC while a game runs. Android then marks REC's concurrent
microphone accessibility service as crashed, keeps it enabled in Settings and
does not bind it again, so the microphone track goes silent. Kills seen on
Android 12 / MIUI 14, read from Android's exit records:

| Exit description | Cause | Prevented by |
| --- | --- | --- |
| `SwipeUpClean` | REC's card swiped away or "clear all" in recent apps | Lock in recents |
| `GameClean` | Game Turbo memory cleanup when a game starts | Game Turbo memory booster exceptions |
| `PeriodicCleaner(...pressure)` | Background cleanup under memory pressure | Autostart, battery exemptions |

Do the phone steps once; the ADB steps are optional and persist across reboots.
Change nothing while a match is running.

## On the phone

Menu names differ slightly between MIUI versions; Turkish names in parentheses.

- [ ] **Lock REC in recent apps.** Open recents, long-press REC's card, tap the
      lock. Stops `SwipeUpClean`.
- [ ] **Game Turbo memory booster exceptions.** Security (Güvenlik) → Game Turbo
      → gear icon → Game speed booster (Oyun hızlandırıcı) → Memory booster
      exceptions (Bellek hızlandırıcı istisnaları) → add REC. Stops `GameClean`.
- [ ] **Autostart.** Settings → Apps → Manage apps → REC → Autostart on.
- [ ] **Battery saver.** Settings → Apps → Manage apps → REC → Battery saver →
      No restrictions (Kısıtlama yok).
- [ ] **Concurrent microphone entry on.** Settings → Accessibility (Erişilebilirlik)
      → Downloaded apps → "Concurrent microphone (experimental)" on.

If REC says the system stopped concurrent microphone assistance, turn that
Accessibility entry off and on before the next match.

## From a PC over ADB (optional)

Covers Autostart and Android's battery optimization; MIUI's own Battery saver
setting still needs the phone step. Run when no game is open.

```sh
P=pixl.rec.hcsolakoglu
adb shell cmd appops set $P 10008 allow                 # MIUI Autostart
adb shell cmd deviceidle whitelist +$P                  # battery optimization exemption
adb shell cmd appops set $P RUN_ANY_IN_BACKGROUND allow
```

Restart a crashed service without opening Settings (same as toggling the
entry; this replaces the whole list, so include any other enabled services):

```sh
C=pixl.rec.hcsolakoglu/pixl.rec.service.ConcurrentMicrophoneService
adb shell settings delete secure enabled_accessibility_services
adb shell settings put secure enabled_accessibility_services $C
adb shell settings put secure accessibility_enabled 1
```

Game Turbo exceptions and the recents lock live inside MIUI apps and have no
ADB setting; do them on the phone.

## Check

```sh
P=pixl.rec.hcsolakoglu
adb shell dumpsys accessibility | grep -E "Bound services|Crashed services"
adb shell dumpsys activity exit-info $P | grep -E "timestamp=|description="
adb shell cmd appops get $P 10008                       # expect allow
adb shell cmd deviceidle whitelist | grep $P
```

Healthy: REC's service under Bound services and Crashed services empty. A new
`SwipeUpClean` or `GameClean` exit means a phone step above is missing.
