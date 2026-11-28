package jp.noppy.lumamusic.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.core.Preferences as StoredPreferences
import androidx.datastore.preferences.preferencesDataStore
import jp.noppy.lumamusic.core.OutputMode
import jp.noppy.lumamusic.core.SmartShuffleCheckpoint
import jp.noppy.lumamusic.core.dsp.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import org.json.JSONArray
import org.json.JSONObject

private val Context.lumaPreferences by preferencesDataStore("luma")
data class Preferences(
    val mode: OutputMode = OutputMode.USB_HIGH_QUALITY,
    val keepAwake: Boolean = false,
    val darkStandby: Boolean = true,
    val clock: Boolean = true,
    val standbyLyrics: Boolean = false,
    val dimAfter: Boolean = true,
    val brightness: Float = .35f,
    val theme: String = "system",
    val reducedMotion: Boolean = false,
    val glass: Float = .65f,
    val glassQuality: String = "BALANCED",
)
data class PlaybackSnapshot(
    val uris: List<String>,
    val index: Int = 0,
    val position: Long = 0,
    val repeat: Int = 0,
    val shuffle: Boolean = false,
    val shuffleOrder: List<Int> = emptyList(),
    val shuffleCheckpoint: SmartShuffleCheckpoint? = null,
)

class SettingsRepository internal constructor(private val store: DataStore<StoredPreferences>) {
    constructor(context: Context) : this(context.lumaPreferences)

    val preferences = store.data.map { p -> Preferences(
        mode = runCatching { OutputMode.valueOf(p[stringPreferencesKey("mode")] ?: "USB_HIGH_QUALITY") }.getOrDefault(OutputMode.STANDARD),
        keepAwake = p[booleanPreferencesKey("awake")] ?: false,
        darkStandby = p[booleanPreferencesKey("night")] ?: true,
        clock = p[booleanPreferencesKey("clock")] ?: true,
        standbyLyrics = p[booleanPreferencesKey("standbyLyrics")] ?: false,
        dimAfter = p[booleanPreferencesKey("dim")] ?: true,
        brightness = p[floatPreferencesKey("brightness")] ?: .35f,
        theme = p[stringPreferencesKey("theme")] ?: "system",
        reducedMotion = p[booleanPreferencesKey("motion")] ?: false,
        glass = p[floatPreferencesKey("glass")] ?: .65f,
        glassQuality = p[stringPreferencesKey("glassQuality")] ?: "BALANCED",
    ) }
    val soundSettings = store.data.map { p -> runCatching {
        val j = JSONObject(p[stringPreferencesKey("sound")] ?: "{}")
        val b = j.optJSONArray("bands")
        val filters = j.optJSONObject("filters")
        DspConfig(
            mode = SoundMode.valueOf(j.optString("mode", "DIRECT")),
            eqEnabled = j.optBoolean("eq", true),
            bands = List(EQ_FREQUENCIES.size) { b?.optDouble(it, 0.0)?.toFloat() ?: 0f },
            preampDb = j.optDouble("preamp", 0.0).toFloat(),
            autoEnabled = j.optBoolean("auto"),
            normalizeEnabled = j.optBoolean("normalize"),
            normalizeMode = NormalizeMode.valueOf(j.optString("normalizeMode", "TRACK")),
            targetLufs = j.optDouble("target", -16.0).toFloat(),
            widthEnabled = j.optBoolean("widthEnabled"),
            width = j.optDouble("width", 1.0).toFloat(),
            preset = j.optString("preset", "フラット"),
            filters = DspFilters(
                highPassHz = filters?.optDouble("highPassHz", 0.0)?.toFloat() ?: 0f,
                lowPassHz = filters?.optDouble("lowPassHz", 0.0)?.toFloat() ?: 0f,
                lowShelfDb = filters?.optDouble("lowShelfDb", 0.0)?.toFloat() ?: 0f,
                highShelfDb = filters?.optDouble("highShelfDb", 0.0)?.toFloat() ?: 0f,
            ),
            comparisonTrimDb = j.optDouble("comparisonTrimDb", 0.0).toFloat(),
        ).safe()
    }.getOrDefault(DspConfig()) }.distinctUntilChanged()

    suspend fun saveSound(value: DspConfig) {
        val v = value.safe()
        val filters = JSONObject()
            .put("highPassHz", v.filters.highPassHz)
            .put("lowPassHz", v.filters.lowPassHz)
            .put("lowShelfDb", v.filters.lowShelfDb)
            .put("highShelfDb", v.filters.highShelfDb)
        val payload = JSONObject()
            .put("mode", v.mode.name).put("eq", v.eqEnabled).put("bands", JSONArray(v.bands)).put("preamp", v.preampDb)
            .put("auto", v.autoEnabled).put("normalize", v.normalizeEnabled).put("normalizeMode", v.normalizeMode.name)
            .put("target", v.targetLufs).put("widthEnabled", v.widthEnabled).put("width", v.width).put("preset", v.preset)
            .put("filters", filters).put("comparisonTrimDb", v.comparisonTrimDb)
        store.edit { it[stringPreferencesKey("sound")] = payload.toString() }
    }

    val snapshot = store.data.map { p ->
        runCatching {
            val json = JSONObject(p[stringPreferencesKey("queue")] ?: "{}")
            val queue = json.optJSONArray("uris") ?: JSONArray()
            val order = json.optJSONArray("shuffleOrder") ?: JSONArray()
            val checkpoint = json.optJSONObject("smartShuffle")
            PlaybackSnapshot(
                (0 until queue.length()).map(queue::getString), json.optInt("index"), json.optLong("position"),
                json.optInt("repeat"), json.optBoolean("shuffle"),
                (0 until order.length()).map { order.optInt(it, -1) },
                checkpoint?.let {
                    SmartShuffleCheckpoint(
                        visited = it.optJSONArray("visited").indices(),
                        inactiveSinceMillis = it.optLong("inactiveSince", -1).takeIf { value -> value >= 0 },
                        roundReady = it.optBoolean("roundReady"),
                        previousRoundTail = it.optInt("roundTail", -1).takeIf { value -> value >= 0 },
                        playNext = it.optJSONArray("playNext").indices(),
                    )
                },
            )
        }.getOrDefault(PlaybackSnapshot(emptyList()))
    }
    suspend fun saveSnapshot(value: PlaybackSnapshot) {
        val payload = JSONObject().put("uris", JSONArray(value.uris)).put("index", value.index)
            .put("position", value.position).put("repeat", value.repeat).put("shuffle", value.shuffle)
            .put("shuffleOrder", JSONArray(value.shuffleOrder))
        value.shuffleCheckpoint?.let {
            payload.put("smartShuffle", JSONObject()
                .put("visited", JSONArray(it.visited))
                .put("inactiveSince", it.inactiveSinceMillis ?: -1L)
                .put("roundReady", it.roundReady)
                .put("roundTail", it.previousRoundTail ?: -1)
                .put("playNext", JSONArray(it.playNext)))
        }
        store.edit { it[stringPreferencesKey("queue")] = payload.toString() }
    }
    suspend fun mode(mode: OutputMode) { store.edit { it[stringPreferencesKey("mode")] = mode.name } }
    suspend fun boolean(key: String, value: Boolean) { store.edit { it[booleanPreferencesKey(key)] = value } }
    suspend fun number(key: String, value: Float) { store.edit { it[floatPreferencesKey(key)] = value } }
    suspend fun text(key: String, value: String) { store.edit { it[stringPreferencesKey(key)] = value } }
}

private fun JSONArray?.indices(): List<Int> = this?.let { array ->
    (0 until array.length()).mapNotNull { index -> array.optInt(index, -1).takeIf { it >= 0 } }
}.orEmpty()
