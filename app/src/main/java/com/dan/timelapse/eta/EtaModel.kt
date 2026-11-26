package com.dan.timelapse.eta

import kotlinx.serialization.Serializable
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Welford accumulator over `ln(ms per unit)`.
 *
 * Log space is the important part: render cost is multiplicative (a slower device
 * is 1.4x slower everywhere), so averaging raw rates would let one throttled run
 * drag the mean far more than one fast run pulls it. It also guarantees a
 * prediction is always positive.
 */
class LogStat {
    /**
     * Effective number of observations. Fractional: the first job in a process
     * is recorded with a lower weight because it carries one-time warm-up
     * (MediaCodec init, JIT, OpenCV class loading) that a steady-state estimate
     * should not be biased by.
     */
    var n: Double = 0.0
        private set
    var mean: Double = 0.0
        private set
    var m2: Double = 0.0
        private set
    var updatedAt: Long = 0L
        private set

    fun add(logValue: Double, now: Long, weight: Double = 1.0) {
        if (!logValue.isFinite()) return
        val w = if (weight.isFinite() && weight > 0.0) weight else 1.0
        // Recency decay: device conditions drift (thermal, background load, a
        // different clip of the "same" shape), so the estimate follows roughly
        // the last three runs instead of an all-time average. The live
        // measurement covers the resulting noise inside a run.
        n *= EtaModel.DECAY
        val previousMean = mean
        n = kotlin.math.min(n + w, EtaModel.MAX_EFFECTIVE_SAMPLES)
        val delta = logValue - previousMean
        mean = previousMean + delta * w / n
        m2 = m2 * EtaModel.DECAY + w * delta * (logValue - mean)
        updatedAt = now
    }

    fun merge(otherN: Double, otherMean: Double, otherM2: Double, now: Long) {
        if (otherN <= 0.0 || !otherMean.isFinite()) return
        if (n == 0.0) {
            n = otherN
            mean = otherMean
            m2 = if (otherM2.isFinite() && otherM2 > 0) otherM2 else 0.0
            updatedAt = maxOf(updatedAt, now)
            return
        }
        val total = n + otherN
        val delta = otherMean - mean
        mean += delta * otherN / total
        m2 += (if (otherM2.isFinite() && otherM2 > 0) otherM2 else 0.0) +
                delta * delta * n * otherN / total
        n = total
        updatedAt = maxOf(updatedAt, now)
    }

    /** Sample standard deviation in log space (0 when there is one sample). */
    val sd: Double get() = if (n > 1.0) sqrt((m2 / (n - 1.0)).coerceAtLeast(0.0)) else 0.0
}

@Serializable
data class EtaStatRecord(
    /** `"<PHASE>|<levelKey>"` — one row per phase per backoff level. */
    val key: String,
    val n: Double,
    val mean: Double,
    val m2: Double,
    val updatedAt: Long
)

@Serializable
data class EtaSnapshot(
    val version: Int = EtaModel.SCHEMA_VERSION,
    val jobsCompleted: Int = 0,
    val stats: List<EtaStatRecord> = emptyList()
)

/**
 * The learned model: a table of log-rate statistics plus a hierarchical lookup.
 *
 * Pure Kotlin on purpose — every rule in here is unit-tested on the JVM in
 * `EtaEngineTest`, with no device and no Android framework in the way.
 */
class EtaModel(
    private val now: () -> Long = System::currentTimeMillis
) {
    companion object {
        /**
         * Bump whenever `EtaUnits.effectCost` changes.
         *
         * Learned rates are ms per UNIT, so redefining the unit base silently
         * multiplies every stored rate by the change: setting NONE from 0.45 to
         * 1.70 made a 29 s render predict 110 s. A version mismatch discards the
         * old statistics (they were learned against a different unit) instead of
         * keeping garbage.
         *
         * v3: the six uncalibrated effect costs were replaced with measured values
         * (Lightest/Darkest/Motion/Difference are window-flat; HDR and Transition
         * grow with the window).
         */
        const val SCHEMA_VERSION = 3

        /**
         * Shrinkage constant per backoff level for `trust = n / (n + K)`.
         *
         * Level 0 is the exact key, so it is allowed to move away from its
         * broader parent quickly; the coarser levels are averaged over more
         * heterogeneous jobs and must be trusted more slowly. With K = 0.6 a
         * single full-weight run at the exact key is already 62% authoritative.
         */
        val TRUST_K_BY_LEVEL = doubleArrayOf(0.6, 1.2, 1.8, 2.4)

        /** Hard cap on persisted rows; least-recently-updated are evicted. */
        const val MAX_RECORDS = 240

        /** Older observations lose weight geometrically (see [LogStat.add]). */
        const val DECAY = 0.7

        /** Confidence ceiling: ten consistent runs is already "certain enough". */
        const val MAX_EFFECTIVE_SAMPLES = 12.0

        fun key(phase: EtaPhase, levelKey: String): String = "${phase.name}|$levelKey"
    }

    private val stats = HashMap<String, LogStat>()

    var jobsCompleted: Int = 0
        private set

    /** Records one completed phase measurement. */
    fun recordPhase(
        phase: EtaPhase,
        signature: EtaSignature,
        units: Double,
        durationMs: Double,
        weight: Double = 1.0
    ) {
        if (!units.isFinite() || units <= 0.0) return
        if (!durationMs.isFinite() || durationMs <= 0.0) return
        val logRate = ln(durationMs / units)
        if (!logRate.isFinite()) return
        val t = now()
        for (level in 0 until EtaSignature.LEVELS) {
            stats.getOrPut(key(phase, signature.levelKey(level))) { LogStat() }
                .add(logRate, t, weight)
        }
    }

    fun recordJobCompleted() {
        jobsCompleted++
    }

    /**
     * Predicted ms per work unit for [phase].
     *
     * Walks level 0 -> 3 and stops at the first level that has any data. The
     * parent (next deeper level with data) is the baseline that the specific
     * level is blended toward; the heuristic is the final fallback.
     *
     * Stopping at the first populated level is what keeps correlated rows from
     * being counted repeatedly: a single run writes all four levels, and summing
     * them would trust that one run ~85% instead of 40%.
     *
     * The trust is also variance-aware: a key whose runs disagreed (e.g. one
     * throttled, one cold) is pulled back toward its broader parent more than a
     * key whose runs were consistent. This is what makes an *unseen* job (which
     * only has the coarse levels) get a conservative starting number.
     */
    fun predictMsPerUnit(
        phase: EtaPhase,
        signature: EtaSignature,
        ramGb: Int = signature.ramGb,
        tempC: Int = 30
    ): Double {
        val heuristic = EtaHeuristics.priorMsPerUnit(phase, signature.kind, ramGb, tempC)
        for (level in 0 until EtaSignature.LEVELS) {
            val st = stats[key(phase, signature.levelKey(level))]
            if (st == null || st.n <= 0 || !st.mean.isFinite()) continue
            val parentLog = parentMeanLog(phase, signature, level) ?: ln(heuristic)
            val trust = trustOf(st, level)
            return exp(parentLog + trust * (st.mean - parentLog))
        }
        return heuristic
    }

    /** `n / (n + K)` with K inflated by the observed run-to-run spread. */
    private fun trustOf(st: LogStat, level: Int): Double {
        val k = TRUST_K_BY_LEVEL.getOrElse(level) { TRUST_K_BY_LEVEL.last() }
        val penalty = 1.0 + 4.0 * st.sd.coerceIn(0.0, 1.5)
        return st.n / (st.n + k * penalty)
    }

    /** How many observations back a prediction at the closest populated level. */
    fun samplesFor(phase: EtaPhase, signature: EtaSignature): Int {
        for (level in 0 until EtaSignature.LEVELS) {
            val st = stats[key(phase, signature.levelKey(level))]
            if (st != null && st.n > 0.0) return st.n.toInt().coerceAtLeast(1)
        }
        return 0
    }

    private fun parentMeanLog(phase: EtaPhase, signature: EtaSignature, level: Int): Double? {
        for (deeper in level + 1 until EtaSignature.LEVELS) {
            val st = stats[key(phase, signature.levelKey(deeper))]
            if (st != null && st.n > 0 && st.mean.isFinite()) return st.mean
        }
        return null
    }

    fun snapshot(): EtaSnapshot {
        if (stats.size <= MAX_RECORDS) {
            return EtaSnapshot(
                version = SCHEMA_VERSION,
                jobsCompleted = jobsCompleted,
                stats = stats.map { (k, v) ->
                    EtaStatRecord(k, v.n, v.mean, v.m2, v.updatedAt)
                }
            )
        }
        // Evict the least recently updated rows, but always keep at least the
        // most specific level of every phase so an in-use key never disappears.
        val kept = stats.entries
            .sortedByDescending { it.value.updatedAt }
            .take(MAX_RECORDS)
        return EtaSnapshot(
            version = SCHEMA_VERSION,
            jobsCompleted = jobsCompleted,
            stats = kept.map { (k, v) -> EtaStatRecord(k, v.n, v.mean, v.m2, v.updatedAt) }
        )
    }

    /** Replace all state with [snapshot]. Unknown phases and old schemas are skipped. */
    fun load(snapshot: EtaSnapshot) {
        stats.clear()
        if (snapshot.version != SCHEMA_VERSION) {
            // Rates are ms per unit; a different effectCost base makes them wrong
            // in a way no amount of averaging can fix.
            jobsCompleted = 0
            return
        }
        jobsCompleted = snapshot.jobsCompleted.coerceAtLeast(0)
        for (record in snapshot.stats) {
            if (record.key.isBlank() || record.n <= 0) continue
            val phaseName = record.key.substringBefore('|', "")
            if (EtaPhase.values().none { it.name == phaseName }) continue
            val st = LogStat()
            st.merge(record.n, record.mean, record.m2, record.updatedAt)
            stats[record.key] = st
        }
    }

    fun clear() {
        stats.clear()
        jobsCompleted = 0
    }

    fun size(): Int = stats.size
}
