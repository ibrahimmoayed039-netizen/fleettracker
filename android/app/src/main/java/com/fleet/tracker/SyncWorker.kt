package com.fleet.tracker

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

/** طابور الإرسال في الخلفية: يعمل عند توفر الإنترنت حتى لو كان التطبيق مغلقاً */
class SyncWorker(ctx: Context, p: WorkerParameters) : Worker(ctx, p) {
    override fun doWork(): Result {
        val st = Store.get(applicationContext)
        if (!st.configured() || !st.dirty) return Result.success()
        var r = Syncer.syncNow(st)
        var n = 0
        // تعديلات جديدة وصلت أثناء الإرسال
        while (r == Syncer.R.OK && st.dirty && n++ < 3) r = Syncer.syncNow(st)
        return when (r) {
            Syncer.R.OK, Syncer.R.DISABLED -> Result.success()
            Syncer.R.RETRY -> if (runAttemptCount >= 10) Result.success() else Result.retry()
        }
    }

    companion object {
        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** إرسال ما هو معلّق فور توفر اتصال (مع إعادة محاولة تدريجية عند الفشل) */
        fun enqueue(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(online)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("sync_now", ExistingWorkPolicy.KEEP, req)
        }

        /** شبكة أمان: فحص دوري كل 15 دقيقة لأي بيانات عالقة */
        fun schedulePeriodic(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(online).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("sync_periodic", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
