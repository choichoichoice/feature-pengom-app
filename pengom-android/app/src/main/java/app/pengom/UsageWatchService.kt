package app.pengom

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/**
 * 인스타·유튜브·틱톡이 화면 맨 앞에 떠 있는 시간을 잰다.
 *
 * - 시스템 "사용 정보"의 앱 전환 기록(어떤 앱이 앞에 왔다/갔다)만 읽는다. 화면 내용은 읽지 않는다.
 * - 화면이 켜져 있고 대상 앱이 맨 앞일 때만 1초씩 더한다.
 * - 대상 앱이 앞에 오면 화면 위 카운터를 띄우고, 정해둔 분에 도달하면 펭곰이가 끼어든다.
 */
class UsageWatchService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var store: CounterStore
    private lateinit var overlay: OverlayController
    private lateinit var usm: UsageStatsManager
    private lateinit var power: PowerManager
    private var foreground: String? = null
    private var lastQuery = 0L
    private var lastTick = 0L
    private var carryMs = 0L
    private var lastNotifiedMin = -1

    private val loop = object : Runnable {
        override fun run() {
            step()
            handler.postDelayed(this, if (power.isInteractive) TICK_MS else IDLE_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        store = CounterStore(this)
        usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        power = getSystemService(Context.POWER_SERVICE) as PowerManager
        overlay = OverlayController(this, store) {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        goForeground()
        catchUpToday()
        lastTick = System.currentTimeMillis()
        handler.post(loop)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacks(loop)
        overlay.hide()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** 서비스가 꺼져 있던 동안의 오늘 사용 시간을 시스템 기록으로 다시 계산한다 */
    private fun catchUpToday() {
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(store.dayStartMillis(), now)
        val targets = store.targets()
        val e = UsageEvents.Event()
        var openPkg: String? = null
        var openAt = 0L
        var total = 0L
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            when (e.eventType) {
                RESUMED -> {
                    if (openPkg != null) total += e.timeStamp - openAt
                    openPkg = if (e.packageName in targets) e.packageName else null
                    openAt = e.timeStamp
                    foreground = e.packageName
                }
                PAUSED -> if (e.packageName == openPkg) { total += e.timeStamp - openAt; openPkg = null }
            }
        }
        if (openPkg != null) total += now - openAt
        store.raiseUsedTo((total / 1000).toInt())
        lastQuery = now
    }

    private var lastDay: String? = null

    private fun step() {
        // 기상 시간이 지나 새 하루가 되면 아침 약속을 물어본다
        val day = store.dateKey()
        if (lastDay != null && day != lastDay) morningNudge()
        lastDay = day

        val now = System.currentTimeMillis()
        val events = usm.queryEvents(lastQuery, now)
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == RESUMED) foreground = e.packageName
            else if (e.eventType == PAUSED && e.packageName == foreground) foreground = null
        }
        lastQuery = now

        val watching = power.isInteractive && foreground in store.targets()
        if (watching != lastWatching) { lastWatching = watching; updateNotification() }
        if (watching) overlay.show() else if (foreground != null && foreground != "com.android.systemui") overlay.hide()

        val dt = (now - lastTick).coerceIn(0, 5_000)
        lastTick = now
        if (!watching) { carryMs = 0; return }
        carryMs += dt
        val sec = (carryMs / 1000).toInt()
        if (sec <= 0) return
        carryMs -= sec * 1000L
        val tick = store.addSeconds(sec)
        overlay.onTick(tick)
        if (tick.usedSec / 60 != lastNotifiedMin) { lastNotifiedMin = tick.usedSec / 60; updateNotification() }
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel("watch") // 0.3.0 이전 채널 (중요도가 낮아 숨겨졌음)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "남은 시간", NotificationManager.IMPORTANCE_LOW).apply {
                description = "사용 시간을 재는 동안 표시돼요"
                setShowBadge(false)
            }
        )
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTI_ID, n)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val b = store.limit()
        val promise = store.budget()
        val used = store.usedSec()
        val nb = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_pengom)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (b == null) {
            return nb.setContentTitle("오늘 목표 없음").setContentText("오늘 사용 ${used / 60}분").build()
        }
        val left = b * 60 - used
        val over = left < 0
        nb.setContentTitle(
            when {
                over -> "목표 시간 종료"
                promise != null && used > promise * 60 -> "남은 시간 (연장 +${b - promise}분)"
                else -> "남은 시간 · 목표 ${b}분"
            }
        )
            .setContentText(
                if (lastWatching) (if (over) "초과 시간 증가 중" else "사용 중")
                else (if (over) "초과 ${-left / 60}분 · 일시정지" else "남은 시간 ${left / 60}분 ${left % 60}초 · 일시정지")
            )
        if (lastWatching) {
            // 보는 동안만 알림 시계가 1초씩 움직인다 (남으면 거꾸로, 넘기면 앞으로)
            val now = System.currentTimeMillis()
            nb.setShowWhen(true).setUsesChronometer(true)
                .setChronometerCountDown(!over)
                .setWhen(now + left * 1000L)
        } else {
            nb.setShowWhen(false)
        }
        return nb.build()
    }

    private var lastWatching = false

    private fun morningNudge() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(MORNING, "아침 목표", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = store.budget()
        val n = Notification.Builder(this, MORNING)
            .setSmallIcon(R.drawable.ic_stat_pengom)
            .setContentTitle("좋은 아침이에요! 오늘은 몇 분까지 볼까요?")
            .setContentText(if (b != null) "정하지 않으면 어제처럼 목표 ${b}분이에요." else "펭곰이를 열어 목표를 정해 주세요.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(MORNING_ID, n)
        updateNotification()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTI_ID, buildNotification())
    }

    companion object {
        @Suppress("DEPRECATION") private const val RESUMED = UsageEvents.Event.MOVE_TO_FOREGROUND
        @Suppress("DEPRECATION") private const val PAUSED = UsageEvents.Event.MOVE_TO_BACKGROUND
        private const val TICK_MS = 1_000L
        private const val IDLE_MS = 15_000L
        private const val CHANNEL = "remaining"
        private const val NOTI_ID = 7
        private const val MORNING = "morning"
        private const val MORNING_ID = 8

        fun start(ctx: Context) {
            val i = Intent(ctx, UsageWatchService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }
}
