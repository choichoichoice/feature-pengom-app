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
    /** 맨 앞 화면(activity) 이름. 같은 앱 안에서 화면이 바뀔 때 앞 화면의 '멈춤'을 앱 전체 멈춤으로 착각하지 않게 */
    private var foregroundClass: String? = null
    private var lastQuery = 0L
    private var lastTick = 0L
    private var carryMs = 0L
    private var lastNotifiedMin = -1
    private var running = false

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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService 를 부를 때마다 startForeground 를 다시 불러야 시스템이 앱을 멈추지 않는다
        if (!goForeground()) { stopSelf(); return START_NOT_STICKY }
        if (!running) {
            running = true
            catchUpToday()
            lastTick = System.currentTimeMillis()
            handler.post(loop)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(loop)
        if (::overlay.isInitialized) overlay.hide()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** 서비스가 꺼져 있던 동안의 오늘 사용 시간을 시스템 기록으로 다시 계산한다 */
    private fun catchUpToday() {
        val now = System.currentTimeMillis()
        // 하루는 새벽 4시에 시작한다 (CounterStore 와 같은 기준). 자정부터 세면 0~4시 시간이 이틀에 들어간다
        val events = usm.queryEvents(store.dayStartMillis(), now)
        val e = UsageEvents.Event()
        var openPkg: String? = null
        var openAt = 0L
        var total = 0L
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            when (e.eventType) {
                RESUMED -> {
                    if (openPkg != null) total += e.timeStamp - openAt
                    openPkg = if (e.packageName in TARGETS) e.packageName else null
                    openAt = e.timeStamp
                    foreground = e.packageName; foregroundClass = e.className
                }
                PAUSED -> if (e.packageName == foreground && e.className == foregroundClass) {
                    if (openPkg != null) total += e.timeStamp - openAt
                    openPkg = null; foreground = null; foregroundClass = null
                }
            }
        }
        if (openPkg != null) total += now - openAt
        store.raiseUsedTo((total / 1000).toInt())
        lastQuery = now
    }

    private fun step() {
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(lastQuery, now)
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == RESUMED) { foreground = e.packageName; foregroundClass = e.className }
            else if (e.eventType == PAUSED && e.packageName == foreground && e.className == foregroundClass) {
                foreground = null; foregroundClass = null
            }
        }
        lastQuery = now

        val watching = power.isInteractive && foreground in TARGETS
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

    /** 알림을 띄우고 포그라운드 서비스로 올린다. 시스템이 막으면 false (그때는 조용히 멈춘다) */
    private fun goForeground(): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel("watch") // 0.3.0 이전 채널 (중요도가 낮아 숨겨졌음)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "남은 시간", NotificationManager.IMPORTANCE_LOW).apply {
                description = "쇼츠 앱을 본 시간을 재는 동안 표시됩니다"
                setShowBadge(false)
            }
        )
        val n = buildNotification()
        return try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTI_ID, n)
            true
        } catch (e: Exception) {
            // 예: 백그라운드에서 다시 시작될 때 시스템이 포그라운드 서비스를 막는 경우.
            // 앱을 다시 열면 MainActivity 가 서비스를 다시 켠다.
            false
        }
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
            return nb.setContentTitle("아직 약속 전").setContentText("오늘 ${used / 60}분 봄").build()
        }
        val left = b * 60 - used
        val over = left < 0
        nb.setContentTitle(
            when {
                over -> "오늘 시간 다 씀"
                promise != null && used > promise * 60 -> "더 받은 ${b - promise}분 중 남은 시간"
                else -> "약속 ${b}분 중 남은 시간"
            }
        )
            .setContentText(
                if (lastWatching) (if (over) "넘긴 시간이 늘고 있어" else "보는 동안 줄어드는 중")
                else (if (over) "+${-left / 60}분 초과 · 멈춤" else "${left / 60}분 ${left % 60}초 남음 · 멈춤")
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

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTI_ID, buildNotification())
    }

    companion object {
        val TARGETS = setOf(
            "com.instagram.android",
            "com.google.android.youtube",
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill",
        )
        @Suppress("DEPRECATION") private const val RESUMED = UsageEvents.Event.MOVE_TO_FOREGROUND
        @Suppress("DEPRECATION") private const val PAUSED = UsageEvents.Event.MOVE_TO_BACKGROUND
        private const val TICK_MS = 1_000L
        private const val IDLE_MS = 15_000L
        private const val CHANNEL = "remaining"
        private const val NOTI_ID = 7

        fun start(ctx: Context) {
            val i = Intent(ctx, UsageWatchService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }
}
