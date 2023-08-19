package xyz.malkki.wifiscannerformls.db

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import timber.log.Timber
import xyz.malkki.wifiscannerformls.WifiScannerApplication
import java.time.ZonedDateTime
import kotlin.time.DurationUnit
import kotlin.time.measureTimedValue

/**
 * Worker for deleting old scan reports from the local DB
 */
class DbPruneWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    companion object {
        const val PERIODIC_WORK_NAME = "db_prune_periodic"
        const val ONE_TIME_WORK_NAME = "db_prune_one_time"

        const val MAX_AGE_DAYS = "max_age_days"
    }

    override suspend fun doWork(): Result {
        val db = (applicationContext as WifiScannerApplication).reportDb
        val reportDao = db.reportDao()

        //By default delete reports older than 60 days
        val maxAgeDays = inputData.getLong(MAX_AGE_DAYS, 60)

        val minTimestamp = ZonedDateTime.now().minusDays(maxAgeDays).toInstant()

        Timber.i("Deleting reports older than $minTimestamp")

        val (deleteCount, duration) = measureTimedValue {
            reportDao.deleteOlderThan(minTimestamp)
        }

        Timber.i("Deleted $deleteCount reports in ${duration.toString(DurationUnit.SECONDS, 1)}")

        return Result.success()
    }
}