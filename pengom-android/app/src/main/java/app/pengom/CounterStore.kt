package app.pengom

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 오늘 쇼츠 앱을 본 시간(초), 아침 약속(예산 분·이유·개입 지점 분)을 폰 안에만 저장한다.
 * 날짜가 바뀌면 어제 시간을 기록(history)으로 옮기고 오늘 시간을 0으로 되돌린다.
 *
 * 서비스와 앱 화면이 각자 CounterStore 를 만들고 서로 다른 스레드에서 쓰므로,
 * 읽고-고쳐-쓰는 동작은 모두 공용 LOCK 으로 묶는다.
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

    /** 하루는 새벽 4시에 바뀐다 (자정 넘어 보는 쇼츠도 그날 기록) */
    private fun logicalDay() = LocalDateTime.now().minusHours(DAY_START_HOUR).toLocalDate()
    private fun today(): String = logicalDay().toString()

    /** 오늘(새벽 4시 기준 하루)이 시작된 시각. 시스템 기록으로 다시 계산할 때 여기서부터 센다 */
    fun dayStartMillis(): Long =
        logicalDay().atStartOfDay().plusHours(DAY_START_HOUR)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun roll() { synchronized(LOCK) {
        val d = today()
        val cur = p.getString(K_DATE, null)
        if (cur == d) return
        val e = p.edit()
        if (cur != null) {
            val hist = history()
            hist.put(cur, p.getInt(K_USED, 0))
            val keys = hist.keys().asSequence().sorted().toList()
            keys.dropLast(60).forEach { hist.remove(it) }
            e.putString(K_HIST, hist.toString())
        }
        e.putString(K_DATE, d).putInt(K_USED, 0).putString(K_FIRED, "").putBoolean(K_ACK, false)
            .remove(K_BUDGET).remove(K_TH).remove(K_OVER_TH).remove(K_EXTRA).remove(K_EXT_COUNT)
            .putBoolean(K_AUTO, false)
        // 오늘 약속을 아직 안 했으면 어제 약속을 그대로 쓴다 (앱을 안 열고 쇼츠부터 켜도 끝이 있게)
        if (p.contains(K_LAST_BUDGET)) {
            val b = p.getInt(K_LAST_BUDGET, 0)
            e.putInt(K_BUDGET, b).putString(K_TH, JSONArray(thresholds(b)).toString())
                .putString(K_OVER_TH, "[]").putBoolean(K_AUTO, true)
        }
        e.apply()
    } }

    private fun history(): JSONObject =
        try { JSONObject(p.getString(K_HIST, "{}") ?: "{}") } catch (e: Exception) { JSONObject() }

    fun usedSec(): Int { roll(); return p.getInt(K_USED, 0) }
    fun usedMin(): Int = usedSec() / 60
    fun budget(): Int? { roll(); return if (p.contains(K_BUDGET)) p.getInt(K_BUDGET, 0) else null }
    fun reason(): String = p.getString(K_REASON, "다른 거") ?: "다른 거"
    /** 아침에 한 약속을 넘겼는가 (더 받은 시간과 상관없이) */
    fun isOver(): Boolean { val b = budget() ?: return false; return usedSec() > b * 60 }
    /** 약속을 넘긴 뒤 더 받은 시간(분). 더 받기 전에 이미 넘겨 본 시간도 여기에 들어간다 */
    fun extra(): Int { roll(); return p.getInt(K_EXTRA, 0) }
    /** 오늘 "5분 더"를 받은 횟수 */
    fun extendCount(): Int {
        roll()
        // 0.6.1 까지는 횟수를 따로 안 적었다: 그때는 한 번에 5분씩이었으니 분으로 셈
        return if (p.contains(K_EXT_COUNT)) p.getInt(K_EXT_COUNT, 0) else (p.getInt(K_EXTRA, 0) + 4) / 5
    }
    /** 오늘 볼 수 있는 끝: 약속 + 더 받은 시간 */
    fun limit(): Int? = budget()?.let { it + extra() }
    fun overLimit(): Boolean { val l = limit() ?: return false; return usedSec() > l * 60 }
    /** 더 받기는 하루 MAX_EXTENDS 번까지 */
    fun canExtend(): Boolean = extendCount() < MAX_EXTENDS

    /**
     * "5분 더": 누른 지금부터 min 분을 더 볼 수 있게 끝을 늦춘다.
     * 시트를 띄워둔 채 한참 보다가 누르거나 홈에서 늦게 눌러도, 받은 5분이 이미 지나버린 상태가 되지 않게 한다.
     */
    fun extend(min: Int) { synchronized(LOCK) {
        roll()
        val b = budget() ?: return
        if (!canExtend()) return
        val n = extendCount() + 1 // extra 를 바꾸기 전에 센다 (예전 기록은 extra 로 횟수를 셈)
        val used = p.getInt(K_USED, 0)
        val cur = p.getInt(K_EXTRA, 0)
        val fromNow = (used + 59) / 60 + min - b
        p.edit().putInt(K_EXTRA, maxOf(cur + min, fromNow))
            .putInt(K_EXT_COUNT, n)
            .putBoolean(K_ACK, false).apply()
    } }

    private fun ints(key: String): List<Int> = try {
        val a = JSONArray(p.getString(key, "[]")); List(a.length()) { a.getInt(it) }
    } catch (e: Exception) { emptyList() }

    /**
     * 아침 약속 또는 하루 중 약속 시간 변경. 변경 때는 이미 지난 개입 지점만 "이미 울림"으로 두고,
     * 새 약속이 지금 쓴 시간보다 크면 초과 시트가 다시 뜰 수 있게 한다.
     */
    fun setPromise(budgetMin: Int, reason: String, th: String, overTh: String) { synchronized(LOCK) {
        roll()
        p.edit().putInt(K_BUDGET, budgetMin).putString(K_REASON, reason)
            .putString(K_TH, th).putString(K_OVER_TH, overTh)
            .putInt(K_LAST_BUDGET, budgetMin).putBoolean(K_AUTO, false).apply()
        val usedMin = p.getInt(K_USED, 0) / 60.0
        val fired = ints(K_TH).withIndex().filter { usedMin >= it.value }.map { "t${it.index}" } +
            ints(K_OVER_TH).withIndex().filter { usedMin >= it.value }.map { "o${it.index}" }
        val e = p.edit().putString(K_FIRED, fired.joinToString(","))
        if (usedMin <= budgetMin) e.putBoolean(K_ACK, false)
        e.apply()
    } }

    fun ackOver() { p.edit().putBoolean(K_ACK, true).apply() }

    /** 서비스가 다시 시작됐을 때, 시스템 기록으로 계산한 오늘 시간이 더 크면 그 값으로 맞춘다 */
    fun raiseUsedTo(sec: Int) { synchronized(LOCK) {
        roll()
        if (sec > p.getInt(K_USED, 0)) p.edit().putInt(K_USED, sec).apply()
    } }

    fun addSeconds(sec: Int): Tick = synchronized(LOCK) {
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
        Tick(now, b, line, crossed, prev / 60 != now / 60)
    }

    // 웹 setBudget 과 같은 개입 지점: 41%, 72%, 2분 전 (5의 배수는 피함)
    private fun thresholds(b: Int): List<Int> {
        fun noRound(v: Double): Int { var x = Math.round(v).toInt(); while (x % 5 == 0) x += 1; return x }
        return listOf(noRound(b * 0.41), noRound(b * 0.72), maxOf(1, b - 2)).filter { it < b }
    }

    // 웹(scroll-melt/index.html)의 TH_LINES 와 같은 말
    private fun thLine(i: Int, t: Int, b: Int): String = when (i) {
        0 -> "벌써 ${t}분 봤어"
        1 -> "${t}분째. 아직 괜찮아"
        else -> "${b - t}분 남았다. 마지막 영상 골라"
    }

    /** 웹 화면으로 넘기는 상태 (used, history 는 초 단위) */
    fun stateJson(): String = synchronized(LOCK) {
        roll()
        JSONObject()
            .put("date", p.getString(K_DATE, today()))
            .put("used", p.getInt(K_USED, 0))
            .put("budget", budget() ?: JSONObject.NULL)
            .put("extra", p.getInt(K_EXTRA, 0))
            .put("extCount", extendCount())
            .put("canExtend", canExtend())
            .put("auto", p.getBoolean(K_AUTO, false))
            .put("history", history())
            .toString()
    }

    companion object {
        private val LOCK = Any()
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
        private const val K_EXT_COUNT = "extendCount"
        private const val K_LAST_BUDGET = "lastBudgetMin"
        private const val K_AUTO = "autoBudget"
        private const val DAY_START_HOUR = 4L
        /** 하루에 "5분 더"를 받을 수 있는 횟수 */
        const val MAX_EXTENDS = 2
    }
}
