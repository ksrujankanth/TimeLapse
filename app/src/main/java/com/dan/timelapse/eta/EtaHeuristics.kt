package com.dan.timelapse.eta

import kotlin.math.max

/**
 * First-run priors, in milliseconds per work unit.
 *
 * These exist only so that a brand-new install (or a brand-new job shape) shows a
 * plausible number instead of "Calculating..." forever. Every value here has
 * been overridden in practice by the learned rate after 2-3 runs, so precision
 * matters far less than the order of magnitude and the direction (a median stack
 * is more expensive than a bypass; a 4x super-resolution fuse is more expensive
 * than its scan).
 *
 * The calibration target is a Pixel 4a class device (Snapdragon 730, 5.7 GB).
 * [deviceFactor] scales it for much smaller or much larger phones.
 */
object EtaHeuristics {

    /** ms per work unit for [phase], before temperature and device scaling. */
    fun baseMsPerUnit(phase: EtaPhase, kind: EtaKind): Double = when (phase) {
        // Video: units are frame-megapixels of OUTPUT, times an effect cost.
        // Measured on a Pixel 4a: AVERAGE w3 at 960x540 = 96 ms per frame-mp,
        // MEDIAN w4 at 1280x720 = 325 ms per frame-mp. With [EtaUnits.effectCost]
        // (1.5 and 5.19) both land on ~62 ms/unit, which is what makes the very
        // first prediction of an effect plausible instead of 13x too optimistic.
        EtaPhase.RENDER -> when (kind) {
            EtaKind.VIDEO_RENDER -> 62.0
            EtaKind.STILL_RENDER -> 90.0
            else -> 20.0
        }
        // Stabilization analysis runs at a fixed 960 px width but still decodes.
        EtaPhase.ANALYZE -> 8.5
        // MFSR: scan/align units are frame-megapixels of INPUT.
        //
        // ALIGN is by far the most expensive phase and the prior is NOT a guess:
        // the engine runs findTransformECC over a 3-level pyramid at FULL
        // resolution with up to 120 iterations per level. Measured on a Pixel 4a
        // (1600x1200, 9 frames): 26.9 s for 9 x 1.92 MP = 1560 ms per input
        // megapixel, and the cost was identical for a noisy and a smooth burst
        // (ECC terminates on the iteration cap, not on content). The old 15 ms
        // figure came from assuming alignment was a cheap pyramid pass; it made
        // the first run of a 30 s render predict 1.9 s.
        EtaPhase.SCAN -> 12.0
        EtaPhase.ALIGN -> 1500.0
        // Fuse units are frames x (inputMP + outputMP). Measured 19 ms/unit.
        EtaPhase.FUSE -> 20.0
        // Finalize/save units are OUTPUT megapixels. Measured 1.2 s for 7.68 MP
        // of finalize + PNG encode, split ~90/55 here.
        EtaPhase.FINALIZE -> 90.0
        EtaPhase.SAVE -> when (kind) {
            EtaKind.MFSR_RENDER -> 60.0
            else -> 120.0 // units are 1 for a video file write
        }
        EtaPhase.ENCODE -> 3.0
        // Folder listing / filtering / indexing: one item per unit.
        EtaPhase.FILTER -> 12.0
        EtaPhase.INDEX -> 8.0
        // Retime units are encoded frames. The whole file is remuxed with new
        // timestamps. Measured in the app: a 300-frame 1080p render spent ~1.5 s
        // here, i.e. ~5 ms/frame, not the 25 ms originally assumed (which made the
        // first-run prediction of a 30 s render 37% too long).
        EtaPhase.RETIME -> 6.0
        // Mux units are 1.
        EtaPhase.MUX -> 1400.0
        // Load units are items (files).
        EtaPhase.LOAD -> 30.0
        // Header-only probe: measured ~1 ms per file on a Pixel 8 Pro, ~2 on a
        // Moto X Play. The device factor covers the difference.
        EtaPhase.PROBE -> 1.5
        // Units are 1 job: the setup is proportional to the job, not to frames.
        EtaPhase.OVERHEAD -> 800.0
    }

    /**
     * Coarse device speed multiplier from total RAM.
     *
     * RAM is a weak proxy for SoC speed, but it is the one hardware fact that is
     * reliably available before any work runs, and the live estimator replaces it
     * within seconds. Deliberately compressed to 0.75..1.6 so a wrong guess is
     * never catastrophic.
     */
    fun deviceFactor(ramGb: Int): Double = when {
        ramGb >= 12 -> 0.70
        ramGb >= 8 -> 0.78
        ramGb >= 6 -> 0.88
        ramGb >= 4 -> 1.00
        ramGb >= 3 -> 1.28
        else -> 1.60
    }

    /**
     * Battery temperature penalty for the prior.
     *
     * Charging while rendering is common (the app is usually plugged in for long
     * jobs) and a hot phone throttles hard. Only the prior is scaled: the live
     * rate measures the real slowdown and takes over within a few percent of a
     * phase.
     */
    fun thermalFactor(tempC: Int): Double = when {
        tempC >= 45 -> 1.55
        tempC >= 42 -> 1.35
        tempC >= 38 -> 1.15
        else -> 1.00
    }

    /** Full prior for one phase, including device and thermal scaling. */
    fun priorMsPerUnit(
        phase: EtaPhase,
        kind: EtaKind,
        ramGb: Int,
        tempC: Int
    ): Double = max(0.0001, baseMsPerUnit(phase, kind) * deviceFactor(ramGb) * thermalFactor(tempC))
}
