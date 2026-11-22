# Rawr 17U changelog

Fork of [Rawr](https://github.com/adityawarmanfw/rawr) for the Xiaomi 17 Ultra.
Releases follow Ed's version: `0.5.1-17u.N`. Test work lands on `yuv-probe`
first and moves to `xiaomi-17u` once verified.

## Unreleased (`yuv-probe`, test builds)

Nothing yet.

## 0.5.1-17u.5

Ed's upstream #137 and #138, then the white balance work. Checks are on a
Xiaomi 17 Ultra.

### From upstream Rawr (Ed): #137, #138

- #137 OPPO Find X8 Ultra (PKJ110) built-in profile, by Ed. Not used on the
  17U. Its refreshed route snapshot is merged into the fork's.
- #138 optional shared debug signing key, by Ed: the Gradle part and
  docs/build.md taken as is (no effect without the key). The fork keeps
  signing debug builds with its own fixed debug.keystore, applied after
  Ed's option, and its own debug.yml (no GitHub secret needed).

### White balance

- WB sampler: WB panel > PICK NEUTRAL, then tap a white or grey area in the
  viewfinder. Rawr measures that area (about 5% of the frame) in the scene
  meter, before the render, and switches to Manual with the temperature and
  tint that make it neutral; the sliders fine-tune from there. A second
  pick refines from the current Manual values. Too dark or clipped areas are
  ignored (logcat tag RawrWbPick). Asked for by Matt in the Rawr group.
- Why: in Auto, Rawr renders with the white balance gains the camera HAL
  gives third-party apps. Checked on a 17U DNG under warm indoor light: the
  HAL neutral (R 0.567, B 0.601) was cooler than the light on a white wall
  (R 0.634, B 0.531), so the wall came out cream/yellow. Matt measured the
  same on a vivo X300 Ultra: Rawr about 100-300 K warmer than the stock app.
- How: the scene meter (image_scopes scene_exposure shaders) also sums the
  RGB inside a sample rectangle; the session turns the measured ratios into
  a Manual temp/tint request on the camera controller.
- First check on the 17U (daylight and shade, 3 picks): the pick lands
  right in camera space - the DNGs read R/G 1.04 / B/G 1.00 and 1.00 / 1.01
  on the picked wall - but the JPEGs came out magenta (a white wall turned
  lilac). Cause, in the render (also in upstream): with AWB off the colour
  matrix took its white from the HAL's neutral point, which in Manual is
  the HAL's own guess, not the requested gains. A raw the Manual gains make
  neutral then rendered with the gap between the two as a cast. Manual WB
  now takes its white from the gains, the same rule the DNG's AsShotNeutral
  already follows (color/ColorCalibration.cpp; native test added).
- Checked on the 17U with the fix (white wall, daylight, Auto then pick):
  the wall read R/G 1.07 / B/G 0.97 in the Auto JPEG and 1.00 / 0.97 after
  the pick (linear, centre of the frame); DNG 0.99 / 0.99. No magenta.
- Warm indoor light (17U, lamps, white wall): Auto JPEG R/G 1.34 / B/G 0.78
  on the picked spot (the yellow cast), after the pick 1.01 / 0.96; DNG
  1.13 / 0.90 -> 1.00 / 0.98. In mixed light the rest of the wall follows
  its own light: a part lit from elsewhere read 0.92 / 1.02 after the pick,
  so it can look slightly blue.
- Picking a cream/beige wall made the picture too cool (the pick makes the
  tapped area grey; a white shelf in the same scene went 0.79 / 1.12). The
  hint now says to tap something truly white or grey. Picking the white
  shelf instead: 1.19 / 0.79 in Auto -> 0.95 / 1.00, still felt too cold
  under warm lamps.
- So a warm pick (one that cools the picture) corrects part of the way
  (in log space), keeping some of the light's warmth like stock cameras do;
  a second pick moves closer to neutral. Cool and tint-only picks stay
  full. Checked on the 17U, same scene: shelf 1.22 / 0.78 in Auto -> 1.01 /
  0.93 at 75%; still read too cold by eye, so now 50% (halfway, in log
  space, between Auto and neutral). Checked on the 17U: the shelf went from
  1.07 / 0.88 in Auto to 1.00 / 0.99; the gains moved about 65-75% of the
  way, not 50%, because Manual keeps only temperature and tint, which can't
  hold the pick's exact red/blue gains. Left as is for now (temporary);
  exact gains are in the TODO. A pick on a napkin changed little: with the
  napkin in frame the HAL's Auto already had it near neutral (1.01 / 0.96).

## 0.5.1-17u.4

Ed's upstream #135 first, then this fork's work since 0.5.1-17u.3. Each
item says how it was checked; checks are on a Xiaomi 17 Ultra unless noted.

### From upstream Rawr (Ed): #135

- #135 Bayer bin 22 MP: the smaller reduced size ("reduced25") on Bayer
  lenses now goes through a direct 3x bin (22 MP from 200 MP) instead of
  the 2x bin then resample; colour kernels refitted. The vivo X300 Ultra
  profile (not the 17U's) also sends vivo's pro-mode vendor key on its
  lenses.
- Rawr AE: a still's gain uses the exposure target in effect when the frame
  was taken (kept per frame timestamp), not the one when the burst is
  processed, which for 200 MP can be seconds later.
- On the 17U: its reduced lenses are 4x4 (block sums, not the Bayer bin),
  so the bin changes don't touch the default profile; the AE change applies
  to every photo. Merged without conflicts on top of this fork (#129 to
  #133 recorded as already merged by hand).
- Checked on the 17U after the merge: 50 MP singles and 17-frame
  multiframe on 1x and 3.2x; single and multiframe came out at matching
  brightness. Leaving the app and shooting right after coming back gave
  the same post gain (1.0) as a shot 3 s later.

### Video

- **Gyro stabilisation** (Settings › Video Encoder › Stabilisation: Off,
  Standard, Strong; off by default). From apos-fandango's Rawr X14U fork
  (github.com/apos-fandango/rawr-xiaomi14u, commit 9bd6bac): the gyro
  integrator with its smoothed path and the timestamped shake track. The
  rest of the stabilisation below (applying it in the fast path, OIS off,
  soft edge, the sub-pixel warp with roll, Level horizon) is this fork's
  own. First version: the shake was taken out by moving the crop window
  inside the sensor's spare area in the recording fast path, so it adds no
  GPU pass. Pitch and yaw only (no roll), 4K and 1080p (Open Gate has no
  spare area), fast path only. The crop moves in 2-pixel steps with
  hysteresis, so it doesn't flicker between steps on a steady hand.
  First test: the slow wander of a handheld shot went from ~70/50 px to
  ~6-16 px over 10 s, no dropped frames.
- Stabilisation turns the lens OIS off while it runs (video mode with
  Stabilisation on); the OIS setting itself is kept. Measured on the 17U:
  with OIS on, the lens already removes most of the hand tremor (3-12 Hz)
  that the gyro still sees, so correcting it again doubled the fine jitter
  (1.6 to 3.3 px). With OIS off the gyro matches the image (correlation
  0.89, gain 0.94, no frame lag) and Standard cut the fine jitter of the
  same take by about half. Not yet better than OIS alone: work in progress.
- Smoothing: Standard 1.0 s (was 0.45 s), Strong 2.0 s (was 1.0 s). Walking
  test on the 17U, same route, separate takes (x / y, px at 4K): fine jitter
  OIS alone 1.7 / 0.7, Strong (1.0 s) 1.0 / 0.7; slow sway 0.3-1 Hz OIS
  alone 4.2 / 2.8, Strong 1.4 / 1.8. The old Standard (0.45 s) was no
  better than OIS. Standing still, OIS alone still has less fine jitter
  (it also corrects within the frame). Motion blur without OIS not measured.
- Soft edge: past 60% of the crop margin the smoothed path is pulled
  towards the camera progressively, so a turn eases into the margin instead
  of hitting it (a walk with turns reached the hard limit in 15-18% of
  frames). Known limit: the crop moves in 2-pixel steps, which leaves small
  frame-to-frame jumps (~0.4-0.5 px median vs ~0.1 px with OIS alone);
  sub-pixel moves need resampling, the same work as horizon levelling.
- Fast path: lens shading looked up once per 2x2 cell (all four map
  channels at the cell centre) instead of three bilinear lookups per pixel.
  AOC (A840, P010): 2870 -> 2189 instructions, ALU 1188 -> 882, texture
  reads 57 -> 13, exposed memory latency 29% -> 7.6% of cycles; occupancy
  still 25%.
- Fast path: RAW tile cache in half precision (packed pairs), half the
  shared memory of the float cache (6.4 -> 3.2 KB per workgroup), to raise
  occupancy. AOC: occupancy 25% -> 31% (2453 instructions). 3-minute 4K60
  10-bit recording with both changes vs the morning build: average GPU
  clock 348 -> 286 MHz at similar load (busy x clock -21%), no dropped
  frames.
- Tried and dropped: sub-pixel stabilisation (resampling the frame in the
  fast pass instead of moving the crop in 2-pixel steps). On the 17U it cost
  about twice the GPU work of the regular pass in 3-minute 4K60 takes
  (busy x clock 401-440 vs 215, GPU 460-650 MHz vs 286) even after three
  rounds of optimisation, for ~0.3 px less frame-to-frame jitter.
  Standard/Strong keep the free 2-pixel crop steps of the regular pass.
- Level horizon (Settings › Video Encoder › Stabilisation): turns the
  frame by the tilt from the gravity sensor, keeping the horizon level in
  landscape; it fades out past 45 degrees (portrait). It zooms in 1.1x so
  the frame can turn about 10 degrees, and levels before taking out shake.
  It runs a turned, sub-pixel shader variant that costs about twice the
  GPU work of the regular pass (opt-in, said in the setting).
  First test (no zoom yet): the turn's direction is right (in the video the
  applied turn cancelled the camera's roll, -0.81 vs -0.83 per degree), but
  tilts of up to 15 degrees ran past the ~5 degrees the margin allowed.
  Measured with the 1.1x zoom (gyro log against the recorded horizon): tilts
  up to ~5 degrees came out level (a 4 degree tilt stayed at -0.25), a 21
  degree tilt was levelled by only ~5.5 (the horizon showed ~15-16
  degrees), and no frame had empty corners. That matches the 2304-row
  sensor mode's margin, where 1.1x leaves ~5.5 degrees (the ~10 of the
  first estimate assumed 2560 rows). The zoom now follows the recording's
  margins: the smallest of 1.1x and what a 9 degree turn needs, up to 1.25x
  (about 1.2x from 2304 rows, 1.1x from 2560 or more). More zoom upscales
  more (4K from ~3200 px wide at 1.2x). The STAB log line
  (`adb logcat -s RawrStab`) now shows the zoom and the turn room. The zoom
  is chosen on a take's first frame and kept for the take.
  Checked on the 17U with this build: that take ran 2560 rows (margins
  112/192 px, zoom 1.1x, turn room 9.7 degrees): tilts up to ~9 came out
  level, an 11 degree tilt was turned 9.7 (the horizon kept ~1-3). The 1.2x
  path (2304 rows) is not yet checked on a take.
  Release check, Stabilisation Standard, gyro log against the video and
  the RawrStab trace: on 1x (margins 120/192 px) and 3.2x, both at 1.1x
  with 9.7 degrees of turn room, tilts up to ~9 degrees came out level; at
  14 degrees the trace shows 9.74 applied of 14.2 wanted, so ~4.5 stays
  visible. Strong not checked (the turn room doesn't depend on it).
- Tried and reverted: holding the viewfinder on a lens switch until the
  new lens's first frames settle. It hid the HAL's default-exposure flash,
  but AE still searched at 800 ms on every switch, and in video mode Rawr
  AE's first manual requests switch the HAL to the 2560-row sensor mode
  (the viewfinder jumps sideways); waiting for that froze the viewfinder
  too long. Lens switching is as it was.
- Tried and reverted: a deeper realtime RAW reader (8 images) and ingress
  queue (3 frames) against the start-of-take drop, from apos-fandango's
  X14U fix (rawr-xiaomi14u, commits 39a49615 and b2d85abe). On the 17U a
  3-minute 4K60 take still dropped 7 frames at the start (2-4 before) and
  had 6 one-frame sensor gaps (2-3 before), at a higher GPU clock.

### Lenses (17U profile)

- New lenses 3.2x 200>25 and 4.3x 200>25, after the 200>50 ones: the same
  200 MP tele mode with the smaller reduced output (Ed's "reduced25": the
  50 MP result resampled to half its pixels, 25 MP), for smaller files.
  To see them: Settings > Lens > Reset to device default (or clear the
  app's data); it replaces lenses edited by hand.
- Checked on the 17U: after the reset both lenses sit right after their
  200>50, and they shoot and save.

### Renderer

- Sensor CFA (RAW tab): how the DNG's colour filter array is read, saved
  with that edit only. Layout Auto, Bayer, Quad Bayer 2x2 or 4x4 blocks;
  order as tagged or RGGB, GRBG, GBRG, BGGR. Some apps write a sensor's
  block mosaic tagged as 2x2 Bayer, which renders magenta: a 50 MP Xiaomi
  15 Ultra DNG from Unspektrawesome holds a Quad Bayer (2x2 sites equal,
  RRGG/RRGG/GGBB/GGBB in 4x4). Auto finds that from the data (Rawr's own
  DNGs are always Bayer) and remosaics it to Bayer once, into the raw
  cache, with the remosaic the camera already uses for block sensor modes
  (CfaRemosaic.h). Checked off-device on that DNG: detection picks 2x2 with
  a wide margin and the remosaiced raw renders with correct colour; a true
  Bayer raw stays Bayer.
- Large uncompressed DNGs: the Auto detection now runs on the full decode
  that builds the raw cache (named -auto<n>, so a later open doesn't decode
  again). Reading a region first made tinydng decode the whole strip inside
  its 128 MiB cap, and that 50 MP DNG failed to open ("memory cap
  exceeded"). Checked on the 17U: it opens, detected as Quad 2x2, with
  correct colour; the CFA section shows in the RAW tab.
- Optional Renderer icon in the app drawer (Renderer › options › Icon in
  app drawer), for launchers that don't show app shortcuts. Off by default.
  The Renderer now runs in its own task, so opening it from a launcher
  doesn't bring up the camera.
- Settings › Renderer: opens the Renderer and holds the same app drawer
  icon switch, so it can be found without a launcher shortcut or the long
  press on the gallery thumbnail.
- Checked on the 17U: Settings > Renderer opens it; the drawer icon opens
  the Renderer. Known: sharing a DNG from Google Photos doesn't offer the
  Renderer (it registers for the exact DNG types only).

### Exposure controls

- Manual ISO shows the standard numbers on the third stops: 80, 160, 320,
  640, 1250, 2500 instead of 79, 159, 317, 635, 1270, 2540 (the exact
  values rounded). The 1/24 EV fine steps between them stay; full and third
  stops keep their snapping. Fork change to Ed's scale
  (NativeCapabilityProjection.kt). Checked on the 17U.

### RAW ingress (PR #2 by the-marox)

- GLES bridge for RAW16 on the Xiaomi 15 (by the-marox, commits f3e8c96
  and 8e053d9): where the Vulkan driver refuses the camera's RAW16 buffers,
  the frame is read through a GLES pbuffer context into a buffer Vulkan
  imports, instead of the CPU upload. On only for ro.product.device "dada"
  (Xiaomi 15); `debug.rawr.gles_bridge=1` opts another device in, `=0` opts
  out. Off, the RAW16 ingress is the previous one: on the 17U nothing of
  the bridge runs (no probe, no EGL context); the app only links EGL and
  GLESv3. Merge into yuv-probe: the link line conflict resolved by hand.
- Checked on the 17U: viewfinder normal, a 200 MP tele photo (the case
  that reached the RAW16 fallback before), no GLES_BRIDGE line in the log.
  Tested on the Xiaomi 15 by the-marox.

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
