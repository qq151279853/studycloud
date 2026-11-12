package com.studycloud.tv.gate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.studycloud.gate.ForegroundContext
import com.studycloud.gate.GateState
import com.studycloud.gate.LocalMoment
import com.studycloud.tv.R
import com.studycloud.tv.StudyCloudApp
import com.studycloud.tv.ui.QuizActivity
import java.util.Calendar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 管控前台服务：每秒驱动一次 [com.studycloud.gate.GateEngine]，
 * 余额耗尽时拉起答题页并暂停播放。
 *
 * 必须是前台服务 + 常驻通知，否则会被系统回收，管控就断了。
 */
class GateService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val app: StudyCloudApp get() = application as StudyCloudApp

    private lateinit var media: MediaController
    private var lastState: GateState = GateState.IDLE

    override fun onCreate() {
        super.onCreate()

        media = MediaController(this)
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        scope.launch {
            while (isActive) {
                tick()
                delay(TICK_INTERVAL_MILLIS)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        media.restore()
        super.onDestroy()
    }

    private fun tick() {
        val moment = currentMoment()
        val context = readForeground()

        app.gate.onTick(context)

        val state = app.gate.evaluate(moment, context)

        if (state != lastState) {
            onStateChanged(state)
            lastState = state
        }

        drainEvents()
    }

    private fun onStateChanged(state: GateState) {
        when (state) {
            GateState.PROMPT -> {
                // 先暂停播放并请求音频焦点，再弹答题页
                media.pauseAndMute()
                launchQuiz()
            }

            GateState.LOCKED -> {
                media.pauseAndMute()
                launchQuiz()
            }

            // 回到自由使用，恢复原来的静音状态
            GateState.IDLE, GateState.WATCHING -> media.restore()

            else -> Unit
        }
    }

    private fun launchQuiz() {
        val intent = Intent(this, QuizActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        // 部分系统限制后台启动 Activity；有悬浮窗权限时才稳妥，
        // 没有时退化为通知点击进入（见 README 的权限矩阵）。
        runCatching { startActivity(intent) }
    }

    private fun readForeground(): ForegroundContext {
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        val screenOn = power.isInteractive

        val packageName = currentForegroundPackage()

        return ForegroundContext(
            screenOn = screenOn,
            foregroundPackage = packageName,
            isOwnApp = packageName == this.packageName,
            isLauncher = packageName?.contains("launcher", ignoreCase = true) == true,
            isSystemUi = packageName == "com.android.systemui",
        )
    }

    /**
     * 识别前台应用。没有使用情况访问权限时返回 null——
     * 此时 [ForegroundContext.isConsuming] 为 false，等于降级成「只在亮屏时计时」。
     */
    private fun currentForegroundPackage(): String? {
        val usage = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        val now = System.currentTimeMillis()

        val stats = runCatching {
            usage.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - LOOKBACK_MILLIS, now)
        }.getOrNull()

        return stats?.maxByOrNull { it.lastTimeUsed }?.packageName
    }

    private fun currentMoment(): LocalMoment {
        val calendar = Calendar.getInstance()

        // Calendar 的周日是 1，这里统一成周一 = 0
        val dayOfWeek = (calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7
        val slot = calendar.get(Calendar.HOUR_OF_DAY) * 2 + if (calendar.get(Calendar.MINUTE) >= 30) 1 else 0

        val day = "%04d-%02d-%02d".format(
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH),
        )

        return LocalMoment(day, dayOfWeek, slot, calendar.timeInMillis)
    }

    private fun drainEvents() {
        val events = app.gate.drainEvents()

        if (events.isEmpty()) {
            return
        }

        scope.launch(Dispatchers.IO) {
            for (event in events) {
                app.store.enqueueGateEvent(event)
            }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.gate_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )

        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val intent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, QuizActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle(getString(R.string.gate_notification_title))
            .setContentText(getString(R.string.gate_notification_text))
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(intent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "studycloud-gate"
        private const val NOTIFICATION_ID = 1001
        private const val TICK_INTERVAL_MILLIS = 1000L
        private const val LOOKBACK_MILLIS = 10_000L

        fun start(context: Context) {
            val intent = Intent(context, GateService::class.java)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
