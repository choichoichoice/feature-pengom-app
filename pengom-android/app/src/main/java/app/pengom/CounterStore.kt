package app.pengom

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 1회 시청 세션(1회 시간·연장·쉬는 시간)과 오늘 총 사용 시간(기록용)을 폰 안에만 저장한다.
 * 날짜(기상 시간 기준)가 바뀌면 어제 기록을 history 로 옮기고 오늘 값을 0으로 되돌린다.
 * 예전 버전의 "하루 목표" 키(budgetMin 등)는 남아 있어도 더는 쓰지 않는다.
 */
class CounterStore(context: Context) {

    data class Tick(
        val usedSec: Int,          // 오늘 총 사용(초) — 기록용
        val sessionSec: Int,       // 이번 시청 세션 사용(초)
        val limitMin: Int,         // 이번 세션 허용(분) = 1회 시간 + 연장
        val line: String?,
        val crossedLimit: Boolean, // 이번 틱에 세션 허용 시간을 넘었는가
        val minuteChanged: Boolean,
    )

    private val p = context.applicationContext.getSharedPreferences("pengom", Context.MODE_PRIVATE)

    /**
     * 하루 누적 기록은 매일 새벽 4시에 넘어간다 (설정 없음). 밤늦게 본 시간은 전날 기록으로 들어간다.
     * 예전 버전의 기상 시간 설정 값이 남아 있어도 쓰지 않는다. 날짜가 거꾸로 가는 일은 roll() 이 막는다.
     */
    fun wakeMin(): Int = DAY_START_MIN
    @Suppress("UNUSED_PARAMETER") fun setWakeMin(min: Int) { /* 기상 시간 설정은 없앴다 */ }

    private fun today(): String = LocalDateTime.now().minusMinutes(wakeMin().toLong()).toLocalDate().toString()

    /** 오늘 날짜(기상 시간 기준). 바뀌었으면 하루를 넘긴다 */
    fun dateKey(): String { roll(); return p.getString(K_DATE, today()) ?: today() }

    /** 오늘이 시작된 시각 (기상 시간) */
    fun dayStartMillis(): Long {
        val w = wakeMin().toLong()
        return LocalDateTime.now().minusMinutes(w).toLocalDate().atStartOfDay().plusMinutes(w)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    // ---------- 잴 앱 ----------
    data class App(val key: String, val label: String, val pkgs: Set<String>)

    private fun offApps(): Set<String> =
        (p.getString(K_OFF_APPS, "") ?: "").split(',').filter { it.isNotEmpty() }.toSet()

    /** 사용자가 끄지 않은 앱들의 패키지 이름 */
    fun targets(): Set<String> { val off = offApps(); return APPS.filter { it.key !in off }.flatMap { it.pkgs }.toSet() + customApps() }

    /** 사용자가 "+ 다른 앱 추가"로 넣은 앱 (패키지 이름) */
    fun customApps(): Set<String> =
        (p.getString(K_CUSTOM_APPS, "") ?: "").split(',').filter { it.isNotEmpty() }.toSet()

    fun setCustomApp(pkg: String, on: Boolean) {
        val s = customApps().toMutableSet()
        if (on) s += pkg else s -= pkg
        p.edit().putString(K_CUSTOM_APPS, s.joinToString(",")).apply()
    }

    /** 아낀 시간에 할 일만 바꾼다 (오버레이 문구에 바로 반영) */
    fun setReason(reason: String) { p.edit().putString(K_REASON, reason.ifBlank { "다른 거" }).apply() }

    fun isAppOn(key: String): Boolean = key !in offApps()

    fun setApp(key: String, on: Boolean) {
        val off = offApps().toMutableSet()
        if (on) off -= key else off += key
        p.edit().putString(K_OFF_APPS, off.joinToString(",")).apply()
    }

    /** 기록 지우기: 이 폰에 저장한 모든 것을 지운다 */
    fun clearAll() { p.edit().clear().apply() }

    @Synchronized
    private fun roll() {
        val d = today()
        val cur = p.getString(K_DATE, null)
        if (cur == d) return
        // 기상 시간을 늦췄을 때 날짜가 거꾸로 가지 않게: 새 기준은 다음 날부터
        if (cur != null && d < cur) return
        val e = p.edit()
        if (cur != null) {
            val hist = history()
            hist.put(cur, p.getInt(K_USED, 0))
            val keys = hist.keys().asSequence().sorted().toList()
            keys.dropLast(60).forEach { hist.remove(it) }
            e.putString(K_HIST, hist.toString())
            val hs = jsonOf(K_HIST_SESS); hs.put(cur, p.getInt(K_SESSIONS, 0)); e.putString(K_HIST_SESS, trim(hs).toString())
            val he = jsonOf(K_HIST_EXT); he.put(cur, p.getInt(K_EXT_TODAY, 0)); e.putString(K_HIST_EXT, trim(he).toString())
            val hl = jsonOf(K_HIST_SLIST); hl.put(cur, todayList()); e.putString(K_HIST_SLIST, trim(hl).toString())
        }
        e.putInt(K_SESSIONS, 0).putInt(K_EXT_TODAY, 0).putBoolean(K_S_ACTIVE, false).putString(K_TODAY_SLIST, "[]")
        e.putString(K_DATE, d).putInt(K_USED, 0)
        // 예전 버전(하루 목표 방식)의 값은 더 쓰지 않으니 지운다
        LEGACY_KEYS.forEach { e.remove(it) }
        e.apply()
    }

    private fun history(): JSONObject =
        try { JSONObject(p.getString(K_HIST, "{}") ?: "{}") } catch (e: Exception) { JSONObject() }

    fun usedSec(): Int { roll(); return p.getInt(K_USED, 0) }
    fun usedMin(): Int = usedSec() / 60
    fun reason(): String = p.getString(K_REASON, "다른 거") ?: "다른 거"

    // ================= 1회 시청 세션 =================
    // 대상 앱을 켜면 세션이 시작된다. 앱을 나가 BREAK_MIN 분 이상 쉬면 다음에 켤 때 새 세션.
    // 그보다 빨리 돌아오면 같은 세션이 이어진다. 하루 총 사용 시간은 기록용일 뿐 막지 않는다.

    private fun jsonOf(key: String): JSONObject =
        try { JSONObject(p.getString(key, "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
    private fun trim(o: JSONObject): JSONObject { o.keys().asSequence().sorted().toList().dropLast(60).forEach { o.remove(it) }; return o }

    // 오늘 세션 목록: [[시작(epoch 초), 본 시간(초), 연장 횟수], ...] — 기록 화면의 세션 블록·시간대·멈춤 성공률용
    private fun todayList(): JSONArray = try { JSONArray(p.getString(K_TODAY_SLIST, "[]") ?: "[]") } catch (e: Exception) { JSONArray() }
    private fun bumpLast(dSec: Int, dExt: Int, e: android.content.SharedPreferences.Editor) {
        val l = todayList(); if (l.length() == 0) return
        val last = l.getJSONArray(l.length() - 1)
        last.put(1, last.optInt(1) + dSec); last.put(2, last.optInt(2) + dExt)
        e.putString(K_TODAY_SLIST, l.toString())
    }

    /**
     * 서비스가 꺼져 있던 동안 생긴 세션을 시스템 기록으로 다시 만든다.
     * segs: [[시작(epoch 초), 본 시간(초)], ...] — 저장된 목록보다 더 많이 봤거나 세션이 많으면 그걸로 바꾼다.
     */
    @Synchronized
    fun rebuildToday(segs: List<Pair<Long, Int>>) {
        roll()
        val l = todayList()
        var storedSec = 0; for (i in 0 until l.length()) storedSec += l.getJSONArray(i).optInt(1)
        val newSec = segs.sumOf { it.second }
        if (segs.size <= l.length() && newSec <= storedSec + 60) return
        val out = JSONArray()
        segs.forEachIndexed { i, (start, sec) ->
            // 저장돼 있던 세션의 연장 횟수는 같은 순서의 세션에 그대로 붙인다
            val x = if (i < l.length()) l.getJSONArray(i).optInt(2) else 0
            out.put(JSONArray().put(start).put(sec).put(x))
        }
        p.edit().putString(K_TODAY_SLIST, out.toString())
            .putInt(K_SESSIONS, maxOf(p.getInt(K_SESSIONS, 0), segs.size)).apply()
    }

    fun sessionMin(): Int = p.getInt(K_SESSION_MIN, DEFAULT_SESSION_MIN)
    fun sessionSec(): Int { roll(); return p.getInt(K_S_USED, 0) }
    fun sessionExtra(): Int { roll(); return p.getInt(K_S_EXTRA, 0) }
    fun sessionLimit(): Int = sessionMin() + sessionExtra()
    fun sessionsToday(): Int { roll(); return p.getInt(K_SESSIONS, 0) }
    fun extendsToday(): Int { roll(); return p.getInt(K_EXT_TODAY, 0) }
    /** 이번 세션이 1회 시간을 넘었는가 (연장 포함 여부와 상관없이) — 펭곰이 녹는 표정용 */
    fun sessionPastBase(): Boolean = sessionSec() > sessionMin() * 60
    /** 이번 세션 허용 시간(1회 + 연장)을 넘었는가 */
    fun sessionOver(): Boolean = inSession() && sessionSec() > sessionLimit() * 60
    /** 연장은 세션당 MAX_EXTENDS 번 */
    fun canExtend(): Boolean = sessionExtra() < MAX_EXTENDS * EXTEND_MIN

    /** 앱을 나간 뒤 쉰 시간을 따져 아직 같은 세션인가 */
    fun inSession(now: Long = System.currentTimeMillis()): Boolean {
        roll()
        if (!p.getBoolean(K_S_ACTIVE, false)) return false
        val left = p.getLong(K_S_LEFT_AT, 0L)
        return left == 0L || now - left < BREAK_MIN * 60_000L
    }
    /** 새 세션까지 더 쉬어야 하는 시간(초). 쉬는 중이 아니면 0 */
    fun breakLeftSec(now: Long = System.currentTimeMillis()): Int {
        val left = p.getLong(K_S_LEFT_AT, 0L)
        if (!inSession(now) || left == 0L) return 0
        return ((BREAK_MIN * 60_000L - (now - left)) / 1000).toInt().coerceAtLeast(0)
    }

    @Synchronized
    fun onWatchStart(now: Long = System.currentTimeMillis()) {
        roll()
        val e = p.edit()
        if (!inSession(now)) {
            e.putBoolean(K_S_ACTIVE, true).putInt(K_S_USED, 0).putInt(K_S_EXTRA, 0)
                .putBoolean(K_S_ACK, false).putString(K_S_FIRED, "")
                .putInt(K_SESSIONS, p.getInt(K_SESSIONS, 0) + 1)
                .putString(K_TODAY_SLIST, todayList().put(JSONArray().put(now / 1000).put(0).put(0)).toString())
        }
        e.putLong(K_S_LEFT_AT, 0L).apply()
    }

    @Synchronized
    fun onWatchStop(now: Long = System.currentTimeMillis()) {
        if (p.getBoolean(K_S_ACTIVE, false) && p.getLong(K_S_LEFT_AT, 0L) == 0L) p.edit().putLong(K_S_LEFT_AT, now).apply()
    }

    /** 1회 시청 시간 설정. 지금 세션에도 바로 적용된다 */
    @Synchronized
    fun setSession(min: Int, reason: String) {
        roll()
        val m = min.coerceIn(1, 120)
        val e = p.edit().putInt(K_SESSION_MIN, m).putString(K_REASON, reason)
        // 새 허용 시간보다 덜 봤으면 다시 끝날 때 시트가 뜨게
        if (p.getInt(K_S_USED, 0) <= (m + p.getInt(K_S_EXTRA, 0)) * 60) e.putBoolean(K_S_ACK, false)
        e.apply()
    }

    /** 세션 시간을 다 쓴 뒤에만 +5분 (세션당 두 번까지) */
    @Synchronized
    fun extend(min: Int = EXTEND_MIN) {
        roll()
        if (!canExtend()) return
        val e = p.edit().putInt(K_S_EXTRA, p.getInt(K_S_EXTRA, 0) + min).putBoolean(K_S_ACK, false)
            .putInt(K_EXT_TODAY, p.getInt(K_EXT_TODAY, 0) + 1)
        bumpLast(0, 1, e); e.apply()
    }



    fun ackOver() { p.edit().putBoolean(K_S_ACK, true).apply() }

    /** 서비스가 다시 시작됐을 때, 시스템 기록으로 계산한 오늘 시간이 더 크면 그 값으로 맞춘다 */
    @Synchronized
    fun raiseUsedTo(sec: Int) {
        roll()
        if (sec > p.getInt(K_USED, 0)) p.edit().putInt(K_USED, sec).apply()
    }

    @Synchronized
    fun addSeconds(sec: Int): Tick {
        roll()
        val day = p.getInt(K_USED, 0) + sec
        val prev = p.getInt(K_S_USED, 0)
        val now = prev + sec
        val base = sessionMin()
        val lim = base + p.getInt(K_S_EXTRA, 0)
        val pm = prev / 60.0
        val nm = now / 60.0
        val fired = (p.getString(K_S_FIRED, "") ?: "").split(',').filter { it.isNotEmpty() }.toMutableSet()
        var line: String? = null
        // 세션 중 개입: 절반, 끝나기 2분 전 (1회 시간 기준)
        sessionThresholds(base).forEachIndexed { i, t ->
            if (pm < t && nm >= t && "t$i" !in fired) { fired += "t$i"; line = thLine(i, t, base) }
        }
        val crossed = pm <= lim && nm > lim && !p.getBoolean(K_S_ACK, false)
        val e = p.edit().putInt(K_USED, day).putInt(K_S_USED, now).putString(K_S_FIRED, fired.joinToString(","))
        bumpLast(sec, 0, e); e.apply()
        return Tick(day, now, lim, line, crossed, prev / 60 != now / 60)
    }

    private fun sessionThresholds(b: Int): List<Double> =
        listOf(b / 2.0, (b - 2).toDouble()).filter { it > 0 && it < b }.distinct()


    // 웹(scroll-melt/index.html)의 TH_LINES 와 같은 말
    private fun thLine(i: Int, t: Double, b: Int): String = when (i) {
        0 -> "벌써 절반 봤어요. 아직 괜찮아요."
        else -> "빙하가 녹고 있어요. 슬슬 마무리해 볼까요?"
    }

    /** 웹 화면으로 넘기는 상태 (used, sessionUsed, history 는 초 단위) */
    fun stateJson(): String {
        roll()
        val now = System.currentTimeMillis()
        return JSONObject()
            .put("date", p.getString(K_DATE, today()))
            .put("used", p.getInt(K_USED, 0))
            .put("sessionMin", sessionMin())
            .put("sessionUsed", if (inSession(now)) p.getInt(K_S_USED, 0) else 0)
            .put("sessionExtra", if (inSession(now)) p.getInt(K_S_EXTRA, 0) else 0)
            .put("inSession", inSession(now))
            .put("watching", inSession(now) && p.getLong(K_S_LEFT_AT, 0L) == 0L)
            .put("breakLeft", breakLeftSec(now))
            .put("sessions", p.getInt(K_SESSIONS, 0))
            .put("extends", p.getInt(K_EXT_TODAY, 0))
            .put("canExtend", canExtend())
            .put("wake", wakeMin())
            .put("history", history())
            .put("historySessions", jsonOf(K_HIST_SESS))
            .put("historyExtends", jsonOf(K_HIST_EXT))
            .put("todaySessions", todayList())
            .put("historySessionList", jsonOf(K_HIST_SLIST))
            .toString()
    }

    companion object {
        private const val K_DATE = "date"
        private const val K_USED = "usedSec"
        private const val K_HIST = "historySec"
        private const val K_REASON = "reason"
        /** 예전 "하루 목표" 방식의 키 (하루가 바뀔 때 정리) */
        private val LEGACY_KEYS = listOf("budgetMin", "thMin", "overThMin", "fired", "overAck", "extraMin", "lastBudgetMin", "autoBudget")
        /** 하루가 바뀌는 시각: 새벽 4시 */
        const val DAY_START_MIN = 4 * 60
        private const val K_OFF_APPS = "offApps"
        private const val K_CUSTOM_APPS = "customApps"
        val APPS = listOf(
            App("instagram", "인스타그램", setOf("com.instagram.android")),
            App("youtube", "유튜브", setOf("com.google.android.youtube")),
            App("tiktok", "틱톡", setOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")),
        )
        // 1회 시청 세션
        private const val K_SESSION_MIN = "sessionMin"
        private const val K_S_USED = "sessionUsedSec"
        private const val K_S_EXTRA = "sessionExtraMin"
        private const val K_S_ACTIVE = "sessionActive"
        private const val K_S_LEFT_AT = "sessionLeftAt"
        private const val K_S_ACK = "sessionAck"
        private const val K_S_FIRED = "sessionFired"
        private const val K_SESSIONS = "sessionsToday"
        private const val K_EXT_TODAY = "extendsToday"
        private const val K_HIST_SESS = "historySessions"
        private const val K_HIST_EXT = "historyExtends"
        private const val K_TODAY_SLIST = "todaySessionList"
        private const val K_HIST_SLIST = "historySessionList"
        const val DEFAULT_SESSION_MIN = 15
        /** 앱을 나가 이만큼(분) 쉬면 다음에 켤 때 새 세션 */
        const val BREAK_MIN = 10
        const val EXTEND_MIN = 5
        const val MAX_EXTENDS = 2
    }
}
