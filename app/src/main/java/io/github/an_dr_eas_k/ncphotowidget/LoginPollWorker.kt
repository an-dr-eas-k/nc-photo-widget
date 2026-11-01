package io.github.an_dr_eas_k.ncphotowidget

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Finishes a login that was started in the browser even when the settings screen is not open. The
 * user may close the browser to the home screen, or the launcher may drop the screen that started
 * the login; the login must still end up stored, or the next try starts from scratch.
 */
class LoginPollWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        val settings = Settings(ctx)
        val pending = settings.pendingLogin ?: return Result.success()
        if (System.currentTimeMillis() > pending.deadline) {
            settings.pendingLogin = null
            return Result.success()
        }
        val login = try {
            LoginFlow.poll(LoginFlow.Session("", pending.pollUrl, pending.token))
        } catch (e: Exception) {
            null
        }
        if (login != null) {
            settings.completeLogin(pending, login)
            Scheduler.apply(ctx)
            WidgetUpdater.updateAll(ctx)
        } else {
            enqueue(ctx, delaySeconds = RETRY_SECONDS)
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "login-poll"
        private const val RETRY_SECONDS = 5L

        fun enqueue(c: Context, delaySeconds: Long = 0) {
            val request = OneTimeWorkRequestBuilder<LoginPollWorker>()
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(c).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
