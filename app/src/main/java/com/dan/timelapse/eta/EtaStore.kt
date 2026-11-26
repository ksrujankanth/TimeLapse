package com.dan.timelapse.eta

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.Json

/**
 * SharedPreferences-backed model storage.
 *
 * The snapshot is a single JSON string so a partially-written model can never be
 * half-applied: either it decodes or it does not, and a decode failure silently
 * falls back to the priors.
 */
class EtaStore(context: Context) : EtaPersistence {

    companion object {
        private const val TAG = "EtaEngine"
        const val PREF_NAME = "eta_model_v2"
        const val KEY_MODEL = "snapshot_json"
    }

    private val prefs = context.applicationContext
        .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun load(): EtaSnapshot? {
        val raw = prefs.getString(KEY_MODEL, null) ?: return null
        return try {
            json.decodeFromString(EtaSnapshot.serializer(), raw)
        } catch (e: Exception) {
            Log.w(TAG, "Learned model could not be decoded; starting from priors", e)
            null
        }
    }

    override fun save(snapshot: EtaSnapshot) {
        val raw = json.encodeToString(EtaSnapshot.serializer(), snapshot)
        // commit() rather than apply(): the job that just finished is exactly the
        // data we cannot afford to lose if the process is killed next (the user
        // often closes the app right after a long render).
        prefs.edit().putString(KEY_MODEL, raw).commit()
    }
}

/**
 * Process-wide access point. Creating the engine reads a few kilobytes of JSON,
 * so it is memoised; [warmUp] primes it from `Application.onCreate` off the main
 * thread so the first render never pays for it.
 */
object Eta {

    @Volatile
    private var engine: EtaEngine? = null

    fun engine(context: Context): EtaEngine {
        engine?.let { return it }
        synchronized(this) {
            engine?.let { return it }
            val created = EtaEngine(EtaStore(context))
            engine = created
            return created
        }
    }

    /** Called once from the Application to hide the load latency. */
    fun warmUp(context: Context) {
        try {
            engine(context).jobsCompleted()
        } catch (e: Exception) {
            Log.w("EtaEngine", "Model warm-up failed", e)
        }
    }

    fun startJob(context: Context, spec: EtaJobSpec): EtaJob =
        engine(context).startJob(spec)

    fun clear(context: Context) {
        engine(context).clear()
    }
}
