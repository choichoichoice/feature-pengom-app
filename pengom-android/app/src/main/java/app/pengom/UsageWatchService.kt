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
 * - 대상 앱이 앞에 오면 1회 시청 세션이 시작(또는 이어짐)되고, 화면 위에 이번 세션 남은 시간을 띄운다.
 * - 세션 시간이 끝나면 펭곰이가 끼어든다. 앱을 나가 일정 시간 쉬면 다음에는 새 세션.
 * - 하루 총 사용 시간은 기록만 한다.
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
        val spans = targetSpans(store.dayStartMillis(), now, initService = true)
        store.raiseUsedTo((spans.sumOf { it[1] - it[0] } / 1000).toInt())
        rebuildSessions(spans)
        lastQuery = now
    }

    /**
     * 오늘 대상 앱이 앞에 떠 있던 구간들 [시작, 끝] (ms). track() 과 같은 화면 단위 판단을 쓴다.
     * initService 면 서비스의 "지금 앞 화면" 상태도 함께 맞춘다.
     */
    private fun targetSpans(from: Long, now: Long, initService: Boolean): List<LongArray> {
        val events = usm.queryEvents(from, now)
        val targets = store.targets()
        val e = UsageEvents.Event()
        val open = LinkedHashMap<String, String>()
        var fg: String? = null
        val spans = mutableListOf<LongArray>()
        var since = -1L
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (initService) track(e)
            val key = e.packageName + "/" + (e.className ?: "")
            when (e.eventType) {
                RESUMED -> { open.remove(key); open[key] = e.packageName; fg = e.packageName }
                PAUSED -> { open.remove(key); if (e.packageName == fg && open.values.none { it == fg }) fg = open.values.lastOrNull() }
                else -> continue
            }
            val inTarget = fg in targets
            if (inTarget && since < 0) since = e.timeStamp
            else if (!inTarget && since >= 0) { spans += longArrayOf(since, e.timeStamp); since = -1L }
        }
        if (since >= 0) spans += longArrayOf(since, now)
        return spans
    }

    /** 오늘 대상 앱이 켜져 있던 구간을 세션으로 묶는다 (쉰 시간이 BREAK_MIN 분 미만이면 같은 세션) */
    private fun rebuildSessions(spans: List<LongArray>) {
        val segs = mutableListOf<Pair<Long, Int>>()
        var start = -1L; var end = -1L; var sec = 0L
        for (sp in spans) {
            if (start >= 0 && sp[0] - end < CounterStore.BREAK_MIN * 60_000L) { sec += sp[1] - sp[0]; end = sp[1] }
            else {
                if (start >= 0) segs += Pair(start / 1000, (sec / 1000).toInt())
                start = sp[0]; end = sp[1]; sec = sp[1] - sp[0]
            }
        }
        if (start >= 0) segs += Pair(start / 1000, (sec / 1000).toInt())
        store.rebuildToday(segs.filter { it.second >= 5 })
    }

    /**
     * 지금 앞에 떠 있는 화면들 ("패키지/화면 이름" → 패키지). 앱 단위가 아니라 화면 단위로 본다.
     * 한 앱 안에서 화면이 바뀔 때(쇼츠 넘기기, 댓글, 전체 화면 회전 등) 기록이
     * "새 화면 앞으로 → 옛 화면 뒤로" 순서로 올 수 있는데, 앱 단위로 보면 그걸 "앱을 닫았다"로
     * 착각해 시간이 멈췄다. 그 앱의 화면이 하나도 안 남았을 때만 닫은 것으로 본다.
     */
    private val resumed = LinkedHashMap<String, String>()

    private fun track(e: UsageEvents.Event) {
        val key = e.packageName + "/" + (e.className ?: "")
        when (e.eventType) {
            RESUMED -> { resumed.remove(key); resumed[key] = e.packageName; foreground = e.packageName }
            PAUSED -> {
                resumed.remove(key)
                // 방금 뒤로 간 화면이 지금 앞 앱의 것이면, 남은 화면 중 가장 최근 것의 앱으로
                if (e.packageName == foreground && resumed.values.none { it == foreground }) {
                    foreground = resumed.values.lastOrNull()
                }
            }
        }
        if (resumed.size > 40) resumed.remove(resumed.keys.first())
    }

    private var lastDay: String? = null

    private fun step() {
        // 새벽 4시가 지나면 하루 기록이 넘어간다 (알림은 보내지 않음)
        val day = store.dateKey()
        if (lastDay != null && day != lastDay) updateNotification()
        lastDay = day

        val now = System.currentTimeMillis()
        val events = usm.queryEvents(lastQuery, now)
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) { events.getNextEvent(e); track(e) }
        lastQuery = now

        val watching = power.isInteractive && foreground in store.targets()
        if (watching != lastWatching) {
            if (watching) store.onWatchStart(now) else store.onWatchStop(now)
            lastWatching = watching; updateNotification()
        }
        // 쉬는 중에는 30초마다 알림을 고쳐서 "새 세션까지 N분"을 맞춘다
        if (!watching && now - lastRestNotify > 30_000) { lastRestNotify = now; updateNotification() }
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
        if (tick.sessionSec / 60 != lastNotifiedMin) { lastNotifiedMin = tick.sessionSec / 60; updateNotification() }
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel("watch") // 0.3.0 이전 채널 (중요도가 낮아 숨겨졌음)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "이번 시청 남은 시간", NotificationManager.IMPORTANCE_LOW).apply {
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
        val nb = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_pengom)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        val today = "오늘 총 ${store.usedMin()}분 사용 · ${store.sessionsToday()}회 시청"
        if (!store.inSession()) {
            return nb.setShowWhen(false).setContentTitle("1회 ${store.sessionMin()}분 · 대기 중").setContentText(today).build()
        }
        val lim = store.sessionLimit()
        val left = lim * 60 - store.sessionSec()
        val over = left < 0
        val rest = store.breakLeftSec()
        nb.setContentTitle(
            when {
                over -> "이번 시청 시간 종료"
                store.sessionExtra() > 0 -> "이번 시청 남은 시간 (연장 +${store.sessionExtra()}분)"
                else -> "이번 시청 남은 시간 · 1회 ${store.sessionMin()}분"
            }
        ).setContentText(
            if (!lastWatching && rest > 0) "쉬는 중 · ${(rest + 59) / 60}분 더 쉬면 새로 시작 · $today" else today
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
    private var lastRestNotify = 0L

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

        fun start(ctx: Context) {
            val i = Intent(ctx, UsageWatchService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }
}
