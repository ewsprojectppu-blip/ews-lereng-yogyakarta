package com.ewslereng.app

object Config {

    // URL Web App EWS yang sedang dipakai oleh ESP32/dashboard.
    const val APPS_SCRIPT_URL =
        "https://script.google.com/macros/s/AKfycbxENcSAzoCpD2N4ENn_c_Mo36Oxlk51-5pJXKp3aypRBF7F9O6S9XJlifPU62eWGJU0/exec"

    val SENSOR_IDS =
        listOf("S1", "S2", "S3", "S4", "S5", "S6")

    val SENSOR_LOKASI =
        mapOf(
            "S1" to "5 m dari Pos Utama",
            "S2" to "10 m dari Pos Utama",
            "S3" to "15 m dari Pos Utama",
            "S4" to "20 m dari Pos Utama",
            "S5" to "25 m dari Pos Utama",
            "S6" to "30 m dari Pos Utama"
        )

    // Background monitor sesuai konsep buku awal.
    const val POLL_INTERVAL_SERVICE_MS =
        60_000L

    // Saat aplikasi sedang dibuka.
    const val POLL_INTERVAL_UI_MS =
        30_000L

    // 1 = WASPADA ikut membunyikan alarm.
    // Jika nanti ingin sirine HP hanya SIAGA ke atas, ubah menjadi 2.
    const val ALARM_MIN_SEVERITY =
        1
}

fun normalizeStatus(status: String?): String =
    when (status?.trim()?.uppercase()) {
        "AMAN", "NORMAL", "SAFE" -> "AMAN"
        "WASPADA", "WARNING" -> "WASPADA"
        "SIAGA", "ALERT" -> "SIAGA"
        "AWAS" -> "AWAS"
        "BAHAYA", "DANGER" -> "BAHAYA"
        else -> "UNKNOWN"
    }

fun severityOf(status: String?): Int =
    when (normalizeStatus(status)) {
        "BAHAYA" -> 4
        "AWAS" -> 3
        "SIAGA" -> 2
        "WASPADA" -> 1
        "AMAN" -> 0
        else -> -1
    }

fun labelOf(status: String?): String =
    normalizeStatus(status)
