package app.pengom

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 오늘 쇼츠 앱을 본 시간(초), 아침 약속(예산 분·이유·개입 지점 분)을 폰 안에만 저장한다.
 * 날짜가 바뀌면 어제 시간을 기록(history)으로 옮기고 오늘 시간을 0으로 되돌린다.
 */
class CounterStore(context: Context) {

    data class Tick(
        val usedSec: Int,
        val budgetMin: Int?,
        val line: String?,
        val crossedBudget: Boolean,
        val minuteChanged: Boolean,
    )

    private val p = context.applicationContext.getSharedPreferences("pengom", Context.MODE_PRIVATE)

    /**
     * 하루는 사용자가 정한 기상 시간에 바뀐다 (사람마다 생활 시간이 달라서).
     * 밤늦게 본 쇼츠는 전날 기록으로 들어간다.
     */
    fun wakeMin(): Int = p.getInt(K_WAKE, DEFAULT_WAKE_MIN)
    fun setWakeMin(min: Int) { p.edit().putInt(K_WAKE, min.coerceIn(0, 24 * 60 - 1)).apply() }

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
    fun targets(): Set<String> { val off = offApps(); return APPS.filter { it.key !in off }.flatMap { it.pkgs }.toSet() }

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
        }
        e.putString(K_DATE, d).putInt(K_USED, 0).putString(K_FIRED, "").putBoolean(K_ACK, false)
            .remove(K_BUDGET).remove(K_TH).remove(K_OVER_TH).remove(K_EXTRA).putBoolean(K_AUTO, false)
        // 오늘 약속을 아직 안 했으면 어제 약속을 그대로 쓴다 (앱을 안 열고 쇼츠부터 켜도 끝이 있게)
        if (p.contains(K_LAST_BUDGET)) {
            val b = p.getInt(K_LAST_BUDGET, 0)
            e.putInt(K_BUDGET, b).putString(K_TH, JSONArray(thresholds(b)).toString())
                .putString(K_OVER_TH, "[]").putBoolean(K_AUTO, true)
        }
        e.apply()
    }

    private fun history(): JSONObject =
        try { JSONObject(p.getString(K_HIST, "{}") ?: "{}") } catch (e: Exception) { JSONObject() }

    fun usedSec(): Int { roll(); return p.getInt(K_USED, 0) }
    fun usedMin(): Int = usedSec() / 60
    fun budget(): Int? { roll(); return if (p.contains(K_BUDGET)) p.getInt(K_BUDGET, 0) else null }
    fun reason(): String = p.getString(K_REASON, "다른 거") ?: "다른 거"
    /** 아침에 한 약속을 넘겼는가 (더 받은 시간과 상관없이) */
    fun isOver(): Boolean { val b = budget() ?: return false; return usedSec() > b * 60 }
    /** 약속을 다 쓴 뒤 "5분 더"로 더 받은 시간(분) */
    fun extra(): Int { roll(); return p.getInt(K_EXTRA, 0) }
    /** 오늘 볼 수 있는 끝: 약속 + 더 받은 시간 */
    fun limit(): Int? = budget()?.let { it + extra() }
    fun overLimit(): Boolean { val l = limit() ?: return false; return usedSec() > l * 60 }
    /** 더 받기는 하루 MAX_EXTRA 분(5분씩 두 번)까지 */
    fun canExtend(): Boolean = extra() < MAX_EXTRA
    /** 시간을 다 쓴 뒤에만 더 받는다 */
    @Synchronized
    fun extend(min: Int) {
        roll()
        if (!canExtend()) return
        p.edit().putInt(K_EXTRA, p.getInt(K_EXTRA, 0) + min).putBoolean(K_ACK, false).apply()
    }

    private fun ints(key: String): List<Int> = try {
        val a = JSONArray(p.getString(key, "[]")); List(a.length()) { a.getInt(it) }
    } catch (e: Exception) { emptyList() }

    /**
     * 아침 약속 또는 하루 중 약속 시간 변경. 변경 때는 이미 지난 개입 지점만 "이미 울림"으로 두고,
     * 새 약속이 지금 쓴 시간보다 크면 초과 시트가 다시 뜰 수 있게 한다.
     */
    @Synchronized
    fun setPromise(budgetMin: Int, reason: String, th: String, overTh: String) {
        roll()
        p.edit().putInt(K_BUDGET, budgetMin).putString(K_REASON, reason)
            .putString(K_TH, th).putString(K_OVER_TH, overTh)
            .putInt(K_LAST_BUDGET, budgetMin).putBoolean(K_AUTO, false).apply()
        val usedMin = p.getInt(K_USED, 0) / 60.0
        val fired = ints(K_TH).withIndex().filter { usedMin >= it.value }.map { "t${it.index}" } +
            ints(K_OVER_TH).withIndex().filter { usedMin >= it.value }.map { "o${it.index}" }
        val e = p.edit().putString(K_FIRED, fired.joinToString(","))
        // 총 허용 시간(목표 + 연장)보다 덜 썼으면 다시 끝날 때 시트가 뜨게
        if (usedMin <= budgetMin + p.getInt(K_EXTRA, 0)) e.putBoolean(K_ACK, false)
        e.apply()
    }

    fun ackOver() { p.edit().putBoolean(K_ACK, true).apply() }

    /** 서비스가 다시 시작됐을 때, 시스템 기록으로 계산한 오늘 시간이 더 크면 그 값으로 맞춘다 */
    @Synchronized
    fun raiseUsedTo(sec: Int) {
        roll()
        if (sec > p.getInt(K_USED, 0)) p.edit().putInt(K_USED, sec).apply()
    }

    @Synchronized
    fun addSeconds(sec: Int): Tick {
        roll()
        val prev = p.getInt(K_USED, 0)
        val now = prev + sec
        val b = budget()
        val lim = limit()
        val pm = prev / 60.0
        val nm = now / 60.0
        val fired = (p.getString(K_FIRED, "") ?: "").split(',').filter { it.isNotEmpty() }.toMutableSet()
        var line: String? = null
        var crossed = false
        if (b != null) {
            ints(K_TH).forEachIndexed { i, t ->
                if (pm < t && nm >= t && "t$i" !in fired) {
                    fired += "t$i"
                    line = thLine(i, t, b)
                }
            }
            if (lim != null && pm <= lim && nm > lim && !p.getBoolean(K_ACK, false)) crossed = true
        }
        p.edit().putInt(K_USED, now).putString(K_FIRED, fired.joinToString(",")).apply()
        return Tick(now, b, line, crossed, prev / 60 != now / 60)
    }

    // 웹 setBudget 과 같은 개입 지점: 41%, 72%, 2분 전 (5의 배수는 피함)
    private fun thresholds(b: Int): List<Int> {
        fun noRound(v: Double): Int { var x = Math.round(v).toInt(); while (x % 5 == 0) x += 1; return x }
        return listOf(noRound(b * 0.41), noRound(b * 0.72), maxOf(1, b - 2)).filter { it < b }
    }

    // 웹(scroll-melt/index.html)의 TH_LINES 와 같은 말
    private fun thLine(i: Int, t: Int, b: Int): String = when (i) {
        0 -> "벌써 ${t}분 봤어요."
        1 -> "${t}분째예요. 빙하가 녹고 있어요."
        else -> "${b - t}분 남았어요. 슬슬 마무리해 볼까요?"
    }

    /** 웹 화면으로 넘기는 상태 (used, history 는 초 단위) */
    fun stateJson(): String {
        roll()
        return JSONObject()
            .put("date", p.getString(K_DATE, today()))
            .put("used", p.getInt(K_USED, 0))
            .put("budget", budget() ?: JSONObject.NULL)
            .put("extra", p.getInt(K_EXTRA, 0))
            .put("canExtend", canExtend())
            .put("auto", p.getBoolean(K_AUTO, false))
            .put("wake", wakeMin())
            .put("history", history())
            .toString()
    }

    companion object {
        private const val K_DATE = "date"
        private const val K_USED = "usedSec"
        private const val K_HIST = "historySec"
        private const val K_BUDGET = "budgetMin"
        private const val K_REASON = "reason"
        private const val K_TH = "thMin"
        private const val K_OVER_TH = "overThMin"
        private const val K_FIRED = "fired"
        private const val K_ACK = "overAck"
        private const val K_EXTRA = "extraMin"
        private const val K_LAST_BUDGET = "lastBudgetMin"
        private const val K_AUTO = "autoBudget"
        private const val K_WAKE = "wakeMin"
        const val DEFAULT_WAKE_MIN = 7 * 60
        private const val K_OFF_APPS = "offApps"
        val APPS = listOf(
            App("instagram", "인스타그램", setOf("com.instagram.android")),
            App("youtube", "유튜브", setOf("com.google.android.youtube")),
            App("tiktok", "틱톡", setOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")),
        )
        const val MAX_EXTRA = 10
    }
}
