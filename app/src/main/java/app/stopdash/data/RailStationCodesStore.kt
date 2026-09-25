package app.stopdash.data

import android.content.Context
import android.util.Log
import app.stopdash.domain.RailStationCodes
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Loads the bundled National Rail station codes (`stations/crs_codes.json`, built from NaPTAN by
 * `scripts/build_crs_index.py`) once per process. Called on the request path, off the main thread.
 * Fails safe to [RailStationCodes.EMPTY]: a missing, corrupt or newer-format asset means no National
 * Rail times ("No data"), never a crash.
 */
object RailStationCodesStore {
    private const val ASSET = "stations/crs_codes.json"
    private const val CURRENT_VERSION = 1
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var cached: RailStationCodes? = null

    fun load(context: Context): RailStationCodes {
        cached?.let { return it }
        val codes = try {
            parse(context.assets.open(ASSET).bufferedReader().use { it.readText() }) { Log.w("StopDash.Rail", it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Static asset, no user data in the failure.
            Log.w("StopDash.Rail", "station codes load failed: ${e::class.simpleName}")
            RailStationCodes.EMPTY
        }
        cached = codes
        return codes
    }

    internal fun parse(text: String, warn: (String) -> Unit = {}): RailStationCodes {
        val file = json.decodeFromString<CodesFile>(text)
        if (file.version != CURRENT_VERSION) {
            warn("station codes version ${file.version} != $CURRENT_VERSION, ignoring")
            return RailStationCodes.EMPTY
        }
        return RailStationCodes(file.codes)
    }

    @Serializable
    private data class CodesFile(val version: Int = 0, val codes: Map<String, String> = emptyMap())
}
