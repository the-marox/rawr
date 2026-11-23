<p align="center">
  <img src="docs/assets/rawr-logo.png" alt="Rawr logo" width="200">
</p>

# Rawr 17U

> **Rawr 17U - unofficial modified version of Rawr.**
> Based on Rawr 0.5.1 by adityawarmanfw (GPL-3.0-only, see `NOTICE` and `LICENSE`), synced with its main
> branch up to #151:
> [github.com/adityawarmanfw/rawr](https://github.com/adityawarmanfw/rawr)
>
> Modified by Paulo Gonçalves ([@utalpt](https://github.com/utalpt)), November 2026. Not affiliated with
> or endorsed by the original author. Changes in this version are listed under
> [What this fork adds](#what-this-fork-adds-xiaomi-17-ultra).
> The release build installs as `x17u.rawr.camera` ("Rawr 17U") and the debug build as
> `x17u.rawr.camera.debug`, next to the official app.

## Download (Xiaomi 17 Ultra)

- Release (recommended): [latest release](https://github.com/utalpt/rawr/releases/latest), file
  `rawr17u-<version>.apk`. Installs as `x17u.rawr.camera` ("Rawr 17U"); versions are named after the Rawr
  version they build on, e.g. `0.5.1-17u.1`, and each one installs over the previous.
- Test build: [rawr-debug.apk](https://github.com/utalpt/rawr/releases/download/debug/rawr-debug.apk)
  ([release page](https://github.com/utalpt/rawr/releases/tag/debug)). Every push to `xiaomi-17u` rebuilds it;
  install it over the previous one.
- Lens setup (50 MP, 200 MP tele, vendor keys, merge limits) is built into the APK. If you restored an older
  backup or edited lenses, use **Settings > Capture > Lens > Reset to device default** (bottom of the list) to get the current profile.
- Optional recommended settings: [`presets/Rawr17U-recommended.zip`](presets/Rawr17U-recommended.zip). Import it
  with **Settings > About > Backup > Import Backup**. It holds settings only (no lens profiles):
  - Multiframe on (Rexture, 17 frames, Chroma Denoise on)
  - RAWR look contrast +61, capture sharpening Medium
  - Highlight recovery off, distortion correction off, AgX preset Default

## What this fork adds (Xiaomi 17 Ultra)

Everything the fork adds, kept here also once Ed has taken it into Rawr: those items say *(Already in
Rawr: Ed's #N)*, with the pull request it came in. Entry by entry, with how each was checked:
[CHANGELOG.md](CHANGELOG.md). Synced with Ed's main up to #151 (23 Nov 2026).

**Photo: 50 and 200 MP**
- **50 MP RAW** on the main, tele and ultra-wide cameras: the viewfinder streams the binned 4:3 RAW at 30 fps
  with continuous and tap AF, and the shutter takes one full-size still. Multiframe merges up to 30 full-size
  frames (17 by default, memory-aware: strips when the merge does not fit). *(Already in Rawr: Ed's #143, the
  17 Ultra and 15 Ultra built-in profiles.)*
- **200 MP tele** at 3.2x and 4.3x: `200>50` merges up to 6 frames into a 50 MP DNG (2x2 binning of the
  quad-Bayer blocks), `200>25` the same at 25 MP, and `200 SF` writes one full 200 MP DNG (about 200 MB).
  *(Already in Rawr: Ed's #143.)*
- **Zero-copy 200 MP bursts:** the GPU reduces each 200 MP frame straight from the camera buffer, with no
  CPU unpack; the 6 frames are in after about 1.5 s (2.7-4.7 s before). *(Already in Rawr: Ed's #144.)*
- **Fast 200 MP remosaic** (4x4 blocks, well under 1 s instead of ~16 s), and the 200 MP JPEG developed
  straight from the blocks (no stair-stepped edges from binning the remosaicked frame).
- **Lost companion buffers** on full-size stills no longer fail the photo (Xiaomi's HAL drops the small
  companion output while the RAW arrives). *(Already in Rawr: Ed's #143, as the per-lens
  ignoreLostCompanion workaround.)*
- **Focus after a 1x 50 MP single frame:** the lens is held at its distance for a few frames after the
  still, so the viewfinder no longer jumps. *(Already in Rawr: Ed's #141.)*
- **Photo aspect ratio** crop of the JPEG (the DNG stays full), picker in the top bar; 3:2 and 16:9 fill
  the screen width in the viewfinder, like 16:9 video.
- **Padded raw edges** dropped per lens (`image.cropEdges`), for stills and the viewfinder. *(Already in
  Rawr: Ed's #150.)*

**Lenses and controls**
- One lens button per focal length (0.6x, 1x, 3.2x, 4.3x; the periscope moves to 100 mm for 4.3x) with a
  variant picker: `Normal`, `50MP`, `200>50` / `200>25` / `200 SF`, `ISZ` (in-sensor zoom) and the dual-gain
  readouts `DCG`, `DSG`, `DAG` (14-bit, with a per-lens ISO cap).
- Lens bar grouping (Auto / Grouped / All) and button size (Compact / Normal / Large), from the eye panel.
- **Exposure memory** (Settings > Control Style): **Per camera**, each lens keeps its own exposure mode,
  ISO, shutter, EV and WB, or **Shared**, one set for every lens (focus stays per lens).
- Each lens can keep its own frame count, look, tone and film.
- Manual ISO shows the standard numbers on the third stops (80, 160, 320, 640, 1250 instead of 79, 159,
  317, 635, 1270).
- Swipe-down quick panel (AE / metering, highlights, frames per lens, per-lens look), exposure row with
  tap-to-set slider bar, VF controls show/hide, RESET and UNDO; camera controls persist across restarts.
- Film strip and PARAMS editor, AgX quick card with advanced grids, long-press help on controls.
- Themes (Leica, Mono, Steel, Sage, Custom), button shapes, and a layout that stays upright in landscape.

**White balance**
- **Pick neutral** (WB panel > PICK NEUTRAL, then tap something truly white or grey): Rawr measures that
  area and switches to Manual with the temperature and tint that make it neutral; warm picks correct about
  halfway, keeping some of the light's warmth.
- **Manual WB renders with the requested gains as white**, so the JPEG matches the DNG (it took the HAL's
  own neutral before, a magenta cast). *(Already in Rawr: Ed's #142.)*

**Video**
- **4K60 10-bit without dropped frames:** a fast path that demosaics, shades, white-balances, applies the
  look and writes P010/NV12 for the encoder in one GPU pass (0 dropped frames over 3 minutes, phone at
  ~4-5 W instead of ~8-12 W), with the encoder fed straight from the GPU.
- **60 fps sensor modes:** the blank rows they pad are left out of the viewfinder, metering and recording
  (`image.cropEdgesHighFps`), on the rear lenses and the front camera. *(Already in Rawr: Ed's #150.)*
- **Open Gate** at 60 fps records 3408x2560 (4:3), the same box as the viewfinder; 4096x3072 at 30 fps.
- **Recording aspect ratios** (1.43 IMAX to 2.76 Ultra Panavision, XPan).
- **Gyro stabilisation** (Standard, Strong) and **Level horizon**, built on apos-fandango's gyro integrator
  from the Rawr X14U fork.
- **Clean recording view** (controls fade out, a swipe shows them) and **Do not disturb while recording**.
- Light meter (lux, EV, colour temperature, flicker, laser distance), antibanding from the flicker sensor
  and a Gyroflow gyro log (.gcsv beside the video), from the-marox's PR #1.

**Galaxy S23 Ultra**
- Faster lens switching (median 2.6 s to 0.6-0.7 s) and app launch: the camera stays open across lens
  switches of the same logical camera, and RAW10 buffers are probed up front. *(Already in Rawr: Ed's
  #140.)*

**Xiaomi 15 (by the-marox, PRs #2 to #5)**
- GLES bridge for RAW16 where the Vulkan driver refuses the camera buffers; 60 fps only where a sensor mode
  delivers the RAW stream; sensed flicker for AE; ToF focus and light-sensor WB (both also work on the
  17 Ultra); 1080p from the full sensor width. The mirrored-lens preview fix of #3 is *(already in Rawr:
  Ed's #150)*.

**Renderer**
- Sensor CFA (RAW tab): Auto finds a Quad Bayer block mosaic tagged as Bayer (for example 50 MP Xiaomi 15
  Ultra DNGs from other apps) and remosaics it; large uncompressed DNGs open; optional Renderer icon in the
  app drawer and Settings > Renderer.

### How 50 and 200 MP are reached

Xiaomi's HAL only delivers full-size frames to an output created with the vendor stream use case `0x80103`
(not listed in `availableStreamUseCases`), and the 200 MP stream also needs session operating mode `36868`.
The NDK can set neither, so these lenses run on Rawr's Java Camera2 backend, which sets both through
`OutputConfiguration` and `SessionConfiguration` (any lens with a use case or operating mode uses it
automatically). The rest is configuration, from the lens profile:

| | Viewfinder | Still stream | Notes |
|---|---|---|---|
| 50 MP (main, UW) | 4096x3072 binned | 8192x6144 | `current_mode` 0 and `xiaomi.remosaic.enabled` 1, scope **Still** only |
| 50 MP (tele) | 4080x3072 binned | 8160x6144 | `current_mode` 1, scope **Still** only |
| 200 MP (tele) | 4080x3072 binned | 16320x12288 RAW10 | use case `0x80103`, session op mode `36868`, `xiaomi.remosaic.enabled` 1 on the still |

The full-size output needs a CPU + RenderScript reader (usage `0x100003`) so Xiaomi's cameraserver accepts the
unadvertised size; `200>50` adds GPU_DATA_BUFFER (`0x1100003`) so the GPU can read the frame in place. The
session also needs a small companion output in the session (without it every request fails). The still
request carries the full-size and companion outputs only; adding the binned RAW stalls the HAL. Details,
logs and the failed attempts are in [`docs/xiaomi-17u-50mp.md`](docs/xiaomi-17u-50mp.md).

An Android RAW based camera application with GPU image processing, film simulation, JPEG/DNG
capture, video recording, and DNG renderer.

Requires RAW camera access and compatible Vulkan hardware. General device compatibility is not yet established.

## Build

Use JDK 17, an Android SDK, and `uv` on macOS or Linux. Install the pinned components:

```sh
sdkmanager 'platforms;android-36' 'build-tools;36.0.0' 'ndk;29.0.14206865' 'cmake;3.31.6'
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleDebug
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.
Install explicitly with `adb install -r <apk>`. The debug application ID is
`com.rawr.camera.debug`; release uses `com.rawr.camera`.

See [build and signing](docs/build.md), [native architecture](app/src/main/cpp/ARCHITECTURE.md),
and [native modules](native/README.md).

## Checks

```sh
./gradlew :app:testDebugUnitTest :app:testReleaseUnitTest :app:lintDebug :app:lintRelease
cmake -S tests -B tmp/host-tests -G Ninja -DCMAKE_BUILD_TYPE=Debug
cmake --build tmp/host-tests --parallel 4
ctest --test-dir tmp/host-tests --output-on-failure
uv run --no-project tools/release/repository_hygiene.py
```

See [host tests](tests/README.md) for dependencies and sanitizers, and
[offline replay](docs/replay.md) for the retained MoltenVK tools and limitations.


## License

See [LICENSE](LICENSE), [NOTICE](NOTICE), and [third-party notices](THIRD_PARTY_NOTICES.md).
