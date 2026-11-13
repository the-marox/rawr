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

**Stream use case from the NDK** (`camera/StreamUseCaseHook.*`, new)
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

## Known limits

- 50 MP *streaming* (full-size as the viewfinder stream, no capture stream) has no working AF on
  this HAL; the seamless setup is the one to use.
- Tele 200 MP (16320x12288 is listed in Xiaomi's tables) not explored yet.
- The 17 Ultra HAL has no ULTRA_HIGH_RESOLUTION_SENSOR / REMOSAIC_REPROCESSING capability.
