package com.ewslereng.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class SensorLatest(
    val id: String,
    val t: String? = null,
    val loc: String? = null,
    val pitch: Double? = null,
    val roll: Double? = null,
    val pergeseran: Double? = null,
    val status: String = "UNKNOWN",
    val sinyal: Double? = null
)

data class PowerLatest(
    val timestamp: String? = null,
    val teganganPanel: Double = 0.0,
    val arusPanel: Double = 0.0,
    val dayaPanel: Double = 0.0,
    val teganganBaterai: Double = 0.0,
    val arusBaterai: Double = 0.0,
    val dayaBaterai: Double = 0.0,
    val kapasitasBaterai: Double = 0.0,
    val statusTerburuk: String = "UNKNOWN",
    val sirine: String = "OFF",
    val sinyalWiFi: Double = 0.0
)

data class EwsSnapshot(
    val sensors: Map<String, SensorLatest>,
    val power: PowerLatest? = null
)

sealed class FetchResult {
    data class Success(val data: EwsSnapshot) : FetchResult()
    data class Error(val message: String) : FetchResult()
}

object SensorRepository {

    private fun httpGet(urlString: String): String {
        var conn: HttpURLConnection? = null

        try {
            conn =
                URL(urlString)
                    .openConnection() as HttpURLConnection

            conn.requestMethod =
                "GET"

            conn.useCaches =
                false

            conn.setRequestProperty(
                "Cache-Control",
                "no-cache, no-store"
            )

            conn.connectTimeout =
                15_000

            conn.readTimeout =
                15_000

            conn.instanceFollowRedirects =
                true

            val code =
                conn.responseCode

            if (code != 200) {
                throw IllegalStateException(
                    "HTTP $code"
                )
            }

            return conn
                .inputStream
                .bufferedReader()
                .use {
                    it.readText()
                }
        }
        finally {
            conn?.disconnect()
        }
    }

    suspend fun fetchLatest(): FetchResult =
        withContext(Dispatchers.IO) {
            try {
                /*
                 * Jalur pertama mengikuti buku awal: ?api=data.
                 * Parser mendukung dua format:
                 * 1. format lama: sensors = object S1..S6 dengan latest
                 * 2. format dashboard baru: sensors = array
                 *
                 * Jika endpoint data tidak tersedia, otomatis fallback ke
                 * ?api=status + ?api=power, jadi aplikasi tetap jalan.
                 */
                val fromData =
                    runCatching {
                        fetchFromDataEndpoint()
                    }
                    .getOrNull()

                if (fromData != null) {
                    return@withContext
                        FetchResult.Success(fromData)
                }

                val fallback =
                    fetchFromStatusAndPower()

                FetchResult.Success(fallback)
            }
            catch (e: Exception) {
                FetchResult.Error(
                    e.message
                        ?: "Gagal mengambil data"
                )
            }
        }

    private fun fetchFromDataEndpoint(): EwsSnapshot? {
        val body =
            httpGet(
                Config.APPS_SCRIPT_URL +
                    "?api=data&_=" +
                    System.currentTimeMillis()
            )

        val root =
            JSONObject(body)

        val sensorsNode =
            root.opt("sensors")
                ?: return null

        val map =
            mutableMapOf<String, SensorLatest>()

        when (sensorsNode) {

            is JSONObject -> {
                // Format buku awal.
                for (id in Config.SENSOR_IDS) {
                    val entry =
                        sensorsNode.optJSONObject(id)
                            ?: continue

                    val latest =
                        entry.optJSONObject("latest")
                            ?: continue

                    map[id] =
                        SensorLatest(
                            id = id,
                            t = latest.optString("t", null),
                            loc = latest.optString(
                                "loc",
                                Config.SENSOR_LOKASI[id]
                            ),
                            pitch = latest.optNullableDouble("sudutX"),
                            roll = latest.optNullableDouble("sudutY"),
                            pergeseran = latest.optNullableDouble("pergeseran"),
                            status = normalizeStatus(
                                latest.optString(
                                    "status",
                                    "UNKNOWN"
                                )
                            ),
                            sinyal = latest.optNullableDouble("sinyal")
                        )
                }
            }

            is JSONArray -> {
                // Format dashboard baru.
                for (i in 0 until sensorsNode.length()) {
                    val s =
                        sensorsNode.optJSONObject(i)
                            ?: continue

                    val id =
                        s.optString("id")
                            .uppercase()

                    if (
                        id !in Config.SENSOR_IDS
                    ) {
                        continue
                    }

                    map[id] =
                        SensorLatest(
                            id = id,
                            t = s.optString("timestamp", null),
                            loc = s.optString(
                                "lokasi",
                                Config.SENSOR_LOKASI[id]
                            ),
                            pitch = s.optNullableDouble("pitch"),
                            roll = s.optNullableDouble("roll"),
                            pergeseran = s.optNullableDouble("pergeseran"),
                            status = normalizeStatus(
                                s.optString(
                                    "status",
                                    "UNKNOWN"
                                )
                            ),
                            sinyal = s.optNullableDouble("sinyal")
                        )
                }
            }

            else -> return null
        }

        if (map.isEmpty()) {
            return null
        }

        var power =
            parsePowerObject(
                root.optJSONObject("power")
            )

        // Backend buku awal ?api=data hanya berisi sensor.
        // Ambil Pos Utama dari endpoint power jika tersedia.
        if (power == null) {
            power =
                runCatching {
                    val powerBody =
                        httpGet(
                            Config.APPS_SCRIPT_URL +
                                "?api=power&_=" +
                                System.currentTimeMillis()
                        )

                    val powerRoot =
                        JSONObject(powerBody)

                    parsePowerObject(
                        powerRoot.optJSONObject("data")
                    )
                }
                .getOrNull()
        }

        return EwsSnapshot(
            sensors = fillMissing(map),
            power = power
        )
    }

    private fun fetchFromStatusAndPower(): EwsSnapshot {
        val statusBody =
            httpGet(
                Config.APPS_SCRIPT_URL +
                    "?api=status&_=" +
                    System.currentTimeMillis()
            )

        val statusRoot =
            JSONObject(statusBody)

        val statusObject =
            statusRoot.optJSONObject("sensors")
                ?: JSONObject()

        val map =
            mutableMapOf<String, SensorLatest>()

        for (id in Config.SENSOR_IDS) {
            map[id] =
                SensorLatest(
                    id = id,
                    loc = Config.SENSOR_LOKASI[id],
                    status = normalizeStatus(
                        statusObject.optString(
                            id,
                            "UNKNOWN"
                        )
                    )
                )
        }

        val power =
            runCatching {
                val powerBody =
                    httpGet(
                        Config.APPS_SCRIPT_URL +
                            "?api=power&_=" +
                            System.currentTimeMillis()
                    )

                val root =
                    JSONObject(powerBody)

                parsePowerObject(
                    root.optJSONObject("data")
                )
            }
            .getOrNull()

        return EwsSnapshot(
            sensors = map,
            power = power
        )
    }

    private fun fillMissing(
        source: Map<String, SensorLatest>
    ): Map<String, SensorLatest> {
        val result =
            linkedMapOf<String, SensorLatest>()

        for (id in Config.SENSOR_IDS) {
            result[id] =
                source[id]
                    ?: SensorLatest(
                        id = id,
                        loc = Config.SENSOR_LOKASI[id],
                        status = "UNKNOWN"
                    )
        }

        return result
    }

    private fun parsePowerObject(
        obj: JSONObject?
    ): PowerLatest? {
        if (obj == null) {
            return null
        }

        return PowerLatest(
            timestamp = obj.optString("timestamp", null),
            teganganPanel = obj.optDouble("tegangan_panel", 0.0),
            arusPanel = obj.optDouble("arus_panel", 0.0),
            dayaPanel = obj.optDouble("daya_panel", 0.0),
            teganganBaterai = obj.optDouble("tegangan_baterai", 0.0),
            arusBaterai = obj.optDouble("arus_baterai", 0.0),
            dayaBaterai = obj.optDouble("daya_baterai", 0.0),
            kapasitasBaterai = obj.optDouble("kapasitas_baterai", 0.0),
            statusTerburuk = normalizeStatus(
                obj.optString(
                    "status_terburuk",
                    "UNKNOWN"
                )
            ),
            sirine = obj.optString("sirine", "OFF"),
            sinyalWiFi = obj.optDouble("sinyal_wifi", 0.0)
        )
    }

    fun worstStatus(
        data: Map<String, SensorLatest>
    ): String {
        var worst =
            "UNKNOWN"

        var level =
            -2

        for (s in data.values) {
            val sev =
                severityOf(s.status)

            if (sev > level) {
                level =
                    sev

                worst =
                    s.status
            }
        }

        return worst
    }

    private fun JSONObject.optNullableDouble(
        key: String
    ): Double? {
        if (
            !has(key) ||
            isNull(key)
        ) {
            return null
        }

        val value =
            opt(key)

        return when (value) {
            is Number ->
                value.toDouble()

            is String ->
                value
                    .replace(",", ".")
                    .toDoubleOrNull()

            else -> null
        }
    }
}
