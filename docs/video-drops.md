# Video encoder buffer diagnostics

The video path reports two separate YUV input starvation counters:

- `encoderNoReady`: no imported encoder buffer was ready when a camera frame reached the video path.
- `encoderNoImage`: all six GPU output images were still in flight.

They are included in `videoStats()` and are also logged as `VIDEO_YUV_ACQUIRE_DROP` with `reason=noReady` or `reason=noImage`. The drop log now includes `frameOrdinal` and `timestampNs`, so it can be matched to the Camera2 frame and the file-side `encodedFrameGaps` statistic.

`videoStats()` also reports `encoderNoBuffer`, `encoderAcquires`, `encoderDequeues`, `encoderQueued`, `encoderDequeueMs`, `encoderDequeueMaxMs`, `encoderFenceMs` and `encoderFenceMaxMs`. These distinguish `ImageWriter`/encoder starvation from GPU fence latency.

On the validated Xiaomi 15 (`ro.product.device=dada`), a short acquire wait can be enabled for diagnosis:

```sh
adb shell su -c 'setprop debug.rawr.video_acquire_wait_ms 2'
```

The value is capped at 5 ms and is ignored on every other device. The default is zero, so the normal path remains non-blocking. Clear it with:

```sh
adb shell su -c 'setprop debug.rawr.video_acquire_wait_ms 0'
```

The YUV feeder keeps four encoder buffers dequeued ahead by default on every device. On `dada` only, `debug.rawr.video_yuv_ready=6` opts into six at the next recording start; unset, `0`, `4` or invalid values keep four. The writer limit remains 16. No reduction in drops from this experiment has been established.

For a controlled 24 fps run, collect:

```sh
adb logcat -c
adb logcat -v threadtime | grep -E 'VIDEO_YUV_ACQUIRE_DROP|VIDEO_YUV_FLOW|VIDEO_YUV_STATS|RAW_READER_STATS'
```

On Windows PowerShell, replace `grep -E` with `Select-String -Pattern`.

Interpretation:

- `encoderNoReady > 0` points to the Java `ImageWriter` feeder or encoder buffer availability.
- `encoderNoImage > 0` points to GPU output images remaining in flight.
- high `encoderDequeueMaxMs` points to the encoder holding its buffers; high `encoderFenceMaxMs` points to GPU completion latency.
- both zero with a file-side PTS gap does not locate the loss: inspect RAW sensor timestamps, ingress/pairer/slot losses, `present()` and encoder output. `encodedFrameGaps` counts gaps inferred from encoder timestamps, not proven missing camera frames.

## Device observation, 2026-11-25

Build `debug-9479b521` (PR head `5f263cd`), Xiaomi 15 `dada`, 23 mm Normal, 1920x1080, 24 fps. This build still used six ready buffers globally; the new opt-in pool policy is not in that installed APK.

| Wait | Encoded frames | First-to-last PTS span | Final encodedFrameGaps | Max sampled dropped/noReady/noImage |
|---|---:|---:|---:|---|
| 0 ms | 890 | 36.996624 s | 0 | 0/0/0 |
| 2 ms | 647 | 27.066362 s | 5 | 0/0/0 |

Both journals ended with `ok=true` and `published`. Final YUV log for the second run reported zero buffer drops. Its RAW reader log at 13:53:15.100 reported `sensorGaps=1` and `longestMs=234.049`. This is evidence against assuming all gaps originate in YUV buffer acquisition. The runs had unequal durations, were sequential, and were not thermally/scene controlled; do not infer a benefit or regression caused by the wait.

The wait property was disabled (`0`) after testing, and the debug app was stopped. No release app settings were modified.

Timing caveats: dequeue timing includes buffer import, not just ImageWriter blocking. Fence timing is the CPU duration of each wait call, not total GPU execution time. The revised mean divides by wait calls, including timeouts. Timing writers and snapshots now use the same mutex; the earlier APK did not fully synchronize these metrics.
