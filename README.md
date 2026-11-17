<p align="center">
  <img src="docs/assets/rawr-logo.png" alt="Rawr logo" width="200">
</p>

# Rawr 17U

> **Rawr 17U - unofficial modified version of Rawr.**
> Based on Rawr 0.5.1 by adityawarmanfw (GPL-3.0-only, see `NOTICE` and `LICENSE`):
> [github.com/adityawarmanfw/rawr](https://github.com/adityawarmanfw/rawr)
>
> Modified by Paulo Gonçalves ([@utalpt](https://github.com/utalpt)), November 2026. Not affiliated with
> or endorsed by the original author. Changes in this version are listed under
> [What this fork adds](#what-this-fork-adds-xiaomi-17-ultra).
> The release build installs as `x17u.rawr.camera` ("Rawr 17U") and the debug build as
> `x17u.rawr.camera.debug`, next to the official app.

## Download (Xiaomi 17 Ultra)

- Release (recommended): [rawr17u.apk](https://github.com/utalpt/rawr/releases/latest/download/rawr17u.apk)
  ([releases](https://github.com/utalpt/rawr/releases)). Installs as `x17u.rawr.camera` ("Rawr 17U"); versions
  are named after the Rawr version they build on, e.g. `0.5.1-17u.1`, and each one installs over the previous.
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

**Resolution**
- **50 MP RAW** on the main, tele and ultra-wide cameras: the viewfinder streams the binned 4:3 RAW at 30 fps
  with continuous and tap AF, and the shutter takes one full-size still. Multiframe merges up to 30 full-size
  frames (17 by default, memory-aware: strips when the merge does not fit).
- **200 MP tele** at 3.2x and 4.3x, in two lenses: `200>50` merges up to 6 frames and writes a 50 MP DNG
  (2x2 binning of the quad-Bayer blocks), and `200 SF` writes one single-frame full 200 MP DNG (about 200 MB).
  The JPEG is always developed at 50 MP.
- `200>50` capture is zero copy: the GPU reduces each 200 MP frame straight from the camera buffer, with no CPU
  unpack. On the 17U the 6 frames are in after about 1.5 s (2.7-4.7 s before), also when the phone is warm;
  merge and JPEG time are unchanged. Details in [`docs/xiaomi-17u-50mp.md`](docs/xiaomi-17u-50mp.md#200-mp-capture-zero-copy).
- Full-size stills use our own block remosaic (directional colour planes, per-zone flat-field) because the
  17 Ultra HAL offers no remosaic reprocessing.

**Lenses and readout modes**
- One lens button per focal length (0.6x, 1x, 3.2x, 4.3x; the tele periscope moves to 100 mm for 4.3x), with a variant picker: `Normal`, `50MP`, `200>50` / `200 SF`, `ISZ`
  (in-sensor zoom) and the dual-gain readouts `DCG`, `DSG`, `DAG` (14-bit, with a per-lens ISO cap).
- Lens bar grouping (Auto / Grouped / All) and button size (Compact / Normal / Large), set from the eye panel.
- Exposure memory (**Settings > Control Style > Exposure memory**): **Per camera**, each lens keeps its own
  exposure mode, ISO, shutter, EV and WB; or **Shared**, one set for every lens, like one camera body (focus
  stays per lens). Both are kept after closing the app.
- Each lens can keep its own frame count, look, tone and film.

**Viewfinder and controls**
- Swipe-down quick panel (AE / metering, highlights, frames per lens, per-lens look), exposure row with
  tap-to-set slider bar, VF controls show/hide, RESET and UNDO. Camera controls persist across restarts.
- Film strip and PARAMS editor, AgX quick card with advanced grids, long-press help on controls.
- Themes (Leica, Mono, Steel, Sage, Custom), button shapes, and a layout that stays upright in landscape.
- Video: 60 and 120 fps in the frame-rate cycle.

**Xiaomi HAL workarounds**: lost companion buffers on stills, late viewfinder results, unadvertised session keys.

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
