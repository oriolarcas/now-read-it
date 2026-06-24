package cat.oriol.nowreadit.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import cat.oriol.nowreadit.R
import cat.oriol.nowreadit.NowReadItApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TtsWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    private val tag = "NowReadItTts"
    private val repository = (appContext as NowReadItApplication).appContainer.libraryRepository
    private val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        ensureChannel()

        val itemId = inputData.getLong(KEY_ITEM_ID, -1L)
        if (itemId <= 0L) return@withContext Result.failure()

        Log.i(tag, "Worker started for itemId=$itemId")

        setForeground(createForegroundInfo(progress = 0))

        runCatching {
            repository.runTtsGeneration(itemId) { progress ->
                Log.i(tag, "Worker progress itemId=$itemId progress=$progress")
                setProgress(workDataOf(KEY_PROGRESS to progress))
                setForeground(createForegroundInfo(progress))
            }
        }.fold(
            onSuccess = {
                Log.i(tag, "Worker completed successfully for itemId=$itemId")
                Result.success()
            },
            onFailure = { throwable ->
                Log.e(tag, "Worker failed for itemId=$itemId", throwable)
                if (throwable is IllegalArgumentException || throwable is IllegalStateException) {
                    Result.failure()
                } else {
                    Result.retry()
                }
            },
        )
    }

    private fun createForegroundInfo(progress: Int): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(applicationContext.getString(R.string.app_name))
            .setContentText("Generating audio... $progress%")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress, progress == 0)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Speech generation",
            NotificationManager.IMPORTANCE_LOW,
        )
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        const val KEY_ITEM_ID = "item_id"
        const val KEY_PROGRESS = "progress"
        private const val CHANNEL_ID = "tts_generation"
        private const val NOTIFICATION_ID = 7

        fun uniqueWorkName(itemId: Long): String = "tts-$itemId"
    }
}
