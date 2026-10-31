# Optional separate game and microphone audio

Branch `feat/separate-game-mic-tracks` extends `fix/pubg-concurrent-microphone` at
`1a648c2f959690ab62fdf64b5644580547ee1f98`. It remains based on upstream v0.8.1,
not upstream main. GPL-3.0 and existing attribution are unchanged.

## Use

Config → Audio → Both → **Separate game and microphone tracks**. This is off by
default. Each local MP4 contains one original video track, then game AAC and
microphone AAC in that order. There is no third mixed audio track. Import both
audio tracks into an editor, process microphone separately, then mix on export.
Many players play only one audio track; that does not mean the other is absent.

Game/mixed AAC and separate microphone AAC each offer 64, 96, 128, 192, 256 and
320 kbps. Existing default 256 kbps, 48 kHz and stereo are preserved. For example,
192 kbps game + 96 kbps microphone totals 288 kbps: approximately 2.16 MB/min of
audio versus 1.92 MB/min for one 256 kbps track (decimal MB, excluding overhead).
These are size calculations, not CPU measurements. Lower bitrate can reduce
quality; it does not remove the cost of running two encoders.

Streaming, including its local archive, retains one mixed AAC track. Internal,
Mic and Mute retain their existing behavior. Old JSON profiles default to mixed
audio; new settings survive import/export. The concurrent microphone permission
is independent and remains explicit opt-in. This feature never sends PCM to
PUBG, changes audio mode/routing, or creates a new microphone recorder. Speaker
sound can still reach any microphone acoustically; this is not digital routing.

## Implementation and cost

- Reuses existing two AudioRecords, reader jobs, bounded queues and sample clock.
- At unity gain, passes borrowed PCM arrays synchronously to each AAC encoder;
  buffers return to the pool only after both consumers return. No PCM mix, extra
  per-chunk arrays, or additional capture jobs. Non-unity gains use reusable
  scratch buffers and REC's existing soft limiter.
- One extra AAC encoder/drain coroutine and AAC track when enabled. Each codec
  copies its own PCM input as required by the existing MediaCodec path. No claim
  of end-to-end zero-copy audio or zero overhead.
- Per-chunk routing at unity gain is O(1); gain processing is O(n) in PCM samples;
  AAC encoding remains work proportional to audio duration. Memory stays bounded,
  independent of recording duration. Default-off creates no second codec.
- VideoEncoder.kt and the MediaProjection → VirtualDisplay → encoder Surface
  path are unchanged. No forced overlays, resolution/FPS/codec changes, additional
  permissions, dependencies, workers or native libraries.
- Waits for all selected output formats; registers tracks in deterministic order,
  including out-of-order format callbacks and queued startup samples. Both PCM
  tracks receive the same sample-based timestamp through silence and pause/resume.
- Stops readers before EOS, allows both AAC encoders to drain within the existing
  bounded shutdown wait, then closes the muxer. Initialization failure releases
  already-created resources and reports an error instead of silently substituting
  a mixed track. AudioEncoder tail-draining and engine error reporting fixes also
  apply to the normal path.

## Platform evidence

Android's [MediaMuxer documentation](https://developer.android.com/reference/android/media/MediaMuxer)
supports multiple audio tracks in MP4 from API 26; REC still requires API 29.
An extra encoder can fail under resource pressure; Android's
[codec instance limit](https://developer.android.com/reference/android/media/MediaCodecInfo.CodecCapabilities#getMaxSupportedInstances())
is only an upper-bound hint. Actual dual-codec startup and output must be tested.
No guarantee is made across all Android versions or OEM audio policies.

## Validation

Validation on 2026-10-31:

| Check | Result |
| --- | --- |
| Debug/release APK build, full tests and lint | PASS: 104 tests per variant; lint 0 errors, 121 existing warnings |
| PCM isolation, stereo identity, zero-copy handoff at unity gain | PASS: deterministic fixtures and identity assertions |
| Independent gain/soft limiting, silent microphone and recovery | PASS: fixtures, 1000 repeated buffer reuse cycles |
| All six format arrival orders, stable track mapping and startup readiness | PASS: unit tests |
| Normal modes, old/new JSON profiles, bitrate bounds and storage accounting | PASS: unit tests |
| Pause/resume idempotence, EOS for both encoders, repeated release, failed-start muxer cleanup | PASS: controlled Android mocks |
| APK signature/package/minSDK | PASS: same independent RSA key, v2 signature, pixl.rec.hcsolakoglu, minSDK 29 |
| Installation on Android 12 / MIUI 14 | PASS: final signed APK updated the existing fork |
| Two-track PUBG recording on Android 12 / MIUI 14 | PASS: user-provided final-build MP4 has game and microphone audio in distinct tracks |
| Real AAC timestamps, counts and file decoding | PASS: 556 packets per audio track, identical PTS lists, monotonic timestamps, full decode exit 0 |
| Selected bitrates and video codec/dimensions | PASS: both selected 192 kbps AAC-LC/48 kHz/stereo; HEVC 1600×720 |
| Settings UI | PASS: controls inspected on device; both bitrate selectors fit and selection persists |
| Gameplay input latency, FPS/1% lows, CPU/memory/thermal A/B | NOT TESTED; no performance guarantee |
| Bluetooth/headset route changes, repeated PUBG mic transitions | NOT TESTED for this two-track build |

The first test run exposed an incorrect new fixture expectation of -32768 for
REC's symmetric soft limiter. Source inspection confirmed -32767; corrected the
fixture to preserve existing limiting behavior. Final full run passed without
disabling checks, modifying existing tests, or adding lint suppressions.

The final-build PUBG sample is 12.5077 seconds. Each audio track spans 11.861333
seconds with the same 1,138,688 decoded PCM samples (counting both channels).
Game RMS is -34.10 dBFS and microphone RMS is -29.19 dBFS; their decoded content
differs. Maximum audio packet gap is 21.334 ms, consistent with AAC frames at
48 kHz. Android recording history reports both REC inputs as not silenced.
An earlier launch-screen sample contained silent game PCM and active microphone
PCM; it was not accepted as a game-audio pass. The second sample supplies actual
PUBG sounds. Private recordings and extracted audio are not uploaded to GitHub.

Measured encoded video rate in this short menu sample is 38.40 FPS, with a maximum
frame timestamp gap of 464.344 ms. These are observed output statistics, not a
60 FPS gameplay pass or an overhead comparison. Video begins at 0.7112 seconds,
audio at 0; matching audio-track timestamps alone does not prove perceptual A/V
sync. No comparable gameplay frame-time/1% low/thermal benchmark has been performed.

The previous single-track PUBG recording and simultaneous live team voice chat
were user-confirmed on Android 12 / MIUI 14. Teammate reception and repeated
voice-chat toggles were not repeated in the new two-track sample. The underlying
microphone capture policy and accessibility service remain byte-for-byte unchanged.
