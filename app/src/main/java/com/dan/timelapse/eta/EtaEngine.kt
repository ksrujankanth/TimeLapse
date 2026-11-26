package com.dan.timelapse.eta

/**
 * Storage for the learned model. Implemented by `EtaStore` on Android and by a
 * plain in-memory map in the JVM tests.
 */
interface EtaPersistence {
    fun load(): EtaSnapshot?
    fun save(snapshot: EtaSnapshot)
}

/**
 * Owns one [EtaModel] and hands out [EtaJob]s.
 *
 * Deliberately tiny: all of the interesting logic (statistics, backoff,
 * blending, smoothing) lives in [EtaModel] and [EtaJob], which are pure Kotlin
 * and covered by JVM tests. This class only adds lazy loading and persistence.
 */
class EtaEngine(
    private val persistence: EtaPersistence? = null,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val model = EtaModel(now)
    private val lock = Any()
    private var loaded = false

    /**
     * Jobs started in THIS process. The first one is recorded with a lower
     * weight: codec/JIT/OpenCV warm-up is a one-time cost of the process, not a
     * property of the workload.
     */
    private var jobsThisProcess = 0

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            try {
                persistence?.load()?.let { model.load(it) }
            } catch (_: Exception) {
                // A corrupt model must never break a render; start from priors.
            }
            loaded = true
        }
    }

    fun startJob(spec: EtaJobSpec): EtaJob {
        ensureLoaded()
        val weight = synchronized(lock) {
            val w = if (jobsThisProcess == 0) COLD_START_WEIGHT else 1.0
            jobsThisProcess++
            w
        }
        return EtaJob(model, spec, now, { persist() }, weight)
    }

    companion object {
        /**
         * Weight of the first job in a process.
         *
         * A 14-frame clip measured 710 ms cold and 474 ms warm on a Pixel 4a —
         * the difference is MediaCodec + class loading, not the clip. Counting
         * the cold run fully made every later estimate ~45% pessimistic for
         * short jobs. 0.35 keeps a useful first data point without letting the
         * warm-up dominate the mean.
         */
        const val COLD_START_WEIGHT = 0.35
    }

    /** Write the model to storage. Called once per successful job. */
    fun persist() {
        synchronized(lock) {
            try {
                persistence?.save(model.snapshot())
            } catch (_: Exception) {
                // Persistence is an optimisation, never a correctness requirement.
            }
        }
    }

    fun snapshot(): EtaSnapshot {
        ensureLoaded()
        return model.snapshot()
    }

    fun jobsCompleted(): Int {
        ensureLoaded()
        return model.jobsCompleted
    }

    fun clear() {
        synchronized(lock) {
            model.clear()
            loaded = true
            try {
                persistence?.save(model.snapshot())
            } catch (_: Exception) {
            }
        }
    }
}
