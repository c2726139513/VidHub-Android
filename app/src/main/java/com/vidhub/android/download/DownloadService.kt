package com.vidhub.android.download

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.vidhub.android.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

// T10 下载前台服务（dataSync）：抬升进程优先级，HOME 回桌面/详情页退出下载不中断。
//  - 渠道 vidhub_downloads IMPORTANCE_LOW：静默驻留无横幅
//  - onStartCommand 同步 startForeground（系统 5s 窗口；API≥29 三参带 dataSync type，
//    <29 两参重载——本任务只用平台自带类型，不引入 androidx.foreground 依赖）
//  - collect queueState 刷新通知（进行中任务数+当前百分比）；队列空 → stopForeground+stopSelf
//  - START_NOT_STICKY：计划明令无开机恢复/自动重启；通知无 contentIntent（不做点击跳转）
//  - API33 未授权 POST_NOTIFICATIONS 时 notify 被系统静默丢弃——服务照跑仅通知不可见，
//    不阻塞下载；运行时请求在 T11 UI 侧（见 hasNotificationPermission 契约）
@AndroidEntryPoint
class DownloadService : Service() {

    @Inject
    lateinit var repository: DownloadRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        createChannel()
        scope.launch {
            repository.queueState.collect { state ->
                if (state.activeCount == 0) {
                    stopForegroundCompat()
                    stopSelf()
                } else {
                    notificationManager().notify(NOTIFICATION_ID, buildNotification(state))
                }
            }
        }
    }

    // 前台化必须同步完成于本方法路径（跨进程 5s 窗口）；collect 只负责后续更新
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val state = repository.queueState.value
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(state),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            @Suppress("DEPRECATION") // 两参重载是 API<29 唯一形态
            startForeground(NOTIFICATION_ID, buildNotification(state))
        }
        if (state.activeCount == 0) {
            // 防御分支：空队列启动（或 collect 首发已消费 0 态）→ 立即自停，不悬挂前台
            stopForegroundCompat()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager().createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.download_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    private fun buildNotification(state: QueueState): android.app.Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.download_notification_title))
            .setContentText(
                getString(
                    R.string.download_notification_progress,
                    state.activeCount,
                    state.progressPercent,
                ),
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun notificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Suppress("DEPRECATION") // boolean 重载 33 起废弃，但全版本可用且语义即"移除通知"
    private fun stopForegroundCompat() {
        stopForeground(true)
    }

    companion object {
        private const val CHANNEL_ID = "vidhub_downloads"
        private const val NOTIFICATION_ID = 20240

        // T11 契约：enqueue 前判断；API33+ 未授权 → T11 走 ActivityResult 请求
        // （授权与否都不阻塞下载，仅决定通知可见性）
        fun hasNotificationPermission(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED

        // T11 契约：确认入队后启动本服务（O+ 必须 startForegroundService，否则 5s 未前台化崩）
        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
