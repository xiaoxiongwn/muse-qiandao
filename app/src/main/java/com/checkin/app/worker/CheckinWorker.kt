package com.checkin.app.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.room.Room
import androidx.work.*
import com.checkin.app.MainActivity
import com.checkin.app.data.AppDatabase
import com.checkin.app.data.CheckinLog
import com.checkin.app.web.ProxyHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * 每天定时任务（WorkManager），时间存在 SharedPreferences，可在首页改，默认 8:00。
 * 只做 B 路：用登录时存下的 Cookie 直接调签到接口，走该站自己的代理（HTTP/SOCKS5 都行）。
 * 失败 → Result.retry()，WorkManager 按退避策略重试，最多 3 次；
 * 3 次都失败就发通知，让用户点通知进 App 手动签到。
 * 注意：需要 JS / Cloudflare 验证的站不要指望这条路，见 README。
 */
class CheckinWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val db = Room.databaseBuilder(
            applicationContext, AppDatabase::class.java, "checkin.db"
        ).addMigrations(AppDatabase.MIGRATION_1_2).build()
        val dao = db.siteDao()
        val sites = dao.enabledSites()
        var anyFail = false

        for (site in sites) {
            val url = site.checkinUrl.ifBlank { site.homeUrl }
            val result: Pair<Boolean, String> = try {
                httpCheckin(site, url)
            } catch (e: Exception) {
                false to "网络异常: ${e.message ?: "未知错误"}"
            }
            dao.insertLog(
                CheckinLog(
                    siteId = site.id, siteName = site.name,
                    success = result.first, mode = "AUTO_HTTP", message = result.second
                )
            )
            if (!result.first) anyFail = true
        }
        db.close()
        scheduleDaily(applicationContext) // 跑完就按保存的时间排明天的，形成每天循环

        return if (anyFail) {
            notifyManualNeeded()
            if (runAttemptCount < 3) Result.retry() else Result.success()
        } else {
            Result.success()
        }
    }

    private suspend fun httpCheckin(
        site: com.checkin.app.data.Site, url: String
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (site.cookieSnapshot.isBlank()) {
            return@withContext false to "未登录：请先在 App 里打开该站登录一次"
        }
        val builder = OkHttpClient.Builder()
            .callTimeout(30, TimeUnit.SECONDS)
        ProxyHelper.applyToOkHttp(site, builder)
        val client = builder.build()
        val reqBuilder = Request.Builder()
            .url(url)
            .header("Cookie", site.cookieSnapshot)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36")
        if (site.httpMethod.uppercase() == "POST") {
            val body = site.httpBody.ifBlank { "" }
                .toRequestBody("application/x-www-form-urlencoded".toMediaType())
            reqBuilder.post(body)
        }
        client.newCall(reqBuilder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val ok = resp.isSuccessful &&
                (site.successKeyword.isBlank() || text.contains(site.successKeyword))
            return@withContext ok to
                if (ok) "成功（HTTP ${resp.code}）" else "HTTP ${resp.code}，未出现“${site.successKeyword}”"
        }
    }

    private fun notifyManualNeeded() {
        val mgr = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
            as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL, "签到提醒", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val intent = Intent(applicationContext, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            applicationContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("有网站自动签到失败了")
            .setContentText("点我打开 App，手动签到一次")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        mgr.notify(1001, n)
    }

    companion object {
        const val CHANNEL = "checkin_channel"
        const val WORK_NAME = "daily-checkin"
        private const val PREFS = "checkin_settings"
        private const val KEY_HOUR = "hour"
        private const val KEY_MINUTE = "minute"

        fun getScheduledTime(context: Context): Pair<Int, Int> {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return p.getInt(KEY_HOUR, 8) to p.getInt(KEY_MINUTE, 0)
        }

        fun saveScheduledTime(context: Context, hour: Int, minute: Int) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_HOUR, hour).putInt(KEY_MINUTE, minute).apply()
            scheduleDaily(context, hour, minute)
        }

        /** 每天指定时间执行，只在有网时跑；失败退避 15 分钟起。 */
        fun scheduleDaily(context: Context, hour: Int? = null, minute: Int? = null) {
            val (h, m) = if (hour != null && minute != null) hour to minute
                else getScheduledTime(context)
            val now = Calendar.getInstance()
            val target = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0)
                if (before(now)) add(Calendar.DAY_OF_YEAR, 1)
            }
            val delay = target.timeInMillis - now.timeInMillis
            val req = OneTimeWorkRequestBuilder<CheckinWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .addTag(WORK_NAME)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, req)
        }
    }
}
