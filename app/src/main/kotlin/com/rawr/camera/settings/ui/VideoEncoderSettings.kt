package com.rawr.camera.settings.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.rawr.camera.settings.architecture.SetVideoEncoder
import com.rawr.camera.settings.architecture.SettingsDispatch
import com.rawr.camera.settings.model.KeyframeInterval
import com.rawr.camera.settings.model.SettingsUiState
import com.rawr.camera.settings.model.VideoAudioCodec
import com.rawr.camera.settings.model.VideoAudioRoute
import com.rawr.camera.settings.model.VideoAudioSource
import com.rawr.camera.settings.model.VideoBitrateMode
import com.rawr.camera.settings.model.VideoEncoderConfig
import com.rawr.camera.video.VideoEncoderCapabilities
import com.rawr.camera.video.VideoResolutionMode

@Composable
internal fun VideoEncoderSettings(state: SettingsUiState, dispatch: SettingsDispatch) {
    val encoder = state.values.videoEncoder
    val set: (VideoEncoderConfig) -> Unit = { dispatch.invoke(SetVideoEncoder(it)) }
    val supportedModes = remember { VideoEncoderCapabilities.supportedBitrateModes }
    val maxBitrateMbps = remember { VideoEncoderCapabilities.maxBitrateMbps }
    val context = androidx.compose.ui.platform.LocalContext.current
    val unprocessedSupported = remember { com.rawr.camera.video.VideoAudioInput.supportsUnprocessed(context) }
    val bFramesProduced = remember { com.rawr.camera.video.EncoderObservations.bFramesProduced(context) }
    SettingsPageContainer(testTag = SettingsTestTags.sectionRoot("VideoEncoder")) {
        SettingsGroup(
            title = "Video",
            description = "HEVC settings apply to the next recording. Some encoders ignore B-frame limits; the recording monitor shows the actual GOP."
        ) {
            SettingsRow(
                "Aspect ratio",
                value = encoder.aspect.label,
                supportingText = "Pick it from the aspect button in the video top bar. A centred crop of 1080p, 4K or " +
                    "Open Gate; with the phone upright the video and the crop are upright too."
            )
            SettingDivider()
            SettingsRow(
                "Bit depth"
            )
            if (state.values.videoLogEnabled) SettingsRow("10-bit", value = "Required by LOG")
            else CompactChoiceRow(listOf(10, 8), encoder.bitDepth, { "$it-bit" }) { set(encoder.copy(bitDepth = it)) }
            SettingDivider()
            SettingsRow("Bitrate mode")
            CompactChoiceRow(
                VideoBitrateMode.entries.filter { it.wireValue in supportedModes || it == encoder.bitrateMode },
                encoder.bitrateMode,
                { it.label }
            ) { set(encoder.copy(bitrateMode = it)) }
            VideoResolutionMode.entries.forEach { mode ->
                SettingDivider()
                StandaloneNumericSliderRow(
                    identity = "video_bitrate_${mode.name}",
                    label = "${mode.label} bitrate",
                    minimum = VideoEncoderConfig.MIN_BITRATE_MBPS.toFloat(),
                    maximum = maxBitrateMbps.toFloat(),
                    step = 1f,
                    decimals = 0,
                    value = encoder.bitrateMbps(mode).toFloat(),
                    defaultValue = VideoEncoderConfig().bitrateMbps(mode).toFloat(),
                    unit = " Mbps",
                    enabled = encoder.bitrateMode != VideoBitrateMode.Cq
                ) { set(encoder.withBitrateMbps(mode, it.toInt())) }
            }
            SettingDivider()
            val interval = encoder.keyframeInterval
            val frames = interval.frames(30)
            SettingsRow(
                "I-frame interval",
                value = if (frames <= 1) "Every frame"
                else "${if (interval.wireValue == 1) "0.5" else "${interval.wireValue / 2}"} s · ${interval.frames(24)} / $frames frames"
            )
            CompactChoiceRow(KeyframeInterval.entries, interval, { it.label }) {
                set(encoder.copy(keyframeInterval = it))
            }
            SettingDivider()
            if (frames <= 1) SettingsRow("Max B-frames", value = "Off with All-I")
            else {
                SettingsRow(
                    "Max B-frames",
                    value = if (encoder.maxBFrames < 0) "Auto" else "${encoder.maxBFrames}",
                    supportingText = if (bFramesProduced == false && encoder.maxBFrames != 0)
                        "This phone's encoder made no B-frames in your last recording; it records I and P frames only." else null
                )
                CompactChoiceRow(
                    VideoEncoderConfig.MAX_B_FRAME_CHOICES,
                    encoder.maxBFrames,
                    { if (it < 0) "Auto" else "$it" }
                ) { set(encoder.copy(maxBFrames = it)) }
            }
            SettingDivider()
            SettingsRow("GOP at 30 fps", value = VideoEncoderConfig.gopPattern(frames, encoder.maxBFrames))
        }
        RecordingDndGroup()
        StabilisationGroup()
        SettingsGroup(
            title = "Motion data",
            description = "For stabilising in Gyroflow afterwards. With OIS on, the lens moves on its own and the result " +
                "is less exact."
        ) {
            SettingsSwitchRow(
                title = "Gyroflow gyro log",
                checked = encoder.gyroflowLog,
                supportingText = "Records the gyroscope and accelerometer (about 200 Hz) into a .gcsv file named like the " +
                    "video. Videos in DCIM get it in Download/RAWR/Gyroflow; in a folder you picked it sits beside " +
                    "the video, where Gyroflow loads it by itself.",
                testTag = SettingsTestTags.row("VideoEncoder", "gyroflow_log")
            ) { set(encoder.copy(gyroflowLog = it)) }
        }
        SettingsGroup(
            title = "Audio",
            description = "48 kHz. PCM records uncompressed audio in a .mov; AAC records a smaller .mp4. " +
                "Stereo falls back to mono if the microphone can't do stereo. " +
                "Gain is digital: Android has no analog mic gain. Watch the MIC meter; the lamp turns red on clipping."
        ) {
            SettingsRow("Codec", value = encoder.audioCodec.label)
            CompactChoiceRow(VideoAudioCodec.entries, encoder.audioCodec, { it.label }) {
                set(encoder.copy(audioCodec = it))
            }
            SettingDivider()
            SettingsRow("Source", value = encoder.audioSource.label)
            CompactChoiceRow(
                VideoAudioSource.entries.filter {
                    it != VideoAudioSource.Unprocessed || unprocessedSupported || it == encoder.audioSource
                },
                encoder.audioSource,
                { it.label }
            ) { set(encoder.copy(audioSource = it)) }
            SettingDivider()
            SettingsRow("Input", value = encoder.audioRoute.label)
            CompactChoiceRow(VideoAudioRoute.entries, encoder.audioRoute, { it.label }) {
                set(encoder.copy(audioRoute = it))
            }
            SettingDivider()
            StandaloneNumericSliderRow(
                identity = "video_audio_gain",
                label = "Gain",
                minimum = VideoEncoderConfig.MIN_AUDIO_GAIN_DB.toFloat(),
                maximum = VideoEncoderConfig.MAX_AUDIO_GAIN_DB.toFloat(),
                step = 1f,
                decimals = 0,
                value = encoder.audioGainDb.toFloat(),
                defaultValue = 0f,
                valueFormatter = { if (it.toInt() == 0) "0 dB" else "%+d dB".format(it.toInt()) }
            ) { set(encoder.copy(audioGainDb = it.toInt())) }
            SettingDivider()
            SettingsRow("Low cut", value = if (encoder.audioLowCutHz == 0) "Off" else "${encoder.audioLowCutHz} Hz")
            CompactChoiceRow(VideoEncoderConfig.AUDIO_LOW_CUT_HZ, encoder.audioLowCutHz, { if (it == 0) "Off" else "$it Hz" }) {
                set(encoder.copy(audioLowCutHz = it))
            }
            SettingDivider()
            SettingsRow("Channels", value = if (encoder.audioChannels == 1) "Mono" else "Stereo")
            CompactChoiceRow(listOf(2, 1), encoder.audioChannels, { if (it == 1) "Mono" else "Stereo" }) {
                set(encoder.copy(audioChannels = it))
            }
            if (!encoder.audioCodec.isPcm) {
                SettingDivider()
                SettingsRow("Bitrate", value = "${encoder.audioBitrateKbps} kbps")
                CompactChoiceRow(VideoEncoderConfig.AUDIO_BITRATES_KBPS, encoder.audioBitrateKbps, { "$it" }) {
                    set(encoder.copy(audioBitrateKbps = it))
                }
            }
        }
    }
}

/** 17U: Do Not Disturb for the length of a recording. */
@Composable
private fun RecordingDndGroup() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var enabled by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(com.rawr.camera.video.RecordingDnd.enabled(context))
    }
    var granted by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(com.rawr.camera.video.RecordingDnd.accessGranted(context))
    }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        granted = com.rawr.camera.video.RecordingDnd.accessGranted(context)
        onPauseOrDispose { }
    }
    SettingsGroup(
        title = "While recording",
        description = "Notification banners drawn over the viewfinder can cost a frame or two."
    ) {
        SettingsSwitchRow(
            title = "Do not disturb",
            checked = enabled,
            supportingText = if (enabled && !granted)
                "Needs Do Not Disturb access: allow Rawr in the list that opened (switch off and on to open it again)."
            else
                "Turns on Do Not Disturb (your priority rules) when recording starts and restores it when it " +
                    "stops. Left alone if Do Not Disturb is already on.",
            testTag = SettingsTestTags.row("VideoEncoder", "dnd_while_recording")
        ) {
            enabled = it
            com.rawr.camera.video.RecordingDnd.setEnabled(context, it)
            if (it && !com.rawr.camera.video.RecordingDnd.accessGranted(context))
                com.rawr.camera.video.RecordingDnd.requestAccess(context)
        }
    }
}

/** Gyro stabilisation (ported from apos-fandango's Rawr X14U). */
@Composable
private fun StabilisationGroup() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var mode by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableIntStateOf(com.rawr.camera.video.StabilizerSensor.mode(context))
    }
    var levelHorizon by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(com.rawr.camera.video.StabilizerSensor.levelHorizon(context))
    }
    SettingsGroup(
        title = "Stabilisation",
        description = "Gyro stabilisation (from apos-fandango's Rawr X14U). Takes out hand shake (up/down, " +
            "left/right and tilt) by moving and turning the frame inside the sensor's spare area, so it needs a " +
            "recording smaller than the sensor (4K or 1080p, not Open Gate) and works on the fast recording path " +
            "only. The lens OIS is turned off while it runs. Don't stabilise the video again in Gyroflow."
    ) {
        SettingsRow("Stabilisation", value = listOf("Off", "Standard", "Strong")[mode])
        CompactChoiceRow(listOf(0, 1, 2), mode, { listOf("Off", "Standard", "Strong")[it] }) {
            mode = it
            com.rawr.camera.video.StabilizerSensor.setMode(context, it)
        }
        SettingDivider()
        SettingsSwitchRow(
            title = "Level horizon",
            checked = levelHorizon,
            enabled = mode > 0,
            supportingText = "Keeps the horizon level from the gravity sensor, in landscape. The spare area allows " +
                "a few degrees (about 5 at 4K, less while it also takes out a big shake); past that the frame " +
                "only turns part of the way.",
            testTag = SettingsTestTags.row("VideoEncoder", "level_horizon")
        ) {
            levelHorizon = it
            com.rawr.camera.video.StabilizerSensor.setLevelHorizon(context, it)
        }
    }
}
