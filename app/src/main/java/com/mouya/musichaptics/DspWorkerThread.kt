package com.mouya.musichaptics

import android.os.Process
import android.os.SystemClock
import android.util.Log
import kotlin.math.sqrt
import kotlin.math.abs
import kotlin.math.max

/**
 * Dedicated DSP Worker Thread.
 * Runs at THREAD_PRIORITY_URGENT_AUDIO for real-time processing.
 * Pulls PCM from PcmFifo at fixed 5ms intervals (200Hz), processes DSP,
 * and feeds native engine / onset ring buffer.
 *
 * Single consumer of PCM FIFO - eliminates dual-consumer race.
 *
 * v4.5: Multi-band beat detection
 * v4.7: Event-driven haptic — only fire vibration on beat detection,
 *       NOT every frame. Larger refractory periods for musical feel.
 *       Different beat types use different duration & amplitude for
 *       rich layering: SUB (deep long), KICK (strong punch), SNARE (mid),
 *       TICK (light needle), BODY (warm sustained).
 *       No continuous floor vibration — let beats breathe.
 * v4.8.1: DeviceProfile fully integrated. Actuator-aware duration/gain.
 *       Disabled C++ onset path (processOnsetFrames) to prevent dual-detector
 *       cancellation. Global vibration refractory in HapticEngine.triggerBeatVibration.
 */
class DspWorkerThread(
    private val pcmFifo: PcmFifo,
    private val nativeBridge: NativeBridge,
    private val hapticEngine: HapticEngine,  // callback for telemetry/UI
    private val deviceProfile: DeviceProfile = DeviceProfile.DEFAULT,
    private val frameSize: Int = 256,        // 5.3ms @ 48kHz
    private val sampleRate: Int = 48000
) {
    private val TAG = "DspWorkerThread"

    private val framePeriodNs: Long = (frameSize.toLong() * 1_000_000_000L) / sampleRate.toLong()

    @Volatile private var running = false
    private var thread: Thread? = null

    private val processFrame = FloatArray(frameSize)
    private val telemetry = FloatArray(32)

    private val directPcmBuffer = java.nio.ByteBuffer.allocateDirect(frameSize * 4).order(java.nio.ByteOrder.nativeOrder()).apply {
        java.nio.ByteOrder.nativeOrder()
    }
    private val floatPcmView = directPcmBuffer.asFloatBuffer()

    // Multi-band beat detection state
    private var lowBandState = 0f
    private var prevLowRms = 0f
    private var prevMidRms = 0f
    private var prevHighRms = 0f
    private var prevFullRms = 0f
    private var subRefractory = 0
    private var kickRefractory = 0
    private var snareRefractory = 0
    private var tickRefractory = 0
    private var bodyRefractory = 0
    private var dspFrameCount = 0
    private var totalFramesRead = 0L
    private var rmsEma = 0f
    private var bassEma = 0f

    private var lastBeatType = ""

    // v4.8.1: Device-profile-tuned gain & thresholds
    // Devices with small motors (high minGuaranteedAmplitude) need more gain.
    // Devices with big motors (low minGuaranteedAmplitude) need less.
    // boostExponent from profile controls the curve shape.
    // v4.9: Made mutable so UI settings can override at runtime.
    // v4.9: Made mutable so UI settings can override at runtime.
    @Volatile var globalGain: Float = when {        deviceProfile.name.contains("Xiaomi 10") -> 2.5f   // Custom ROM weak motor
        deviceProfile.minGuaranteedAmplitude >= 40 -> 2.0f  // Small motor devices
        deviceProfile.minGuaranteedAmplitude >= 20 -> 1.5f  // Mid-range
        deviceProfile.minGuaranteedAmplitude >= 10 -> 1.2f  // Flagship
        else -> 1.5f
    }
    @Volatile var bassBoost: Float = deviceProfile.bassBoost.coerceIn(1.0f, 2.0f)
    // v4.10: DSP-domain floor. NOT deviceProfile.energyThreshold — that value lives in
    // the HapticEventGenerator's accumulated-energy domain and is 10-30x too large to
    // compare against a single-frame RMS. Using it here silenced every device except
    // Xiaomi 10 (whose energyThreshold happened to be 0.003, i.e. already DSP-scale).
    @Volatile var energyThreshold: Float = deviceProfile.dspEnergyFloor
    // v4.9: User override gain multiplier (from UI prefs)
    @Volatile var userGainOverride: Float = 1.0f

    // 上次触发帧号（用于全局间隔，防止傻震）
    @Volatile private var lastTriggerFrame = -20

    // ── v4.11: 音量总强度门限 + 强度映射 ──
    // volumeGateMin: 系统媒体音量低于此比例（0~1）时完全不震动
    // intensityCap: 系统音量 100% 时的最大输出增益；音量从 gate→1.0 线性映射到 UI 总强度→上限
    @Volatile var volumeGateMin: Float = 0.10f
    // 当前系统媒体音量比例（0~1），由 HapticEngine 同步，只用于门限判断
    @Volatile var intensityCap: Float = 2.5f
    // 当前系统媒体音量比例（0~1），由 HapticEngine 心跳同步
    @Volatile var systemVolumeRatio: Float = 1f
    // hapticThreshold: 0~1，低于此比例的输出强度不触发（与 UI 的"震动阈值"联动）
    @Volatile var hapticThreshold: Float = 0.0f
    // 震动模式（kick/bass_comp/smart），由 HapticEngine 同步
    @Volatile var vibrationMode: String = "kick"
    // per-app 引擎开关与定时开关（由 synchronizeParameters 同步）
    @Volatile var isEngineEnabled: Boolean = true
    @Volatile var quietHoursEnabled: Boolean = false
    @Volatile var quietHoursStart: String = "23:00"
    @Volatile var quietHoursEnd: String = "07:00"

    // ═══ v4.10: Actuator-derived band multipliers & refractory frames ═══
    // Fast motors (ESA1016 / CSA0916 Turbo, ~3ms response) can retrigger far sooner
    // than slow Z-axis units (K80U, ~12.5ms) before pulses smear into a buzz.
    private val subMult = deviceProfile.dspSubMult
    private val kickMult = deviceProfile.dspKickMult
    private val snareMult = deviceProfile.dspSnareMult
    private val tickMult = deviceProfile.dspTickMult
    private val bodyMult = deviceProfile.dspBodyMult

    private val refScale = deviceProfile.dspRefractoryScale
    private fun refFrames(base: Int, minFrames: Int): Int =
        (base * refScale).toInt().coerceAtLeast(minFrames)

    private val subRefFrames = refFrames(25, 10)
    private val kickRefFrames = refFrames(15, 6)
    private val kickSuppressFrames = refFrames(12, 5)
    private val tickRefFrames = refFrames(8, 3)
    private val snareRefFrames = refFrames(12, 5)
    private val bodyRefFrames = refFrames(30, 12)

    // ─── 定时开关检查 ───
    private fun isInQuietHours(): Boolean {
        if (!quietHoursEnabled) return false
        val now = java.util.Calendar.getInstance()
        val nowMin = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
        val s = quietHoursStart.split(":").map { it.toIntOrNull() ?: 0 }
        val e = quietHoursEnd.split(":").map { it.toIntOrNull() ?: 0 }
        val startMin = s.getOrElse(0) { 23 } * 60 + s.getOrElse(1) { 0 }
        val endMin = e.getOrElse(0) { 7 } * 60 + e.getOrElse(1) { 0 }
        return if (startMin <= endMin) nowMin in startMin..endMin
               else nowMin >= startMin || nowMin <= endMin
    }

    fun start() {
        if (running) return
        running = true

        val dspRunnable = Runnable {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

            Log.i(TAG, "DSP Worker started at URGENT_AUDIO priority — v4.10 actuator-calibrated haptic")
            Log.i(TAG, "[DSP-CAL] profile=${deviceProfile.name} floor=${"%.5f".format(energyThreshold)} " +
                "mult(sub/kick/snare/tick/body)=${"%.2f".format(subMult)}/${"%.2f".format(kickMult)}/" +
                "${"%.2f".format(snareMult)}/${"%.2f".format(tickMult)}/${"%.2f".format(bodyMult)} " +
                "refScale=${"%.2f".format(refScale)} refFrames(sub/kick/tick/snare/body)=" +
                "$subRefFrames/$kickRefFrames/$tickRefFrames/$snareRefFrames/$bodyRefFrames " +
                "actuator(f0=${deviceProfile.actuator.resonanceFreq} Q=${deviceProfile.actuator.qFactor} " +
                "resp=${"%.2f".format(deviceProfile.actuator.responseTimeMs)}ms)")
            hapticEngine.onDspWorkerLog("[DSP-CAL] profile=${deviceProfile.name} floor=${"%.5f".format(energyThreshold)} refScale=${"%.2f".format(refScale)} subRef=$subRefFrames tickRef=$tickRefFrames")

            var nextWakeNs = System.nanoTime() + framePeriodNs

            while (running) {
                val framesRead = pcmFifo.read(processFrame, frameSize)

                if (framesRead > 0) {
                    totalFramesRead += framesRead
                    dspFrameCount++

                    // ═══ Multi-band energy analysis ═══
                    var sumSqFull = 0f
                    var sumSqLow = 0f
                    var sumSqMid = 0f
                    var sumSqHigh = 0f

                    val lowAlpha = 0.073f
                    val midHpAlpha = 0.087f
                    val midLpAlpha = 0.55f

                    var midHpState = 0f
                    var midLpState = 0f

                    for (i in 0 until framesRead) {
                        val v = processFrame[i]
                        sumSqFull += v * v
                        lowBandState += lowAlpha * (v - lowBandState)
                        sumSqLow += lowBandState * lowBandState
                        midHpState += midHpAlpha * (v - midHpState)
                        val midHpOut = v - midHpState
                        midLpState += midLpAlpha * (midHpOut - midLpState)
                        sumSqMid += midLpState * midLpState
                        val highOut = v - lowBandState - midLpState
                        sumSqHigh += highOut * highOut
                    }

                    val rmsFull = sqrt(sumSqFull / framesRead)
                    val rmsLow = sqrt(sumSqLow / framesRead)
                    val rmsMid = sqrt(sumSqMid / framesRead)
                    val rmsHigh = sqrt(sumSqHigh / framesRead)

                    rmsEma = 0.95f * rmsEma + 0.05f * rmsFull
                    bassEma = 0.92f * bassEma + 0.08f * rmsLow

                    if (subRefractory > 0) subRefractory--
                    if (kickRefractory > 0) kickRefractory--
                    if (snareRefractory > 0) snareRefractory--
                    if (tickRefractory > 0) tickRefractory--
                    if (bodyRefractory > 0) bodyRefractory--

                    // ═══ Beat detection v4.7 ═══
                    // Larger refractory periods → fewer triggers → more musical feel
                    // Priority: SUB > KICK > TICK > SNARE > BODY
                    // (Tick promoted above Snare for high-freq nuance)

                    var beatType = ""
                    var beatIntensity = 0

                    // 1. SUB-BASS — deepest pulse, long refractory. Bass boost from profile.
                    val subDelta = max(0f, rmsLow - prevLowRms)
                    val subThreshold = energyThreshold * subMult * bassBoost
                    if (rmsLow > subThreshold && subDelta > subThreshold * 0.4f && subRefractory == 0 && rmsLow > bassEma * 1.5f) {
                        subRefractory = subRefFrames
                        kickRefractory = kickSuppressFrames   // suppress KICK after SUB
                        beatType = "SUB"
                        beatIntensity = ((rmsLow - subThreshold) / (subThreshold * 3.3f) * 175f + 80f).toInt().coerceIn(80, 255)
                    }

                    // 2. KICK — strong punch
                    if (beatType.isEmpty()) {
                        val kickDelta = max(0f, rmsLow - prevLowRms)
                        val kickThreshold = energyThreshold * kickMult * bassBoost
                        if (rmsLow > kickThreshold && kickDelta > kickThreshold * 0.3f && kickRefractory == 0 && subRefractory == 0) {
                            kickRefractory = kickRefFrames
                            beatType = "KICK"
                            beatIntensity = ((rmsLow - kickThreshold) / (kickThreshold * 3.5f) * 175f + 80f).toInt().coerceIn(80, 255)
                        }
                    }

                    // 3. TICK — light high-freq needle (promoted for variety)
                    if (beatType.isEmpty()) {
                        val highD = max(0f, rmsHigh - prevHighRms)
                        val tickThreshold = energyThreshold * tickMult
                        if (rmsHigh > tickThreshold && highD > tickThreshold * 0.3f && tickRefractory == 0) {
                            tickRefractory = tickRefFrames
                            beatType = "TICK"
                            beatIntensity = ((rmsHigh - tickThreshold) / (tickThreshold * 6f) * 120f + 40f).toInt().coerceIn(40, 160)
                        }
                    }

                    // 4. SNARE — mid-frequency transient
                    if (beatType.isEmpty()) {
                        val midD = max(0f, rmsMid - prevMidRms)
                        val snareThreshold = energyThreshold * snareMult
                        if (rmsMid > snareThreshold && midD > snareThreshold * 0.35f && snareRefractory == 0) {
                            snareRefractory = snareRefFrames
                            beatType = "SNARE"
                            beatIntensity = ((rmsMid - snareThreshold) / (snareThreshold * 3f) * 145f + 50f).toInt().coerceIn(50, 190)
                        }
                    }

                    // 5. BODY — sustained warm vibration
                    if (beatType.isEmpty()) {
                        val bodyThreshold = energyThreshold * bodyMult
                        if (rmsFull > bodyThreshold && bodyRefractory == 0 && subRefractory == 0 && kickRefractory == 0 && snareRefractory == 0) {
                            val fullDelta = abs(rmsFull - prevFullRms)
                            if (fullDelta < bodyThreshold * 0.4f && rmsFull > rmsEma * 0.75f) {
                                bodyRefractory = bodyRefFrames
                                beatType = "BODY"
                                beatIntensity = ((rmsFull - bodyThreshold) / (bodyThreshold * 4f) * 80f + 40f).toInt().coerceIn(40, 120)
                            }
                        }
                    }

                    // ═══ v4.7: Event-driven haptic output ═══
                    // ONLY fire vibration when a beat is detected.
                    // No continuous floor, no per-frame one-shot.
                    // This lets each vibration pulse fully complete before
                    // the next one fires, eliminating the "mechanical" feel.
                                                                                if (beatType.isNotEmpty()) {
                    // ═══ 触发判断 ═══
                    // 阶段0：引擎开关与定时开关（per-app 启用 + 安静时段）
                    if (!isEngineEnabled) {
                        if (dspFrameCount % 300L == 0L) {
                            Log.i(TAG, "[GATE] engine disabled — skip")
                        }
                    } else if (isInQuietHours()) {
                        if (dspFrameCount % 300L == 0L) {
                            Log.i(TAG, "[GATE] quiet hours — skip")
                        }
                    } else {
                    // 阶段1：系统音量门限 — 低于 gate 完全静音（静音保护）
                    if (systemVolumeRatio < volumeGateMin) {
                        if (dspFrameCount % 300L == 0L) {
                            Log.i(TAG, "[VOL-GATE] sysVol=" + "%.2f".format(systemVolumeRatio) + " < gate=" + volumeGateMin + " — muted")
                        }
                    } else {
                        // 阶段2：弱拍过滤（haptic_threshold）— 信号强度低于阈值视为弱拍，不触发
                        val rmsGate = hapticThreshold * 0.15f
                        if (rmsFull < rmsGate) {
                            if (dspFrameCount % 300L == 0L) {
                                Log.i(TAG, "[HT-GATE] rms=" + "%.5f".format(rmsFull) + " < " + "%.5f".format(rmsGate) + " (ht=" + hapticThreshold + ") — weak beat skipped")
                            }
                        } else {
                            // 阶段2.5：震动模式过滤 — 鼓点只留 KICK，低音留 KICK+SUB
                            val allowed = when (vibrationMode) {
                                "kick" -> beatType == "KICK"
                                "bass_comp" -> beatType == "KICK" || beatType == "SUB"
                                else -> true
                            }
                            if (!allowed) {
                                if (dspFrameCount % 300L == 0L) {
                                    Log.i(TAG, "[MODE] " + beatType + " suppressed by mode=" + vibrationMode)
                                }
                            } else {
                                // 阶段3：强度映射 — 信号越强震感越强，系统音量类似增益档位参与缩放
                                val volScale = ((systemVolumeRatio - volumeGateMin) / (1f - volumeGateMin)).coerceIn(0f, 1f)
                                val rmsProgress = ((rmsFull - rmsGate) / (0.30f - rmsGate)).coerceIn(0f, 1f)
                                val intensityScale = userGainOverride + rmsProgress * (intensityCap - userGainOverride)
                                val effectiveGain = intensityScale * volScale * globalGain
                                val amplifiedIntensity = (beatIntensity * effectiveGain).toInt().coerceIn(1, 255)
                                hapticEngine.onKotlinBeatDetected(beatType, amplifiedIntensity, rmsFull)
                                lastBeatType = beatType
                            }
                        }
                    }
                    }  // close phase0 else (engine on & not quiet hours)
                }
// Update previous values
                    prevLowRms = rmsLow
                    prevMidRms = rmsMid
                    prevHighRms = rmsHigh
                    prevFullRms = rmsFull

                    // Also process through native engine (for telemetry/UI)
                    if (nativeBridge.isLoaded) {
                        floatPcmView.rewind()
                        floatPcmView.put(processFrame, 0, framesRead)
                        nativeBridge.processAudioDirect(directPcmBuffer, framesRead, telemetry)
                        hapticEngine.onNativeTelemetry(telemetry, framesRead)
                    }

                    // Log every 200th frame (~1 second)
                    if (dspFrameCount % 200 == 0) {
                        val fifoLoad = pcmFifo.loadFactor()
                        val msg = "[DSP-WRK] frame#$dspFrameCount totalRead=$totalFramesRead fifoLoad=${"%.2f".format(fifoLoad)} framesRead=$framesRead rms=${"%.5f".format(rmsFull)} low=${"%.5f".format(rmsLow)} mid=${"%.5f".format(rmsMid)} high=${"%.5f".format(rmsHigh)} env=${"%.5f".format(rmsEma)} lastBeat=$lastBeatType"
                        Log.i(TAG, msg)
                        hapticEngine.onDspWorkerLog(msg)
                    }
                }

                // Precise scheduling
                nextWakeNs += framePeriodNs
                val sleepNs = nextWakeNs - System.nanoTime()

                if (sleepNs > 0) {
                    try {
                        Thread.sleep(sleepNs / 1_000_000, (sleepNs % 1_000_000).toInt())
                    } catch (e: InterruptedException) {
                        break
                    }
                } else {
                    nextWakeNs = System.nanoTime() + framePeriodNs
                }
            }

            // Flush remaining — continuous stream auto-stops when loop exits

            Log.i(TAG, "DSP Worker stopped")
        }
        thread = Thread(dspRunnable, "DspWorkerThread")

        thread?.start()
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread?.join(1000)
        thread = null
    }

    fun getLoadFactor(): Float = pcmFifo.loadFactor()
}