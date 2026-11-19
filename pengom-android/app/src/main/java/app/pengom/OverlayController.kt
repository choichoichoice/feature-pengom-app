package app.pengom

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 다른 앱 위에 뜨는 화면들 ("다른 앱 위에 표시" 권한 필요).
 *  - 카운터: 화면 위쪽 가운데 "17분 · 14분 남음"
 *  - 한마디: 캐릭터 얼굴 + 말풍선 (정해둔 분에 도달할 때, 2.8초)
 *  - 약속 초과: 아래에서 올라오는 시트 "네가 정한 거야. 내가 정한 거 아니고."
 */
class OverlayController(
    private val svc: Context,
    private val store: CounterStore,
    private val goHome: () -> Unit,
) {
    private val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val density = svc.resources.displayMetrics.density
    private fun px(v: Int) = (v * density).toInt()

    private var pill: LinearLayout? = null
    private var pillNum: TextView? = null
    private var pillSub: TextView? = null
    private var pillIcon: ImageView? = null
    private var toast: View? = null
    private var sheet: View? = null
    private val hideToastTask = Runnable { removeToast() }

    private fun params(w: Int, h: Int, touchable: Boolean) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT,
    )

    private fun rounded(color: Int, radiusDp: Int, strokeDp: Int = 0, strokeColor: Int = 0) =
        GradientDrawable().apply {
            setColor(color); cornerRadius = px(radiusDp).toFloat()
            if (strokeDp > 0) setStroke(px(strokeDp), strokeColor)
        }

    private fun dimen(name: String): Int {
        val id = svc.resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) svc.resources.getDimensionPixelSize(id) else 0
    }

    private fun safeRemove(v: View) = try { wm.removeView(v) } catch (_: Exception) {}

    // ---------------- 카운터 ----------------
    fun show() {
        if (pill != null) { refresh(); return }
        val row = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(8), px(5), px(14), px(5))
            background = rounded(0xE60E161E.toInt(), 20, 1, 0x40FFFFFF)
        }
        pillIcon = ImageView(svc).apply { setImageResource(R.drawable.mascot_head) }
        row.addView(pillIcon, LinearLayout.LayoutParams(px(26), px(26)).apply { marginEnd = px(6) })
        pillNum = TextView(svc).apply { setTextColor(Color.WHITE); textSize = 17f; typeface = Typeface.DEFAULT_BOLD; fontFeatureSettings = "tnum" }
        pillSub = TextView(svc).apply { textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setPadding(px(8), 0, 0, 0) }
        row.addView(pillNum); row.addView(pillSub)
        val lp = params(WRAP_CONTENT, WRAP_CONTENT, false).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dimen("status_bar_height") + px(6)
        }
        try { wm.addView(row, lp); pill = row } catch (_: Exception) { return }
        refresh()
        // 시간을 다 쓴 채로 다시 들어오면 시트를 다시 보여준다
        if (store.overLimit()) main.post { showSheet() }
    }

    fun hide() {
        pill?.let { safeRemove(it) }; pill = null
        removeToast()
        sheet?.let { safeRemove(it) }; sheet = null
    }

    /** 약속(그리고 더 받은 시간)에서 1초씩 줄어드는 남은 시간 */
    private fun refresh() {
        val used = store.usedSec(); val b = store.budget(); val lim = store.limit()
        val red = 0xFFFF9D80.toInt(); val amber = 0xFFFFC56B.toInt()
        var color = Color.WHITE; var sub = 0xCCFFFFFF.toInt()
        when {
            b == null || lim == null -> { pillNum?.text = "${used / 60}분"; pillSub?.text = "약속 전" }
            used <= b * 60 -> { pillNum?.text = clock(b * 60 - used); pillSub?.text = "남음" }
            used <= lim * 60 -> { pillNum?.text = clock(lim * 60 - used); pillSub?.text = "더 받은 시간"; color = amber; sub = amber }
            else -> { pillNum?.text = "+" + clock(used - lim * 60); pillSub?.text = "초과"; color = red; sub = red }
        }
        pillNum?.setTextColor(color)
        pillSub?.setTextColor(sub)
        pillIcon?.setImageResource(if (store.isOver()) R.drawable.mascot_head_melt else R.drawable.mascot_head)
    }

    private fun clock(sec: Int): String {
        val s = sec.coerceAtLeast(0)
        val h = s / 3600; val m = (s % 3600) / 60; val r = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, r) else "%d:%02d".format(m, r)
    }

    fun onTick(t: CounterStore.Tick) {
        show()
        refresh()
        if (!t.minuteChanged && t.line == null && !t.crossedBudget) return
        pill?.animate()?.scaleX(1.08f)?.scaleY(1.08f)?.setDuration(90)
            ?.withEndAction { pill?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(140)?.start() }?.start()
        if (t.crossedBudget) showSheet() else t.line?.let { showToast(it) }
    }

    // ---------------- 한마디 ----------------
    private fun showToast(text: String) {
        removeToast()
        val card = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(10), px(8), px(16), px(8))
            background = rounded(Color.WHITE, 22)
            elevation = px(10).toFloat()
        }
        card.addView(ImageView(svc).apply {
            setImageResource(if (store.isOver()) R.drawable.mascot_head_melt else R.drawable.mascot_head)
        }, LinearLayout.LayoutParams(px(48), px(48)).apply { marginEnd = px(10) })
        card.addView(TextView(svc).apply {
            this.text = text; setTextColor(0xFF17232E.toInt()); textSize = 15f; typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val wrap = FrameLayout(svc).apply { setPadding(px(12), 0, px(12), px(12)) }
        wrap.addView(card, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val lp = params(MATCH_PARENT, WRAP_CONTENT, false).apply {
            gravity = Gravity.TOP
            y = dimen("status_bar_height") + px(52)
        }
        try { wm.addView(wrap, lp); toast = wrap } catch (_: Exception) { return }
        card.translationY = -px(40).toFloat(); card.alpha = 0f
        card.animate().translationY(0f).alpha(1f).setDuration(380).setInterpolator(OvershootInterpolator(1.6f)).start()
        vibrate(longArrayOf(0, 25))
        main.removeCallbacks(hideToastTask)
        main.postDelayed(hideToastTask, 2800)
    }

    private fun removeToast() {
        main.removeCallbacks(hideToastTask)
        toast?.let { safeRemove(it) }; toast = null
    }

    // ---------------- 약속 초과 시트 ----------------
    private fun showSheet() {
        if (sheet != null) return
        removeToast()
        val r = px(28).toFloat()
        val dim = FrameLayout(svc).apply { setBackgroundColor(0x8C000000.toInt()) }
        val card = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(px(20), px(16), px(20), px(20) + dimen("navigation_bar_height"))
            background = GradientDrawable().apply {
                setColor(0xFFF6FAFC.toInt()); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
        }
        card.addView(ImageView(svc).apply { setImageResource(R.drawable.mascot_head_melt) },
            LinearLayout.LayoutParams(px(104), px(104)))
        val first = store.extra() == 0
        val more = store.canExtend()
        card.addView(TextView(svc).apply {
            text = when { first -> "${store.budget()}분 다 됐어."; more -> "더 받은 ${store.extra()}분도 끝."; else -> "오늘은 여기까지." }
            textSize = 23f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setTextColor(0xFF17232E.toInt()); setLineSpacing(0f, 1.15f)
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = px(8) })
        card.addView(TextView(svc).apply {
            val reason = store.reason()
            text = (if (reason != "다른 거") "아침에 ‘$reason’ 한다고 했잖아.\n" else "") +
                (if (more) "더 보면 오늘 기록에 남아." else "더 받는 건 하루 두 번까지야.")
            textSize = 14f; setTextColor(0xFF687B8B.toInt()); gravity = Gravity.CENTER
            setPadding(px(12), px(10), px(12), px(10))
            background = rounded(Color.WHITE, 14, 2, 0xFFE1EAF0.toInt())
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = px(14) })

        val row = LinearLayout(svc).apply { orientation = LinearLayout.HORIZONTAL }
        val keep = button("5분만 더", Color.WHITE, 0xFF17232E.toInt(), 0xFFE1EAF0.toInt())
        val leave = button("그만 볼래", 0xFF1FA7E8.toInt(), Color.WHITE, 0xFF1583B9.toInt())
        if (more) row.addView(keep, LinearLayout.LayoutParams(0, px(54), 1f).apply { marginEnd = px(10) })
        row.addView(leave, LinearLayout.LayoutParams(0, px(54), 1f))
        card.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = px(16) })

        dim.addView(card, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        try { wm.addView(dim, params(MATCH_PARENT, MATCH_PARENT, true)); sheet = dim } catch (_: Exception) { return }
        card.translationY = px(400).toFloat()
        card.animate().translationY(0f).setDuration(420).setInterpolator(DecelerateInterpolator(2f)).start()
        vibrate(longArrayOf(0, 30, 60, 30))

        keep.setOnClickListener {
            store.extend(5); removeSheet(); refresh()
            main.postDelayed({ showToast("딱 5분이다?") }, 250)
        }
        leave.setOnClickListener { store.ackOver(); removeSheet(); goHome() }
    }

    private fun removeSheet() { sheet?.let { safeRemove(it) }; sheet = null }

    private fun button(label: String, bg: Int, fg: Int, edge: Int) = TextView(svc).apply {
        text = label; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        setTextColor(fg); background = rounded(bg, 18, 2, edge); isClickable = true
    }

    private fun vibrate(pattern: LongArray) {
        try {
            @Suppress("DEPRECATION")
            val v = svc.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (_: Exception) {}
    }
}
