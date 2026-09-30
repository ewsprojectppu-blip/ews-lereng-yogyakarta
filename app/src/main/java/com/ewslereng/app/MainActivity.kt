package com.ewslereng.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.ewslereng.app.databinding.ActivityMainBinding
import com.ewslereng.app.databinding.ItemSensorBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity :
    AppCompatActivity() {

    private lateinit var binding:
        ActivityMainBinding

    private var uiPollJob:
        Job? =
        null

    private val sensorViews =
        mutableMapOf<String, ItemSensorBinding>()

    private val dataUpdatedReceiver =
        object :
            BroadcastReceiver() {

            override fun onReceive(
                context: Context,
                intent: Intent
            ) {
                refreshNow()
            }
        }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(
            savedInstanceState
        )

        binding =
            ActivityMainBinding.inflate(
                layoutInflater
            )

        setContentView(
            binding.root
        )

        buildSensorCards()

        requestNotificationPermissionIfNeeded()

        startMonitoringService()

        requestIgnoreBatteryOptimizations()

        binding
            .btnRefresh
            .setOnClickListener {
                refreshNow()

                val checkIntent =
                    Intent(
                        this,
                        MonitoringService::class.java
                    )
                        .setAction(
                            MonitoringService.ACTION_CHECK_NOW
                        )

                ContextCompat
                    .startForegroundService(
                        this,
                        checkIntent
                    )
            }

        binding
            .btnSilence
            .setOnClickListener {
                val intent =
                    Intent(
                        this,
                        MonitoringService::class.java
                    )
                        .setAction(
                            MonitoringService.ACTION_SILENCE
                        )

                ContextCompat
                    .startForegroundService(
                        this,
                        intent
                    )
            }
    }

    override fun onStart() {
        super.onStart()

        LocalBroadcastManager
            .getInstance(
                this
            )
            .registerReceiver(
                dataUpdatedReceiver,
                IntentFilter(
                    MonitoringService.ACTION_DATA_UPDATED
                )
            )

        uiPollJob =
            lifecycleScope.launch {
                while (isActive) {
                    refreshNow()

                    delay(
                        Config.POLL_INTERVAL_UI_MS
                    )
                }
            }
    }

    override fun onStop() {
        uiPollJob?.cancel()

        LocalBroadcastManager
            .getInstance(
                this
            )
            .unregisterReceiver(
                dataUpdatedReceiver
            )

        super.onStop()
    }

    private fun buildSensorCards() {
        binding
            .containerSensors
            .removeAllViews()

        sensorViews.clear()

        for (
            id in Config.SENSOR_IDS
        ) {
            val itemBinding =
                ItemSensorBinding.inflate(
                    LayoutInflater.from(
                        this
                    ),
                    binding.containerSensors,
                    false
                )

            itemBinding
                .tvSensorLoc
                .text =
                "$id · ${Config.SENSOR_LOKASI[id]}"

            binding
                .containerSensors
                .addView(
                    itemBinding.root
                )

            sensorViews[id] =
                itemBinding
        }
    }

    private fun refreshNow() {
        lifecycleScope.launch {
            when (
                val result =
                    SensorRepository.fetchLatest()
            ) {
                is FetchResult.Success ->
                    renderData(
                        result.data
                    )

                is FetchResult.Error -> {
                    binding
                        .tvStatusDesc
                        .text =
                        "Gagal mengambil data: ${result.message}"
                }
            }
        }
    }

    private fun renderData(
        snapshot: EwsSnapshot
    ) {
        val data =
            snapshot.sensors

        val worst =
            SensorRepository
                .worstStatus(
                    data
                )

        binding
            .tvStatusLabel
            .text =
            labelOf(
                worst
            )

        binding
            .tvStatusLabel
            .setBackgroundColor(
                colorFor(
                    worst
                )
            )

        binding
            .tvStatusDesc
            .text =
            descriptionFor(
                worst
            )

        val time =
            SimpleDateFormat(
                "HH:mm:ss",
                Locale(
                    "id",
                    "ID"
                )
            )
                .format(
                    Date()
                )

        binding
            .tvLastUpdate
            .text =
            "Update aplikasi: $time"

        for (
            id in Config.SENSOR_IDS
        ) {
            val view =
                sensorViews[id]
                    ?: continue

            val sensor =
                data[id]

            if (
                sensor == null
            ) {
                view
                    .tvSensorStatus
                    .text =
                    "UNKNOWN"

                view
                    .tvSensorStatus
                    .setBackgroundColor(
                        colorFor(
                            "UNKNOWN"
                        )
                    )

                view
                    .tvSensorDetail
                    .text =
                    "Belum ada data"
            }
            else {
                val status =
                    normalizeStatus(
                        sensor.status
                    )

                view
                    .tvSensorStatus
                    .text =
                    status

                view
                    .tvSensorStatus
                    .setBackgroundColor(
                        colorFor(
                            status
                        )
                    )

                view
                    .tvSensorStatus
                    .setTextColor(
                        Color.WHITE
                    )

                view
                    .tvSensorDetail
                    .text =
                    sensorDetail(
                        sensor
                    )
            }
        }

        renderPower(
            snapshot.power
        )
    }

    private fun sensorDetail(
        s: SensorLatest
    ): String {
        val parts =
            mutableListOf<String>()

        if (
            s.pergeseran !=
            null
        ) {
            parts +=
                "Pergeseran %.2f°"
                    .format(
                        Locale.US,
                        s.pergeseran
                    )
        }

        if (
            s.pitch != null
        ) {
            parts +=
                "Pitch %.2f°"
                    .format(
                        Locale.US,
                        s.pitch
                    )
        }

        if (
            s.roll != null
        ) {
            parts +=
                "Roll %.2f°"
                    .format(
                        Locale.US,
                        s.roll
                    )
        }

        if (
            s.sinyal != null
        ) {
            parts +=
                "Sinyal %.0f dBm"
                    .format(
                        Locale.US,
                        s.sinyal
                    )
        }

        if (
            !s.t.isNullOrBlank()
        ) {
            parts +=
                "Data ${s.t}"
        }

        if (
            parts.isEmpty()
        ) {
            return "Status diterima dari backend"
        }

        return parts.joinToString(
            " · "
        )
    }

    private fun renderPower(
        p: PowerLatest?
    ) {
        binding
            .tvPower
            .text =
            if (
                p == null
            ) {
                "Data Pos Utama belum tersedia."
            }
            else {
                """
                Panel    : %.2f V | %.3f A | %.2f W
                Baterai  : %.2f V | %.3f A | %.2f W
                Kapasitas: %.0f %%
                Status   : %s
                Sirine   : %s
                WiFi     : %.0f dBm
                """.trimIndent()
                    .format(
                        Locale.US,
                        p.teganganPanel,
                        p.arusPanel,
                        p.dayaPanel,
                        p.teganganBaterai,
                        p.arusBaterai,
                        p.dayaBaterai,
                        p.kapasitasBaterai,
                        p.statusTerburuk,
                        p.sirine,
                        p.sinyalWiFi
                    )
            }
    }

    private fun descriptionFor(
        status: String
    ): String =
        when (
            normalizeStatus(
                status
            )
        ) {
            "AMAN" ->
                "Kondisi lereng dalam batas aman."

            "WASPADA" ->
                "Pergeseran terdeteksi. Tingkatkan pemantauan."

            "SIAGA" ->
                "Pergeseran signifikan. Periksa kondisi lapangan."

            "AWAS" ->
                "Pergerakan tinggi terdeteksi. Siapkan tindakan keselamatan."

            "BAHAYA" ->
                "Ambang bahaya terlampaui. Segera ikuti prosedur keselamatan."

            else ->
                "Menunggu data sensor."
        }

    private fun colorFor(
        status: String?
    ): Int =
        when (
            normalizeStatus(
                status
            )
        ) {
            "BAHAYA" ->
                Color.parseColor(
                    "#ED2638"
                )

            "AWAS" ->
                Color.parseColor(
                    "#EF5B24"
                )

            "SIAGA" ->
                Color.parseColor(
                    "#F39413"
                )

            "WASPADA" ->
                Color.parseColor(
                    "#D5A900"
                )

            "AMAN" ->
                Color.parseColor(
                    "#0AA95B"
                )

            else ->
                Color.parseColor(
                    "#8795A3"
                )
        }

    private fun startMonitoringService() {
        ContextCompat
            .startForegroundService(
                this,
                Intent(
                    this,
                    MonitoringService::class.java
                )
            )
    }

    @Suppress("BatteryLife")
    private fun requestIgnoreBatteryOptimizations() {
        try {
            val powerManager =
                getSystemService(
                    POWER_SERVICE
                ) as PowerManager

            if (
                !powerManager
                    .isIgnoringBatteryOptimizations(
                        packageName
                    )
            ) {
                val intent =
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                    )
                        .apply {
                            data =
                                Uri.parse(
                                    "package:$packageName"
                                )
                        }

                startActivity(
                    intent
                )
            }
        }
        catch (_: Exception) {
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.POST_NOTIFICATIONS
                ),
                100
            )
        }
    }
}
