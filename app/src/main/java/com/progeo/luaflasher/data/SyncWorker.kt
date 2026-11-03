package com.progeo.luaflasher.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.progeo.luaflasher.graph
import java.util.concurrent.TimeUnit

/** Daily sync with a network constraint (PLAN.md section 4, step 1). */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = applicationContext.graph
        // Never compete with a running flash for CPU or storage.
        if (graph.flash.isActive) return Result.retry()
        return if (graph.sync.sync().isSuccess) Result.success() else Result.retry()
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("bundle-sync", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
