# Rawr 17U changelog

Fork of [Rawr](https://github.com/adityawarmanfw/rawr) for the Xiaomi 17 Ultra.
Releases follow Ed's version: `0.5.1-17u.N`. Test work lands on `yuv-probe`
first and moves to `xiaomi-17u` once verified.

## 0.5.1-17u.3

Goal of this round: 4K60 10-bit recording without dropped frames on the 17U.

Test conditions for the figures below: Xiaomi 17 Ultra, HEVC at 120 Mbps,
viewfinder quality High, 10-bit unless noted.

### How the GPU work was found

- The video shaders were compiled to SPIR-V by CI (artifact
  `shaders-spv-<sha>`) and analysed with Qualcomm's Adreno Offline
  Compiler (`aoc -arch=a840 -api=Vulkan -dump=stats_perf`). It showed the
  tonemap uber-shader at ~12k instructions with almost no fp16, and the
  quad demosaic at 25% occupancy.
- That is why the fast path replaces the per-pixel tonemap with a 65³ LUT
  baked once per frame and fuses demosaic, colour and YUV output into one
  dispatch. The demosaic occupancy is not addressed yet. AOC is an
  analysis tool only: nothing from Qualcomm ships in the app.

### Video pipeline

- **17U fast path (RAW to YUV in one GPU pass).** Demosaic, lens shading,
  white balance, the render look and the YUV conversion run in a single
  compute dispatch that writes the encoder buffer directly (P010 for 10-bit,
  NV12 for 8-bit). The render look is baked each frame into a 65³ LUT.
  - Measured on the 17U at 4K60 10-bit: 0 dropped frames over 3 minutes with
    the GPU at ~342 MHz; phone power ~4-5 W (earlier builds: ~8-12 W).
  - 4K60 8-bit (NV12) also measured: no encoder buffer drops over ~3 min.
  - Rechecked after this round's changes: 4K60 10-bit, ~3.5 min, ~12,960
    frames to the encoder, none missing.
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
- **Open Gate above 30 fps records 3408x2560 (4:3)** (was 4096x3072,
  which the encoder refused). The 60 fps sensor mode only has 2560 image
  rows, so the frame keeps the 30 fps 4:3 aspect with a centred crop. The
  viewfinder keeps the same 4:3 box; switching 30 to
  60 fps looks like a ~1.2x zoom, and the recording matches what the
  viewfinder shows. At 30 fps Open Gate stays 4096x3072.

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

### From upstream Rawr (Ed): #129 to #133

- #129 multiframe follows the lens photo remosaic (Detail on quad lenses,
  High elsewhere); full-size quad remosaic runs on the GPU in strips.
- #130 Rexturation; #131 and #132 Rexture fixes around moving subjects
  (blotches, grain patches, moving water).
- #133 Bayer reduced output.
- Merged by hand into the 17U code: the 200 MP fast remosaic (single
  stills and bursts on 4x4 blocks) and the 200 MP JPEG from the blocks
  stay; the zero-copy reduced burst stays for 4x4 blocks, Bayer reduced
  frames take the upload path.
- Checked on the 17U after the merge: 50 MP singles and 17-frame
  multiframe on 1x and 3.2x (DNG and JPEG); 200 MP singles on the tele
  (fast remosaic ~0.8-0.9 s, JPEG ~8.5 s after the press); 4K60 10-bit
  recording with no encoder drops.

### Auxiliary sensors (PR #1 by the-marox)

- **Light meter** (Settings › Display › Light meter, off by default): lux,
  incident EV, colour temperature, mains flicker and laser AF distance from
  the sensors beside the rear cameras, with a warning when the shutter
  speed will band. Values checked on the 17U against another app. Hidden
  in the clean recording view, like the other viewfinder controls. Sits
  at the top centre as the phone is held, in portrait and landscape (in
  landscape it used to spill over the top bar).
- **Antibanding** in Auto follows the phone's flicker sensor when it has
  one.
- **Gyroflow gyro log** (Settings › Video Encoder › Motion data, off by
  default): gyroscope and accelerometer in a .gcsv named like the video
  (the 17U delivers ~238 Hz). Videos in DCIM get it in
  Download/RAWR/Gyroflow; in a picked folder it sits beside the video.
  Not tested in Gyroflow yet: the file format, timing and units were
  checked, the stabilisation itself was not.
- Sensor readings also go into the photo/video metadata (EXIF/XMP).

### Removed for now

- **120 fps** is out of the fps button until the video pipeline records it
  without drops. A saved 120 falls back to 30.

### Fixes

- **Tap-to-focus in the bottom third of the viewfinder.** The exposure /
  look strip swallowed taps across its whole width; now only its buttons
  take taps and the empty space focuses.
- **Scopes ignore the blank rows of the 60 fps modes.** The idle
  waveform/vectorscope sampled the whole frame, padding included, so in
  Open Gate at 60 fps the waveform showed an empty band. They now sample
  only the image area (bottom/right padding).

### Known issues

- One frame can still drop in the first second of a recording; one
  mid-recording drop was seen once in Open Gate at 60 fps.
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
