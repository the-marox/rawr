# Rawr 17U - to do

Open work on this fork. Results of what is done are in [CHANGELOG.md](CHANGELOG.md).

## Video stabilisation (work in progress)

- Standing still, OIS alone is still smoother than gyro stabilisation
  (walking test: fine jitter OIS alone 1.7 / 0.7 px, Strong 1.0 / 0.7; slow
  sway OIS alone 4.2 / 2.8 px, Strong 1.4 / 1.8). Find a way to beat OIS
  when the camera is held still, or keep OIS on below some shake level.
- Frame-to-frame steps: the crop moves in 2-pixel steps (~0.4-0.5 px median
  frame-to-frame vs ~0.1 px with OIS alone). Sub-pixel resampling cost about
  twice the GPU work of the regular pass in 3-minute 4K60 takes and was
  dropped; look for a cheaper sub-pixel path.
- No roll correction in plain stabilisation (pitch and yaw only).
- Motion blur without OIS not measured.
- Strong not yet checked with Level horizon.

## Level horizon (experimental)

- Turn room ~9.7 degrees at 1.1x zoom (2560-row sensor modes, measured on 1x
  and 3.2x with Standard). The adaptive zoom (about 1.2x where the sensor
  mode has 2304 rows) is not yet checked on a take: confirm in the RawrStab
  trace (`room=112,64 zoom=1.2...`) and repeat the tilt test.
- Optional: show the tilt in degrees on the level cross in the viewfinder.

## Renderer

- Sharing a DNG from Google Photos doesn't offer the Renderer: it registers
  for the exact DNG types only (image/x-adobe-dng, image/dng, image/x-dng).
  Accept image/* shares and keep only DNGs.

## Video recording

- One frame can still drop in the first second of a recording; one
  mid-recording drop was seen once in Open Gate at 60 fps.
- Viewfinder jump on a lens switch in video mode (Rawr AE's first manual
  requests switch the HAL to the 2560-row sensor mode).
- Sensor gaps with OIS off: check whether they change.
- The app's main thread uses ~24% of a core while recording.

## White balance

- Auto WB under warm indoor light comes out warm/yellow: the HAL's AWB for
  third-party apps (17U DNG: HAL neutral R 0.567 B 0.601 vs the wall's
  R 0.634 B 0.531; vivo X300 Ultra 100-300 K warmer than stock per Matt).
- Optional Rawr-side Auto correction (beta, off by default): estimate the
  white from bright near-neutral RAW samples and blend it with the HAL's.
  Prototype on that DNG moved the wall from R/G 1.08 B/G 0.91 to 1.02/0.94.
  Needs a DNG test set first: warm room with and without a white sheet,
  daylight, wood/orange-dominant scene, skin under warm light, sunset.
- Matching WB between lenses when locked.

## Photo

- Tele photos looked darker than the viewfinder before upstream #135; one
  dark shot (RAWR_20261121_152853) was not explained (no log). Re-check
  with the filtered log running if it happens again.
