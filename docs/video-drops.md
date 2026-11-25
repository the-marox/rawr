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

The YUV feeder keeps six encoder buffers dequeued ahead of the recording path. This reduces transient `noReady` misses while retaining the existing 16-buffer writer limit.

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
- both zero with a file-side PTS gap means the loss is later in `present()`/encoder output and should be correlated with `VIDEO_PRESENT_FAIL` and native recorder statistics.
