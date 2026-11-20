# Rawr 17U changelog

Fork of [Rawr](https://github.com/adityawarmanfw/rawr) for the Xiaomi 17 Ultra.
Releases follow Ed's version: `0.5.1-17u.N`. Test work lands on `yuv-probe`
first and moves to `xiaomi-17u` once verified.

## Unreleased (`yuv-probe`, test builds)

Goal of this round: 4K60 10-bit recording without dropped frames on the 17U.

### Video pipeline

- **17U fast path (RAW to YUV in one GPU pass).** Demosaic, lens shading,
  white balance, the render look and the YUV conversion run in a single
  compute dispatch that writes the encoder buffer directly (P010 for 10-bit,
  NV12 for 8-bit). The render look is baked each frame into a 65³ LUT.
  - Measured on the 17U at 4K60 10-bit: 0 dropped frames over 3 minutes with
    the GPU at ~342 MHz; phone power ~4-5 W (earlier builds: ~8-12 W).
  - Used only when the recording needs nothing the fast path doesn't do:
    YUV output, highlight recovery off, no denoise, no false-colour or
    defringe correction, no DCP profile, no distortion correction, no
    mirror, quad demosaic at sensor scale 1:1. Otherwise the previous path
    runs (no change in output, higher GPU load).
  - `debug.rawr.video.fast=0` turns it off.
- **Encoder feed.** Finished frames are handed to the encoder before
  waiting on the GPU, and the encoder keeps more buffers in reserve
  (16 writer images, 4 ready). This removed the systematic "no buffer"
  drops on a warm phone.
- Encoder input is P010/NV12 written by the GPU (ImageWriter), no platform
  RGBA conversion.
- Viewfinder refreshes every other frame while recording above 30 fps
  (the recorded video keeps every frame).
- On-screen monitor written at half resolution while recording.
- Scopes recompute at most every 300 ms per frame slot while recording
  (monitor stage ~3.0 ms to ~1.1 ms).
- Idle viewfinder runs at the recording fps above 30 so the sensor mode
  doesn't switch at record start.
- Hardware trilinear render LUT by default on Adreno 840.

### 60 fps sensor modes (blank rows)

- The 17U's 60 fps modes deliver 3072 rows of which only 2560 hold image.
  New per-lens setting `image.cropEdgesHighFps`; the 17U profile drops the
  512 blank bottom rows on 0.6x, 1x, 3.2x and 4.3x. Viewfinder, metering
  and recording use the same area, so there's no blank strip and no
  reframe at record start.
- **Open Gate above 30 fps records 4096x2560** (was 4096x3072, which the
  encoder refused). Checked: HEVC Main 10, level 6, 60 fps.
  - The 60 fps sensor mode sees about 17% less scene on the short side
    than 30 fps; the long side is the same. This is the sensor mode, not
    software.
  - The viewfinder box is full width at 60 fps, so switching 30 to 60 fps
    looks like a ~1.2x zoom. The recording matches what the viewfinder
    shows.

### Recording UI

- **Clean recording view:** while recording, the top bar, lens and mode
  selectors, look strip and exposure row fade out. A swipe in any
  direction on the viewfinder shows them for 3 s; a tap still focuses.
  While they're shown, swipes work as before (quick panel).
- Scopes keep their rounded corners while recording (the scope was
  letterboxed to the video aspect and the black bars hid the corners).
- **Do not disturb while recording** (Settings › Video Encoder › While
  recording, off by default). Turns on Do Not Disturb with the user's
  priority rules at record start and restores the previous mode at stop.
  Left alone if Do Not Disturb is already on or changed meanwhile; undone
  on the next launch if the app closed mid-recording. Needs Do Not Disturb
  access (asked the first time).
- Timing row hides SHORT/GPU/LAT/GOP while recording.

### Fixes

- **Tap-to-focus in the bottom third of the viewfinder.** The exposure /
  look strip swallowed taps across its whole width; now only its buttons
  take taps and the empty space focuses.

### Known issues

- One frame can still drop in the first second of a recording.
- The app's main thread uses ~24% of a core while recording.

### Build

- CI "Debug build" workflow: `variant` (debug / benchmark) and
  `skip_tests` inputs; the benchmark variant installs as
  `x17u.rawr.camera.benchmark` ("Rawr 17U Benchmark") beside the debug
  app. Shader SPIR-V uploaded as an artifact.

## 0.5.1-17u.2

- Photo: aspect ratio crop of the JPEG (the DNG stays full), picker in the
  top bar; plane-aware gain map edge crop.
- RAW import cache: 32 entries; a full cache is transient.

## 0.5.1-17u.1

- First signed release of the fork.
