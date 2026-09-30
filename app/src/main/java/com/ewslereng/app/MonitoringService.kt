package com.ewslereng.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MonitoringService :
    Service() {

    companion object {

        const val CHANNEL_PERSISTENT =
            "ews_persistent"

        const val CHANNEL_ALERT =
            "ews_alert"

        const val NOTIF_ID_PERSISTENT =
            1001

        const val NOTIF_ID_ALERT =
            1002

        const val ACTION_SILENCE =
            "com.ewslereng.app.ACTION_SILENCE"

        const val ACTION_CHECK_NOW =
            "com.ewslereng.app.ACTION_CHECK_NOW"

        const val ACTION_DATA_UPDATED =
            "com.ewslereng.app.DATA_UPDATED"

        const val EXTRA_WORST_STATUS =
            "worst_status"
    }

    private val scope =
        CoroutineScope(
            Dispatchers.Default +
                SupervisorJob()
        )

    private var pollJob:
        Job? =
        null

    /*
     * Sama dengan rancangan buku awal:
     * severity tertinggi yang SUDAH pernah dialarm disimpan PER SENSOR.
     */
    private val alertedSeverityBySensor =
        mutableMapOf<String, Int>()

    private var currentWorstStatus =
        "UNKNOWN"

    private var wakeLock:
        PowerManager.WakeLock? =
        null

    override fun onCreate() {
        super.onCreate()

        createNotificationChannels()

        ServiceCompat.startForeground(
            this,
            NOTIF_ID_PERSISTENT,
            buildPersistentNotification(
                "UNKNOWN",
                muted = false
            ),
            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.Q
            ) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
            else {
                0
            }
        )

        acquireWakeLock()

        startPolling()
    }

    private fun acquireWakeLock() {
        try {
            val powerManager =
                getSystemService(
                    POWER_SERVICE
                ) as PowerManager

            wakeLock =
                powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "EWSLereng::MonitoringWakeLock"
                )
                    .apply {
                        setReferenceCounted(
                            false
                        )

                        acquire()
                    }
        }
        catch (_: Exception) {
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        when (
            intent?.action
        ) {

            ACTION_SILENCE -> {
                stopCurrentAlarm()
            }

            ACTION_CHECK_NOW -> {
                scope.launch {
                    checkNow()
                }
            }
        }

        return START_STICKY
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? =
        null

    override fun onDestroy() {
        pollJob?.cancel()

        AlarmPlayer.stop()

        runCatching {
            if (
                wakeLock?.isHeld ==
                true
            ) {
                wakeLock?.release()
            }
        }

        super.onDestroy()
    }

    private fun startPolling() {
        pollJob =
            scope.launch {
                while (isActive) {
                    try {
                        checkNow()
                    }
                    catch (_: Exception) {
                    }

                    delay(
                        Config.POLL_INTERVAL_SERVICE_MS
                    )
                }
            }
    }

    private suspend fun checkNow() {
        when (
            val result =
                SensorRepository.fetchLatest()
        ) {
            is FetchResult.Success ->
                handleData(
                    result.data.sensors
                )

            is FetchResult.Error -> {
                // Notifikasi foreground tetap hidup.
            }
        }
    }

    private fun handleData(
        data: Map<String, SensorLatest>
    ) {
        val worst =
            SensorRepository
                .worstStatus(
                    data
                )

        currentWorstStatus =
            worst

        LocalBroadcastManager
            .getInstance(
                this
            )
            .sendBroadcast(
                Intent(
                    ACTION_DATA_UPDATED
                )
                    .putExtra(
                        EXTRA_WORST_STATUS,
                        worst
                    )
            )

        /*
         * Jika sensor kembali AMAN, reset rekam alarm sensor tersebut.
         */
        data.forEach {
            (
                id,
                sensor
            ) ->

            if (
                severityOf(
                    sensor.status
                ) <= 0
            ) {
                alertedSeverityBySensor
                    .remove(
                        id
                    )
            }
        }

        val escalated =
            data.filter {
                (
                    id,
                    sensor
                ) ->

                val sev =
                    severityOf(
                        sensor.status
                    )

                sev >=
                    Config.ALARM_MIN_SEVERITY &&
                    sev >
                    (
                        alertedSeverityBySensor[id]
                            ?: 0
                        )
            }

        if (
            escalated.isNotEmpty()
        ) {
            AlarmPlayer.start()

            vibrateAlarm()

            showAlertNotification(
                worst,
                escalated.keys
                    .joinToString(", ")
            )

            escalated.forEach {
                (
                    id,
                    sensor
                ) ->

                alertedSeverityBySensor[id] =
                    severityOf(
                        sensor.status
                    )
            }
        }

        /*
         * Alarm yang sudah dimatikan manual tidak dibunyikan ulang
         * selama severity sensor belum naik lagi.
         */
        getSystemService(
            NotificationManager::class.java
        )
            .notify(
                NOTIF_ID_PERSISTENT,
                buildPersistentNotification(
                    worst,
                    muted =
                        !AlarmPlayer.isPlaying()
                )
            )
    }

    private fun stopCurrentAlarm() {
        AlarmPlayer.stop()

        (
            getSystemService(
                VIBRATOR_SERVICE
            ) as? Vibrator
            )
            ?.cancel()

        val nm =
            getSystemService(
                NotificationManager::class.java
            )

        nm.cancel(
            NOTIF_ID_ALERT
        )

        nm.notify(
            NOTIF_ID_PERSISTENT,
            buildPersistentNotification(
                currentWorstStatus,
                muted = true
            )
        )
    }

    private fun vibrateAlarm() {
        val vibrator =
            getSystemService(
                VIBRATOR_SERVICE
            ) as? Vibrator
                ?: return

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {
            vibrator.vibrate(
                VibrationEffect
                    .createWaveform(
                        longArrayOf(
                            0,
                            500,
                            300,
                            500,
                            300,
                            500
                        ),
                        0
                    )
            )
        }
        else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(
                longArrayOf(
                    0,
                    500,
                    300,
                    500
                ),
                0
            )
        }
    }

    private fun createNotificationChannels() {
        val nm =
            getSystemService(
                NotificationManager::class.java
            )

        val persistent =
            NotificationChannel(
                CHANNEL_PERSISTENT,
                "Status pemantauan",
                NotificationManager.IMPORTANCE_LOW
            )
                .apply {
                    description =
                        "Status pemantauan EWS Lereng"
                }

        val alert =
            NotificationChannel(
                CHANNEL_ALERT,
                "Peringatan lereng",
                NotificationManager.IMPORTANCE_HIGH
            )
                .apply {
                    description =
                        "Peringatan perubahan status lereng"

                    enableVibration(
                        true
                    )

                    setSound(
                        null,
                        null
                    )
                }

        nm.createNotificationChannel(
            persistent
        )

        nm.createNotificationChannel(
            alert
        )
    }

    private fun buildPersistentNotification(
        status: String,
        muted: Boolean
    ): Notification {

        val openAppIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(
                    this,
                    MainActivity::class.java
                ),
                PendingIntent.FLAG_IMMUTABLE or
                    PendingIntent.FLAG_UPDATE_CURRENT
            )

        val text =
            when {
                severityOf(status) < 0 ->
                    "Menghubungkan ke backend EWS..."

                muted &&
                    severityOf(status) >=
                    Config.ALARM_MIN_SEVERITY ->
                    "Alarm dimatikan, pemantauan tetap aktif"

                else ->
                    "Memantau status sensor setiap 1 menit"
            }

        return NotificationCompat
            .Builder(
                this,
                CHANNEL_PERSISTENT
            )
            .setContentTitle(
                "EWS Lereng · ${labelOf(status)}"
            )
            .setContentText(
                text
            )
            .setSmallIcon(
                android.R.drawable.ic_dialog_info
            )
            .setOngoing(
                true
            )
            .setOnlyAlertOnce(
                true
            )
            .setContentIntent(
                openAppIntent
            )
            .build()
    }

    private fun showAlertNotification(
        status: String,
        sensors: String
    ) {
        val openAppIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(
                    this,
                    MainActivity::class.java
                ),
                PendingIntent.FLAG_IMMUTABLE or
                    PendingIntent.FLAG_UPDATE_CURRENT
            )

        val silenceIntent =
            PendingIntent.getService(
                this,
                1,
                Intent(
                    this,
                    MonitoringService::class.java
                )
                    .setAction(
                        ACTION_SILENCE
                    ),
                PendingIntent.FLAG_IMMUTABLE or
                    PendingIntent.FLAG_UPDATE_CURRENT
            )

        val title =
            when (
                normalizeStatus(
                    status
                )
            ) {
                "BAHAYA" ->
                    "🚨 BAHAYA — pergerakan lereng"

                "AWAS" ->
                    "⚠ AWAS — pergerakan lereng"

                "SIAGA" ->
                    "⚠ SIAGA — pergerakan signifikan"

                "WASPADA" ->
                    "WASPADA — pergeseran terdeteksi"

                else ->
                    "Peringatan EWS Lereng"
            }

        val notification =
            NotificationCompat
                .Builder(
                    this,
                    CHANNEL_ALERT
                )
                .setContentTitle(
                    title
                )
                .setContentText(
                    "Sensor: $sensors. Ketuk untuk melihat status."
                )
                .setSmallIcon(
                    android.R.drawable.ic_dialog_alert
                )
                .setPriority(
                    NotificationCompat.PRIORITY_HIGH
                )
                .setCategory(
                    NotificationCompat.CATEGORY_ALARM
                )
                .setAutoCancel(
                    false
                )
                .setContentIntent(
                    openAppIntent
                )
                .addAction(
                    android.R.drawable.ic_media_pause,
                    "Matikan Alarm",
                    silenceIntent
                )
                .build()

        getSystemService(
            NotificationManager::class.java
        )
            .notify(
                NOTIF_ID_ALERT,
                notification
            )
    }
}
