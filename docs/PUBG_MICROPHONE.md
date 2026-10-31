# PUBG microphone assistance: engineering and validation

## Status and provenance

Experimental release with real-device Android 12 / MIUI 14 microphone-sharing evidence. The user
confirmed game audio and their own voice together in the recording, with the
teammate hearing them in PUBG. Full routing, synchronization and performance
acceptance remains pending.
Checked 2026-10-31.
Base: upstream `v0.8.1`, commit `806a555b52187253e3f0a2b921bf5edfff4e9f5b`.
User confirmed installed v0.8.1; ADB independently confirmed versionCode 12.
GitHub REST resolves both supplied owner names to **pixlbuilds/rec**. Latest public
release remains v0.8.1; main declares 0.10.1. No broad main upgrade was taken.

## Root cause boundaries and research

A. Android normally permits only one ordinary app to receive microphone PCM.
The losing AudioRecord can remain initialized/recording while its samples are
silenced. Foreground service alone does not waive this rule. Privacy-sensitive
sources and communication-mode ownership affect priority. Restarting repeatedly
cannot override policy. See [Android sharing rules](https://developer.android.com/media/platform/sharing-audio-input)
and [AOSP concurrency](https://source.android.com/docs/core/audio/concurrent).

B. Playback capture is separate. Only eligible MEDIA, GAME and UNKNOWN playback
with permissive capture policy can enter REC. If PUBG switches to communication
usage or disallows capture, accessibility assistance cannot restore that output.
Teammates' voices are not guaranteed. See [playback capture](https://developer.android.com/media/platform/av-capture)
and [configuration API](https://developer.android.com/reference/android/media/AudioPlaybackCaptureConfiguration).

C. REC v0.8.1 creates playback and mic AudioRecords, tries VOICE_RECOGNITION then
MIC during initialization, reads them independently, and mixes stereo PCM into
one AAC input. Missing buffers become silence to preserve timing. That existing
behavior is not proof that either stream contains sound. Mixer sample clock and
AudioEncoder timestamp clamping are unchanged. Duplicate prepare/start could
allocate another recorder/job; this patch makes those calls idempotent and makes
reader loops cancellation-aware. Route/silence changes do not restart projection,
video, AAC or the microphone. Framework-managed unsilencing is observed directly.

### Why accessibility plus VOICE_RECOGNITION

The [Android 12 AudioPolicyService source](https://android.googlesource.com/platform/frameworks/av/+/refs/tags/android-12.0.0_r1/services/audiopolicy/service/AudioPolicyService.cpp)
checks accessibility **UID**, not which Java Service constructed AudioRecord.
Its accessibility branch permits VOICE_RECOGNITION when `isA11yOnTop` holds; that
helper includes process states TOP through BOUND_FOREGROUND_SERVICE. Other branches
remain constrained by sensitive capture and communication ownership. The
[AccessibilityManagerService source](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-12.0.0_r1/services/accessibility/java/com/android/server/accessibility/AccessibilityManagerService.java)
passes bound-service UIDs to AudioManager. Therefore this fork keeps AudioRecord
ownership in its existing manager, in the same UID/process as a genuinely bound
accessibility service. No visible overlay is added. OEM equivalence is a testable
hypothesis, not guaranteed by the general documentation's "UI on top" wording.

[RootEncoder #1848](https://github.com/pedroSG94/RootEncoder/issues/1848) and its
[wiki](https://github.com/pedroSG94/RootEncoder/wiki/Recording-audio-while-other-app-use-the-microphone)
report accessibility-assisted MIC capture. The wiki's permission example returns
true when ACCESSIBILITY_ENABLED is zero and does not verify the particular service.
Its broad event subscriptions/window access are unnecessary for UID registration.
None of that sample code was copied. This fork observes its actual service connection
and declares no event types, window access, gestures or overlays.

[#2067](https://github.com/pedroSG94/RootEncoder/issues/2067) reports DEFAULT working
on a different rooted Xiaomi Android 14 device. Its claim that an uninitialized Java local int
implicitly becomes zero is invalid Java reasoning. RootEncoder
[MixAudioSource](https://github.com/pedroSG94/RootEncoder/blob/f1bbda7ca6fb92bb599206fda2b97745ac11b7f2/encoder/src/main/java/com/pedro/encoder/input/sources/audio/MixAudioSource.kt)
comments report DEFAULT/MIC silence on Android 12/14. These reports do not isolate
identical policy/root/accessibility conditions. DEFAULT is not promoted to a fix;
VOICE_COMMUNICATION/CAMCORDER are rejected because they can take the game microphone.
No proprietary Glip code or decompiled control flow was reused.

[CaptureCap](https://github.com/yepgoryo/CaptureCap/tree/ee98832d3ae60aa02628a3fc8c80039537f9e739)
uses standard playback capture plus MIC and also contains Shizuku options; those
options are outside this task. No code/dependency was imported. Xiaomi's public
screen-recorder help does not establish concurrent capture for Android 12 / MIUI 14.
Glip behavior reports are comparison hypotheses, not a transferable guarantee.

## Implementation and privacy

The option is off by default, preserved by preferences and JSON import/export;
old profiles default off. Turning it off disables this service. Permission must
be enabled explicitly in Android accessibility settings. Service disconnects are
visible in settings and produce a recording-time warning. Process death clears
connection state. No worker, overlay, external audio framework or native library
is added. When permission is missing, normal source selection remains unchanged.
When connected, concurrent mode retains VOICE_RECOGNITION without falling back to
MIC, which would lose the documented exception.

[AudioRecord callbacks](https://developer.android.com/reference/android/media/AudioRecord.html)
and [isClientSilenced](https://developer.android.com/reference/android/media/AudioRecordingConfiguration)
log source, recording state, policy silence, numeric route type/id and mode changes.
A five-second summary reuses existing independent RMS computations and records
buffer availability plus sample-based AAC input PTS. No raw audio, screen contents,
conversations, device addresses or third-party identifiers enter these diagnostics.
Low RMS is never used to declare a conflict or restart anything. Callback listeners
are removed on stop. Existing audio/video encoders and mixing math are unchanged.
Actual encoded timestamps and content still require an MP4 recording test.

## Device validation matrix

Test environment: Android 12/API31, MIUI 14. Exact hardware model is intentionally omitted.

Baseline reproduced on the actual phone: original v0.8.1 VOICE_RECOGNITION input
changed from `silenced:false` to `silenced:true` when PUBG enabled its
VOICE_COMMUNICATION input. PUBG remained `silenced:false`. This directly establishes
policy denial in the original app, independently of whether the lobby was audible.
The baseline MP4 has HEVC 1600x720 and one AAC 48 kHz stereo track; 2,934 video frames
over 71.166 seconds (about 41.23 average FPS) include settings/lobby transitions.
This is not an equivalent gameplay benchmark or evidence of capture frame drops.

Build verification: `assembleDebug assembleRelease test lint --continue` PASS.
90 tests per variant, zero failures/errors/skips; lint zero errors, 121 warnings.
The first new round-trip fixture failed because upstream import aligns 1080 to
1088. It was corrected to the actual requested aligned 1600x720 profile; no existing
test or assertion was removed. Final release signature v2 verifies, minSdk29,
targetSdk35, ARM64 included. Release installed and launched on Android 12 / MIUI 14; installed
APK SHA-256 exactly matches the delivered artifact. Original REC remains installed.

| Case | Scenario | Status |
|---|---|---|
| A | PUBG mic off, Both, including audible game output | NOT TESTED |
| B | PUBG mic on before REC, Both, including audible game output | NOT TESTED |
| C | Off to on during Both, full audio acceptance | NOT TESTED |
| D | On to off during Both, full audio acceptance | NOT TESTED |
| E | Repeated transitions | NOT TESTED |
| F | PUBG mic on, Internal only | NOT TESTED |
| G | PUBG mic on, Mic only | NOT TESTED |
| Routes | Speaker / wired headset / Bluetooth | NOT TESTED |
| Performance | FPS, 1% low, drops, temperature, CPU, memory comparison | NOT TESTED |

### Verified subset from the user's manual test

| Check | Status | Evidence |
|---|---|---|
| Player voice is present in saved recording | PASS | User listened and confirmed |
| Both mode contains game audio and player voice together | PASS | User explicitly confirmed both are audible in the last recording |
| PUBG teammate hears player during recording | PASS | User explicitly confirmed |
| Concurrent REC and PUBG inputs not silenced | PASS | Actual Android recording event history |
| Accessibility service connected without an overlay | PASS | Android bound service plus REC status UI |
| Signed release installs beside official REC | PASS | Package query and installed APK hash |
| HEVC 1600x720 + one AAC-LC 48kHz stereo track | PASS | ffprobe of fork-owned user recording |
| AAC packet continuity and monotonic timestamps | PASS | 4026 packets, max gap 0.021334 s, zero PTS/DTS reversals |

At 12:06:01 local time, Android recorded REMOTE_SUBMIX and VOICE_RECOGNITION
for the fork, followed by PUBG VOICE_COMMUNICATION, all not silenced. PUBG later
switched to MIC at 12:06:20; REC remained active through 12:07:27. Earlier fork
recording also spans a PUBG voice-chat start/stop. This verifies policy coexistence
for the observed transitions, not every routing or repeated-toggle scenario.

The examined 86.619-second file has 2668 video frames, average encoded rate about
31.11 FPS, maximum video packet gap 0.117033 seconds. Video begins at 0.8528 s and
audio at 0 s. Baseline starts differed too. These mixed lobby/menu/game samples
are not comparable gameplay benchmarks; no 60-FPS, frame-drop, 1% low or perceptual
A/V synchronization pass is claimed. The user audibly verified game audio together
with microphone voice; the mixed track has not been independently separated.
Generic logcat tags were unavailable
in the retrieved device buffer; system recording history provided policy evidence.

APK: `REC-hcsolakoglu-PUBG-Mic-Android12.apk`, 21,701,908 bytes.
SHA-256: `f0ded47fe820285876c5507c9a95651b031e976655f0302f194d5967a3e1a767`.
Signer certificate SHA-256:
`4328deb74464c3fe270f350bc56c9dfcefbc6eb63082bf22f99eedf4df87a38a`.
An independent RSA-3072 key signs this fork; key and password remain outside Git.

## Diff scope and upstream submission

### Compatibility scope

The minimum supported platform remains Android 10/API29, matching the v0.8.1 base.
No device-model allowlist or hardware-model branch was introduced. API31 audio-mode
callbacks are guarded by a runtime SDK check; other added capture callbacks use
APIs available at the existing minimum. Normal source selection is retained when
the optional assistance mode is disabled or disconnected. These properties preserve
the intended compatibility floor; they do not prove behavior on every Android
release or vendor implementation. Concurrent sharing is verified only for the
reported Android 12 / MIUI 14 setup. The existing unit suite passes, but all unrelated
features and every supported OS/device combination have not been exercised end to end.

VideoEncoder, AudioEncoder, ScreenRecorderEngine, PcmAudioMixer, RecordingService,
streaming, screenshots, replay and vault implementation have zero changes from
v0.8.1. Remaining changes are audio lifecycle/diagnostics, opt-in service/config/UI,
serialization tests, fork identity/signing, and required release documentation.

For an upstream PR, prepare a separate branch based on their requested target,
port only the audio/config/permission changes and tests, and omit fork application
ID, branding, signing and release metadata. Include reproducible device evidence,
the limited acceptance matrix and the rationale for zero accessibility events.
Do not represent this single user test as universal Android/MIUI support. No
upstream PR has been opened. Multi-track audio is a separate feature proposal:
three AAC tracks (game, microphone, mix) require shared timestamp handling,
separate encoder/resource cleanup and player/editor compatibility validation.

Run with intentional game sound and spoken test phrases, not a silent lobby.
Check both the saved recording and whether a teammate actually hears the player.
Record transition timestamps; compare original v0.8.1 using identical settings.
A real device being connected is not itself a successful microphone-sharing test.

## Install and test

Install the independently signed APK beside `pixl.rec`; no original data is removed.
Use Settings > Audio > Both, enable Concurrent microphone, then open accessibility
settings and enable **REC hcsolakoglu / Concurrent microphone (experimental)**.
Return to REC and confirm "Assistance connected". Keep floating overlays disabled.
Apply the existing 1600x720 landscape, hardware HEVC, 60 FPS, 6 Mbps VBR,
1-second keyframes, AAC-LC 48 kHz stereo/256 kbps profile. No video setting was changed
by this patch. If Android reports silencing, the recording must not be treated as
successful voice capture. Disable the option to return to normal behavior.

Build with JDK17 and Android SDK36: `./gradlew assembleDebug assembleRelease test lint`.
Release signing uses REC_SIGNING_KEYSTORE and REC_SIGNING_PASSWORD environment
variables and alias rec-fork; without them the release is unsigned. Never commit
keys/passwords. GPL-3.0 and upstream attribution remain intact.
