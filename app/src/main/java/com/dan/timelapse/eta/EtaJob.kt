package com.dan.timelapse.eta

import kotlin.math.max
import kotlin.math.min

/** One planned phase and how much work it contains, in [EtaUnits]. */
data class EtaPhasePlan(val phase: EtaPhase, val units: Double)

/**
 * A job's shape: what will happen, in order, and how much work each phase holds.
 *
 * [tailMs] is work the caller cannot describe as a phase (kept for callers that
 * have an opaque finalisation step). Prefer describing it as a phase when the
 * work is known — a phase is measured and learned, a tail constant is not.
 */
data class EtaJobSpec(
    val signature: EtaSignature,
    val phases: List<EtaPhasePlan>,
    val tailMs: Double = 0.0,
    /** Battery temperature at job start, °C. Only scales the prior. */
    val temperatureC: Int = 30
)

/**
 * A single run of a job. Callers drive it with [beginPhase] / [progress] and
 * close it with [finish] (or [abandon] on cancel/failure).
 *
 * Thread-safety: one job is driven by one render thread. The UI reads the number
 * the callbacks already carry, it never calls into the job.
 */
class EtaJob internal constructor(
    private val model: EtaModel,
    private val spec: EtaJobSpec,
    private val now: () -> Long,
    private val onCompleted: (() -> Unit)?,
    /**
     * How much this run's measurements count when they are learned.
     *
     * The first job after the process starts pays one-time costs (MediaCodec
     * creation, JIT, OpenCV class loading) that a steady-state estimate must not
     * be biased by, so it is recorded with a reduced weight. Everything after it
     * counts fully.
     */
    private val sampleWeight: Double = 1.0
) {
    companion object {
        /**
         * Weight of the prior against the live measurement, from the number of
         * rate samples taken inside the phase: `PRIOR_SHARE / (PRIOR_SHARE + samples)`.
         *
         * Sample-count based rather than progress based. A phase at 25% progress
         * can still have only two samples (if each sample covers 12% of the
         * phase), and extrapolating the remaining 75% from two samples is not
         * trustworthy; conversely a long phase with 12 samples taken in the first
         * 2% deserves to be believed. 0.6 gives 50/50 at the second sample and
         * under 6% prior weight after ten.
         */
        const val PRIOR_SHARE = 0.6

        /** Growth cap of the displayed ETA, in multiples of wall-clock time. */
        const val MAX_RISE_PER_SECOND = 2.0

        /** Hard ceiling relative to the initial prediction, to survive bad samples. */
        const val CEILING_FACTOR = 3.0

        /** How many recent per-sample rates the median is taken over. */
        const val RATE_WINDOW = 5
    }

    private val plans = spec.phases.toMutableList()
    private val completedPhases = HashSet<EtaPhase>()

    private var currentPhase: EtaPhase? = null
    private var currentUnits: Double = 0.0
    private var currentDone: Double = 0.0
    private var phaseStartMs: Long = 0L
    private var lastSampleMs: Long = 0L
    private var lastSampleUnits: Double = 0.0

    /**
     * Most recent per-sample rates, ms per unit.
     *
     * The estimate uses their MEDIAN, not an EMA. MFSR's align phase emits one
     * sample every ~3 s and its first frame carries engine setup, so a single
     * slow sample dragged an EMA for ~12 s — the mid-render estimate sat ~30%
     * high for a third of the job. A median rejects that outlier in either
     * direction while still tracking a genuine slowdown after two samples.
     */
    private val recentRates = DoubleArray(RATE_WINDOW)
    private var rateIndex: Int = 0
    private var rateCount: Int = 0

    /** Average of the first samples, used before there are enough for a median. */
    private var warmupRateSum: Double = 0.0

    /** Units x learned rate, without the running speed factor. */
    private var phaseBaselineMs: Double = 0.0

    /** Baseline x speed factor: what the phase looked like it would take at its start. */
    private var phasePredictedMs: Double = 0.0

    private var speedFactor: Double = 1.0
    private val startedAtMs: Long = now()
    private var finished: Boolean = false

    /** Sum of the phase durations already recorded, to derive the overhead. */
    private var recordedPhaseMs: Double = 0.0
    private var recordedPhaseCount: Int = 0

    private var displayMs: Double = -1.0
    private var lastSmoothMs: Long = now()

    private val totalPlannedUnits: Double =
        spec.phases.sumOf { if (it.units.isFinite() && it.units > 0) it.units else 0.0 }

    /**
     * What the model expects for the whole job with the CURRENT phase plan.
     *
     * Deliberately a property, not a constructor val: the initial spec can only
     * guess some counts (MFSR plans ALIGN with the whole scan window until the
     * real selection is known). A frozen value was 11% high on every subsequent
     * run because it kept multiplying the learned rate by the stale unit count.
     */
    val predictedTotalMs: Double get() = computePlanTotalMs()

    /** The prediction as it stood before any work ran, for logs and the ceiling. */
    val predictedTotalAtStartMs: Double = computePlanTotalMs()

    private fun computePlanTotalMs(): Double {
        var total = spec.tailMs + model.predictMsPerUnit(
            EtaPhase.OVERHEAD, spec.signature, spec.signature.ramGb, spec.temperatureC
        )
        for (plan in plans) {
            if (plan.units <= 0.0) continue
            total += plan.units * model.predictMsPerUnit(
                plan.phase, spec.signature, spec.signature.ramGb, spec.temperatureC
            )
        }
        return if (total.isFinite() && total > 0) total else 0.0
    }

    /** Number of observations behind the closest level of the first phase. */
    val priorSamples: Int =
        spec.phases.firstOrNull()?.let { model.samplesFor(it.phase, spec.signature) } ?: 0

    val signature: EtaSignature get() = spec.signature

    /**
     * Enter (or re-enter) a phase. Re-entering the same phase only widens its
     * unit count — the clock keeps running, because e.g. MFSR's fuse pass learns
     * its frame count after the alignment pass has already begun.
     */
    fun beginPhase(phase: EtaPhase, units: Double) {
        if (finished) return
        val safeUnits = EtaUnits.sanitize(units)

        if (currentPhase == phase) {
            if (safeUnits > currentUnits) {
                currentUnits = safeUnits
                updatePlanUnits(phase, safeUnits)
                recomputePrediction()
            }
            return
        }

        closeCurrentPhase()

        updatePlanUnits(phase, safeUnits)
        currentPhase = phase
        // The caller's unit count wins at phase start: it is the only code that
        // knows the real count (MFSR plans ALIGN with the whole scan window, then
        // starts it with the frames actually selected). A later re-entry may only
        // widen it, never shrink it.
        currentUnits = if (safeUnits > 0.0) safeUnits else planUnitsOf(phase)
        currentDone = 0.0
        phaseStartMs = now()
        lastSampleMs = phaseStartMs
        lastSampleUnits = 0.0
        rateIndex = 0
        rateCount = 0
        warmupRateSum = 0.0
        java.util.Arrays.fill(recentRates, 0.0)
        recomputePrediction()
    }

    /** Report absolute work done inside the current phase. */
    fun progress(doneUnits: Double): Long {
        if (finished) return remainingMs()
        if (currentPhase == null) return remainingMs()
        if (doneUnits.isFinite() && doneUnits > currentDone) currentDone = doneUnits

        val t = now()
        val dt = t - lastSampleMs
        if (dt >= 40L) {
            val du = currentDone - lastSampleUnits
            if (du > 1e-9) {
                var instant = dt.toDouble() / du
                val reference = when {
                    rateCount > 0 -> liveRateMsPerUnit()
                    currentUnits > 0.0 && phaseBaselineMs > 0.0 -> phaseBaselineMs / currentUnits
                    else -> instant
                }
                // One stalled frame (a GC pause, a thermal sleep, a slow decode)
                // must not redefine the rate. Clamp it to 2%..50x the reference.
                instant = instant.coerceIn(reference * 0.02, reference * 50.0)
                recentRates[rateIndex % RATE_WINDOW] = instant
                rateIndex++
                rateCount++
                warmupRateSum += instant
            }
            lastSampleMs = t
            lastSampleUnits = currentDone
        }
        return remainingMs()
    }

    /** Median of the recent per-sample rates (mean while fewer than 3 exist). */
    private fun liveRateMsPerUnit(): Double {
        if (rateCount == 0) return 0.0
        val filled = minOf(rateCount, RATE_WINDOW)
        if (filled < 3) return warmupRateSum / rateCount
        val sorted = DoubleArray(filled) { recentRates[it] }
        sorted.sort()
        return if (filled % 2 == 1) sorted[filled / 2]
        else (sorted[filled / 2 - 1] + sorted[filled / 2]) / 2.0
    }

    /** Time-linear progress from the learned phase plan, 0..100. */
    fun progressPercent(phase: EtaPhase, fraction: Double): Int {
        val total = predictedTotalMs.coerceAtLeast(1.0)
        val f = fraction.coerceIn(0.0, 1.0)
        var done = 0.0
        val targetIndex = plans.indexOfFirst { it.phase == phase }
        if (targetIndex >= 0) {
            for (index in 0 until targetIndex) {
                val plan = plans[index]
                if (plan.units <= 0.0) continue
                done += plan.units * model.predictMsPerUnit(
                    plan.phase, spec.signature, spec.signature.ramGb, spec.temperatureC
                )
            }
            val target = plans[targetIndex]
            if (target.units > 0.0) {
                done += target.units * model.predictMsPerUnit(
                    target.phase, spec.signature, spec.signature.ramGb, spec.temperatureC
                ) * f
            }
        }
        // Setup (writer/encoder construction) is paid before the first frame, so
        // it belongs at the start of the bar, not spread across it.
        val overhead = model.predictMsPerUnit(
            EtaPhase.OVERHEAD, spec.signature, spec.signature.ramGb, spec.temperatureC
        )
        done += overhead.coerceAtMost((total - done).coerceAtLeast(0.0))
        return (100.0 * done / total).toInt().coerceIn(0, 100)
    }

    /**
     * Close the current phase without ending the job (the next [beginPhase]
     * normally does this, but a caller that reports an intermediate milestone
     * can call it explicitly).
     */
    fun endPhase() {
        if (finished) return
        closeCurrentPhase()
    }

    /** Smoothed remaining milliseconds. Always >= 0. */
    fun remainingMs(): Long {
        if (finished) return 0L
        val raw = rawRemainingMs()
        val smoothed = smooth(raw, now())
        return max(0L, smoothed.toLong())
    }

    /** Un-smoothed estimate, exposed for tests and diagnostics. */
    fun rawRemainingMs(): Double {
        if (finished) return 0.0
        var total = 0.0
        val phase = currentPhase
        if (phase != null) {
            val fraction = if (currentUnits > 0.0) {
                (currentDone / currentUnits).coerceIn(0.0, 1.0)
            } else 0.0
            val priorRemaining = phasePredictedMs * (1.0 - fraction)
            val liveRate = liveRateMsPerUnit()
            total += if (fraction > 0.005 && liveRate > 0.0 && currentUnits > 0.0) {
                val liveRemaining = liveRate * (currentUnits - currentDone)
                val w = PRIOR_SHARE / (PRIOR_SHARE + rateCount)
                w * priorRemaining + (1.0 - w) * liveRemaining
            } else {
                priorRemaining
            }
        }
        for (plan in plans) {
            if (plan.phase == phase) continue
            if (completedPhases.contains(plan.phase)) continue
            total += plan.units * model.predictMsPerUnit(
                plan.phase, spec.signature, spec.signature.ramGb, spec.temperatureC
            ) * speedFactor
        }
        total += spec.tailMs * speedFactor
        // Setup already spent is no longer "remaining".
        val overhead = model.predictMsPerUnit(
            EtaPhase.OVERHEAD, spec.signature, spec.signature.ramGb, spec.temperatureC
        ) * speedFactor
        total += max(0.0, overhead - elapsedMs().toDouble())
        return if (total.isFinite() && total > 0.0) total else 0.0
    }

    /** Multiplier learned from phases that already completed in THIS run. */
    fun currentSpeedFactor(): Double = speedFactor

    fun elapsedMs(): Long = (now() - startedAtMs).coerceAtLeast(0L)

    /**
     * One-line post-mortem for logcat, e.g.
     * `predicted=12.3s actual=15.1s error=+22.8% speed=1.12 samples=3`.
     * This is the evidence trail used to tune the priors and to prove learning.
     */
    fun summaryLine(): String {
        val actual = elapsedMs().toDouble()
        val predicted = predictedTotalAtStartMs
        val error = if (actual > 0) (predicted - actual) / actual * 100.0 else 0.0
        return "predicted=${"%.1f".format(predicted / 1000.0)}s " +
                "actual=${"%.1f".format(actual / 1000.0)}s " +
                "error=${"%+.1f".format(error)}% " +
                "speed=${"%.2f".format(speedFactor)} " +
                "samples=$priorSamples"
    }

    /** Complete the job. Only a successful job trains the model. */
    fun finish(success: Boolean = true) {
        if (finished) return
        closeCurrentPhase()
        finished = true
        if (success) {
            // Everything the phases did not account for: writer/encoder setup, the
            // first-frame cost, and the gaps between phases. Without this a 300-frame
            // render measured 29.8 s while its phases summed to ~14 s, so the next
            // prediction was 24% short.
            val total = elapsedMs().toDouble()
            val overhead = total - recordedPhaseMs
            if (recordedPhaseCount > 0 && overhead > 100.0) {
                model.recordPhase(
                    EtaPhase.OVERHEAD, spec.signature, 1.0, overhead, sampleWeight
                )
            }
            model.recordJobCompleted()
            onCompleted?.invoke()
        }
    }

    /** Abandon without recording anything (cancel, failure, OOM). */
    fun abandon() {
        finished = true
    }

    /**
     * Update the work estimate of a phase that has not completed yet. Used when
     * MFSR learns how many frames it actually selected.
     */
    fun planUnits(phase: EtaPhase, units: Double) {
        if (finished) return
        val safe = EtaUnits.sanitize(units)
        if (safe <= 0.0) return
        if (completedPhases.contains(phase)) return
        updatePlanUnits(phase, safe)
        if (phase == currentPhase) {
            currentUnits = safe
            recomputePrediction()
        }
    }

    private fun planUnitsOf(phase: EtaPhase): Double =
        plans.firstOrNull { it.phase == phase }?.units ?: 0.0

    private fun updatePlanUnits(phase: EtaPhase, units: Double) {
        val index = plans.indexOfFirst { it.phase == phase }
        // The latest count wins: at phase start the caller knows the real number,
        // and MFSR refines the estimate after selection. Completed phases are
        // already recorded and never reach here.
        if (index >= 0) {
            plans[index] = plans[index].copy(units = units)
        } else {
            // Insert after the current position so "future" ordering stays sane.
            plans.add(EtaPhasePlan(phase, units))
        }
    }

    private fun recomputePrediction() {
        val phase = currentPhase ?: return
        phaseBaselineMs = currentUnits * model.predictMsPerUnit(
            phase, spec.signature, spec.signature.ramGb, spec.temperatureC
        )
        phasePredictedMs = phaseBaselineMs * speedFactor
    }

    private fun closeCurrentPhase() {
        val phase = currentPhase ?: return
        val duration = (now() - phaseStartMs).coerceAtLeast(0L).toDouble()
        currentPhase = null
        completedPhases.add(phase)
        if (currentUnits <= 0.0 || duration <= 0.0) return

        model.recordPhase(phase, spec.signature, currentUnits, duration, sampleWeight)
        recordedPhaseMs += duration
        recordedPhaseCount++

        // Only phases that are a meaningful share of the JOB TIME may move the
        // running speed factor. Units are a poor proxy: MFSR's proxy scan is ~20
        // units but 0.2 s, and letting it set the factor made the following align
        // phase (90% of the job) predict ~80% too long. The 1 s floor plus a 5%
        // time share excludes fixed-cost phases like "extract anchor" and "scan".
        val totalTime = predictedTotalAtStartMs.coerceAtLeast(1.0)
        if (currentUnits >= totalPlannedUnits * 0.05 &&
            phasePredictedMs >= 1000.0 &&
            phasePredictedMs / totalTime >= 0.05
        ) {
            val ratio = (duration / phasePredictedMs).coerceIn(0.35, 3.0)
            speedFactor = (speedFactor * (0.5 + 0.5 * ratio)).coerceIn(0.35, 3.0)
        }
    }

    private fun smooth(raw: Double, t: Long): Double {
        if (!raw.isFinite()) return 0.0
        if (displayMs < 0.0) {
            displayMs = raw
            lastSmoothMs = t
            return displayMs
        }
        val dt = (t - lastSmoothMs).coerceAtLeast(0L).toDouble()
        lastSmoothMs = t
        displayMs = if (raw <= displayMs) {
            // Falling fast is always safe: it is good news.
            displayMs + (raw - displayMs) * 0.6
        } else {
            // Rising is capped at MAX_RISE_PER_SECOND x wall clock. A genuine
            // slowdown still shows within seconds; a single bad sample cannot
            // double the ETA. No burst allowance: it would be spent on every
            // call and a 4 Hz update cadence would multiply it.
            val maxRise = MAX_RISE_PER_SECOND * dt
            min(raw, displayMs + maxRise)
        }
        val ceiling = max(1000.0, CEILING_FACTOR * max(predictedTotalAtStartMs, predictedTotalMs))
        displayMs = displayMs.coerceIn(0.0, ceiling)
        return displayMs
    }
}
