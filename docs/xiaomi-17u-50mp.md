# Xiaomi 17 Ultra: 50 MP RAW (main, tele, UW)

Changes on top of Rawr 0.5.1 for the Xiaomi 17 Ultra.
Tested on Xiaomi 17 Ultra (nezha, 25128PNA1G).

## Result

Seamless capture on all three rear lenses: the viewfinder streams the binned 4:3 RAW at 30 fps
with AF (continuous + tap), and the shutter sends one still request to a full-size RAW output.
The sensor switches mode for that frame only; no session reconfiguration.

| Lens | Camera | Viewfinder | Full-size | Stream use case | Still keys |
|---|---|---|---|---|---|
| Main | 2 | 4096x3072 | 8192x6144 | 0x80103 | `current_mode` i32 0, `xiaomi.remosaic.enabled` u8 1 |
| Tele | 4 | 4080x3072 | 8160x6144 | 0x80103 | `current_mode` i32 1 |
| UW | 3 | 4096x3072 | 8192x6144 | 0x80103 | `current_mode` i32 0, `xiaomi.remosaic.enabled` u8 1 |

(`current_mode` = `org.codeaurora.qcamera3.sensor_meta_data.current_mode`.)
Lens setup: capture stream on, RAW_SENSOR at the full size, stream use case 0x80103,
both keys with scope Still only. With them in Session/Request scope the binned viewfinder
comes out with a ~450 DN pedestal (pink).

The use case and keys for the main and tele were taken from an existing Unspektrawesome
lens profile; the UW values were found by testing.

## What the HAL needs

- **Stream use case 0x80103 on the full-size output** (vendor value, not in
  `availableStreamUseCases`). Without it the HAL writes the binned frame into the first quarter
  of the full-size buffer, and per-request mode switching fails (`CaptureFailure` reason 0).
- **A small YUV output** (640x480 YUV_420_888) in the session; without it every request fails.
- **Reader usage CPU_READ_OFTEN | RENDERSCRIPT (0x100003)** on the unadvertised full-size reader.
  Xiaomi's cameraserver checks unadvertised output sizes against its own list and the
  RENDERSCRIPT bit skips that check ("can't find size ... in xiaomi size" in logcat is harmless).
- Stills go to full-size + YUV. Adding the binned RAW to the still gives device error 4 on the tele.

## Changes

> **Current state (`xiaomi-17u` branch):** the NDK hook below was the first working route, on the old `main`
> branch only. `xiaomi-17u` does not carry it: lenses with a stream use case or an operating mode run on
> Rawr's Java Camera2 backend (upstream #83), which sets both through `OutputConfiguration` and
> `SessionConfiguration`. The hook notes are kept as history.

**Stream use case from the NDK** (`camera/StreamUseCaseHook.*`, old `main` branch only)
The NDK has no `setStreamUseCase`. `ACameraDevice` builds an
`android::hardware::camera2::params::OutputConfiguration` per output inside libcamera2ndk.so.
The hook patches libcamera2ndk's PLT/GOT import of that constructor (dl_iterate_phdr,
JUMP_SLOT/GLOB_DAT), calls the original, then writes `mStreamUseCase`. The field is found by its
defaults (int64 dynamic range 1, int32 colour space -1, int64 use case 0), verified on every write.
Only active inside a scope around `ACameraDevice_createCaptureSessionWithSessionParameters`.
With a capture stream, only the output whose Surface holds the matching
IGraphicBufferProducer gets the use case (`target=capture` in the log line).
Log: `STREAM_USE_CASE_HOOK useCase=0x80103 applied=1 otherOutputs=2 target=capture offset=128`.

**Profile fields** (`CameraRouting.h`, `CameraProfileJson.cpp`, Kotlin model/codec/editor)
- `streamUseCase` (number) per lens; editor dropdown 0 / 0x80103.
- `readerUsage: "cpuRenderscript"` (`kReaderUsageCpuRenderscript`).
- Stream picker accepts an unadvertised size ("(unadvertised)").

**Session / requests**
- `NativeCameraController`: use-case lenses keep the YUV companion attached
  (640x480 minimum); the capture-stream reader uses CPU+RenderScript usage when the lens has a
  use case.
- `CameraDeviceSession`: for use-case lenses, stills target full-size + companion instead of
  full-size + viewfinder RAW.
- `CameraKeyInjection`: session keys that the HAL doesn't advertise are written anyway
  (logged `unadvertised_session_key=forced`) instead of aborting session creation.
- `CameraCallbacks`: on use-case lenses a lost buffer only fails the still when it's on the
  full-size output (the HAL drops the YUV buffer on 50 MP stills).
- vivo still IDs are only added when the tag resolves.

**Viewfinder latency** (`FramePairer`, `FrameSubmitCoordinator`, `FrameCapturePort`)
With the use-case full-size output configured, the HAL returns viewfinder results a few frames
after the images; the pairer dropped most frames (~11-20 fps). Those lenses now keep 2 unmatched
images instead of 1 (3+1+2 = the reader's 6). `PAIRER_PRESSURE` also logs the timestamps.

**Scope:** everything above only runs on lenses with a stream use case. vivo capture-stream
lenses and all other lenses behave as before, with one exception: the session-key change in
`CameraKeyInjection` applies to every lens (unadvertised session keys no longer abort the session).

**Unrelated, small:** lens name limit raised to 12 (`LENS_NAME_MAX_LENGTH`, test updated).

## 200 MP capture: zero copy

`200>50` (3.2x and 4.3x) reduces every 200 MP frame on the GPU (`ReducedRaw`, hexadeca to quad) before the
merge. Until November 2026 the CPU first unpacked each RAW10 frame to 16-bit (`copyRawToPackedRaw16`, one
thread) and copied it into the reduce's upload buffer. Now:

- The photo reader asks for usage `0x1100003` (`0x100003` + GPU_DATA_BUFFER); the 17U accepts it for the
  16320x12288 output. `0x100103` (GPU_SAMPLED_IMAGE) is refused by gralloc and the shot fails.
- Each reduced RAW10 frame is imported as a `VkBuffer` (same recipe as `RawAhbImporter::ensureBufferImport`)
  and `RawRemosaicker` reads the packed RAW10 directly (`raw10 = true`, row stride 20400). The camera frame
  stays leased until its slot is collected. Only the 128 binned-corner samples are read on the CPU.
- A frame with an acquire fence, or one that cannot be imported, falls back to the CPU path
  (`FULLRES_GPU_DIRECT_FALLBACK` in logcat). That path now unpacks on up to 8 threads straight into the upload
  buffer, and the two 400 MB upload buffers are only allocated when it is used.
- `adb shell setprop debug.rawr.still_gpu_direct 0` turns zero copy off, to compare.

Measured on the 17U (3.2x `200>50`, 6 frames, 20 shots alternating builds `6452350` and `62e248d`, phone
warming from 35 to 42 C; shots 2-5 of each block):

| | Before (CPU unpack) | After (zero copy) |
|---|---|---|
| 6 frames captured | 2.65-4.74 s (median 3.12) | 1.46-1.58 s (median 1.52) |
| First shot after opening the app | 4.14-5.55 s | 1.49-1.59 s |
| App work per 200 MP frame | 261-539 ms (median 346) | 52-102 ms (median 63) |
| JPEG + DNG saved | 9.6-15.5 s (median 11.6) | 9.1-12.7 s (median 10.8) |

The app now waits for the sensor (one 200 MP frame every ~133 ms). Merge, demosaic, render and Ultra HDR
(about 7.5-8 s) are unchanged, so the end-to-end gain is small.

## Known limits

- 50 MP *streaming* (full-size as the viewfinder stream, no capture stream) has no working AF on
  this HAL; the seamless setup is the one to use.
- Tele 200 MP works: photo stream 16320x12288 RAW10 with use case 0x80103, session op mode 36868,
  `xiaomi.remosaic.enabled` 0 on requests and 1 on the still. The app remosaics the 4x4 blocks itself.
  Outputs: `200>50` (up to 6 frames, 2x2 binned to a 50 MP DNG) and `200 SF` (single frame, full 200 MP DNG,
  about 200 MB). The JPEG is always 50 MP.
- The 17 Ultra HAL has no ULTRA_HIGH_RESOLUTION_SENSOR / REMOSAIC_REPROCESSING capability.
