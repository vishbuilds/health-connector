package com.vishaal.healthconnector.sync

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.vishaal.healthconnector.MainActivity
import com.vishaal.healthconnector.R
import com.vishaal.healthconnector.data.SettingsStore
import com.vishaal.healthconnector.logic.ACTION_OPEN_LOG_PROMPT
import com.vishaal.healthconnector.logic.EXTRA_LOG_PROMPT
import com.vishaal.healthconnector.logic.LogTarget
import com.vishaal.healthconnector.logic.LogTargets
import com.vishaal.healthconnector.network.HomeApi
import com.vishaal.healthconnector.network.HomeResult

class LogReminderWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val key = inputData.getString(KEY_LOG_TARGET) ?: return Result.success()
        val target = when (val result = HomeApi(SettingsStore(applicationContext)).fetchHome()) {
            is HomeResult.Success -> LogTargets.forKey(result.summary, key)
            is HomeResult.HttpError, is HomeResult.NetworkError, HomeResult.NotConfigured -> null
        }
        if (target != null) {
            showReminder(target)
        }
        return Result.success()
    }

    @SuppressLint("MissingPermission")
    private fun showReminder(target: LogTarget) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        ensureChannel()

        val tapIntent = Intent(applicationContext, MainActivity::class.java).apply {
            action = ACTION_OPEN_LOG_PROMPT
            putExtra(EXTRA_LOG_PROMPT, target.prompt)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            target.key.hashCode(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(target.heading)
            .setContentText(target.reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(target.reason))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(applicationContext)
            .notify(NOTIFICATION_ID_BASE + target.key.hashCode().mod(1000), notification)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Daily log prompts",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Reminders to log weight or food when still outstanding."
            },
        )
    }

    companion object {
        const val KEY_LOG_TARGET = "log_target"
        private const val CHANNEL_ID = "daily_log_prompts"
        private const val NOTIFICATION_ID_BASE = 4200
    }
}
