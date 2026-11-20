package app.pengom

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.HapticFeedbackConstants
import android.view.animation.LinearInterpolator
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.MotionEvent
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
 *  - 카운터: 화면 위쪽 가운데 "14:59 남음" (목표 + 연장 기준으로 1초씩 줄어듦)
 *  - 한마디: 캐릭터 얼굴 + 말풍선 (정해둔 분에 도달할 때, 2.8초)
 *  - 1회 시청 시간 끝: 아래에서 올라오는 시트 "이번엔 15분 다 봤어요." [여기서 멈추기] [+5분 연장하기(꾹)]
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
        if (store.sessionOver()) main.post { showSheet() }
    }

    fun hide() {
        pill?.let { safeRemove(it) }; pill = null
        removeToast()
        sheet?.let { safeRemove(it) }; sheet = null
    }

    /** 이번 시청 세션의 남은 시간: 1회 시간(그리고 연장)에서 1초씩 줄어든다 */
    private fun refresh() {
        val used = store.sessionSec(); val base = store.sessionMin(); val lim = store.sessionLimit()
        val red = 0xFFFF9D80.toInt(); val amber = 0xFFFFC56B.toInt()
        var color = Color.WHITE; var sub = 0xCCFFFFFF.toInt()
        when {
            used <= base * 60 -> { pillNum?.text = clock(base * 60 - used); pillSub?.text = "남음" }
            used <= lim * 60 -> { pillNum?.text = clock(lim * 60 - used); pillSub?.text = "남음 (연장)"; color = amber; sub = amber }
            else -> { pillNum?.text = "+" + clock(used - lim * 60); pillSub?.text = "초과"; color = red; sub = red }
        }
        pillNum?.setTextColor(color)
        pillSub?.setTextColor(sub)
        pillIcon?.setImageResource(if (store.sessionPastBase()) R.drawable.mascot_head_melt else R.drawable.mascot_head)
    }

    private fun clock(sec: Int): String {
        val s = sec.coerceAtLeast(0)
        val h = s / 3600; val m = (s % 3600) / 60; val r = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, r) else "%d:%02d".format(m, r)
    }

    fun onTick(t: CounterStore.Tick) {
        show()
        refresh()
        if (!t.minuteChanged && t.line == null && !t.crossedLimit) return
        pill?.animate()?.scaleX(1.08f)?.scaleY(1.08f)?.setDuration(90)
            ?.withEndAction { pill?.animate()?.scaleX(1f)?.scaleY(1f)?.setDuration(140)?.start() }?.start()
        if (t.crossedLimit) showSheet() else t.line?.let { showToast(it) }
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
            setImageResource(if (store.sessionPastBase()) R.drawable.mascot_head_melt else R.drawable.mascot_head)
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

    // ---------------- 세션 종료 시트: [여기서 멈추기] / [+5분 연장하기(꾹 눌러 게이지 채우기)] ----------------
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
        // 펭곰이 얼굴: 연한 하늘색 원 안에 담는다. 그림 아래 일자 단면이 원 테두리에 가려지도록 아래쪽에 붙여 배치.
        val badge = FrameLayout(svc).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFE2F2FB.toInt()) }
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            clipToOutline = true
        }
        badge.addView(ImageView(svc).apply { setImageResource(R.drawable.mascot_head_melt); scaleType = ImageView.ScaleType.FIT_CENTER },
            FrameLayout.LayoutParams(px(100), px(100), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = -px(10) })
        card.addView(badge, LinearLayout.LayoutParams(px(108), px(108)))

        val first = store.sessionExtra() == 0
        val more = store.canExtend()
        // 제목
        card.addView(TextView(svc).apply {
            text = when {
                first -> "약속한 ${store.sessionMin()}분이 지났어요!"
                more -> "연장한 ${store.sessionExtra()}분도 다 지났어요!"
                else -> "이번 시청은 여기까지예요!"
            }
            textSize = 22f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setTextColor(0xFF17232E.toInt()); setLineSpacing(0f, 1.15f)
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = px(12) })
        // 서브 문구 1줄: 아낀 시간에 할 일이 있으면 그것, 없으면 쉬어 가자는 말
        card.addView(TextView(svc).apply {
            val reason = store.reason()
            text = if (reason != "다른 거" && reason.isNotBlank()) "아낀 시간엔 ‘$reason’ 하기로 했어요." else "빙하가 녹기 전에 잠깐 쉬어 갈까요?"
            textSize = 15f; setTextColor(0xFF4F6576.toInt()); gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = px(6) })
        // 요약 박스: 오늘 누적만 한 줄 (연한 배경, 테두리 없음)
        card.addView(TextView(svc).apply {
            text = "오늘 총 ${store.usedMin()}분 · ${store.sessionsToday()}회 시청"
            textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(0xFF2B3E4E.toInt()); gravity = Gravity.CENTER
            setPadding(px(14), px(11), px(14), px(11))
            background = rounded(0xFFEAF4FA.toInt(), 14)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = px(14) })

        // 위: 여기서 멈추기(주 버튼) / 아래: +5분 연장하기(꾹 누르면 게이지가 0→100% 차오름)
        val leave = button("여기서 멈추기", 0xFF1FA7E8.toInt(), Color.WHITE, 0xFF1583B9.toInt())
        card.addView(leave, LinearLayout.LayoutParams(MATCH_PARENT, px(56)).apply { topMargin = px(16) })
        val gauge = ClipDrawable(rounded(0xFFBFE3F7.toInt(), 18), Gravity.START, ClipDrawable.HORIZONTAL)
        val keep = TextView(svc).apply {
            text = KEEP_LABEL; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setTextColor(0xFF17232E.toInt()); isClickable = true
            background = LayerDrawable(arrayOf(rounded(Color.WHITE, 18, 2, 0xFFE1EAF0.toInt()), gauge))
        }
        if (more) card.addView(keep, LinearLayout.LayoutParams(MATCH_PARENT, px(56)).apply { topMargin = px(10) })
        // 맨 아래 작은 캡션: 쿨다운 / 연장 한도 안내 (박스와 분리)
        card.addView(TextView(svc).apply {
            val b = CounterStore.BREAK_MIN
            text = if (more) "${b}분 쉬었다 켜면 새 타이머로 시작해요" else "연장은 한 번 볼 때 두 번까지예요 · ${b}분 쉬면 새로 시작해요"
            textSize = 12.5f; setTextColor(0xFF8A9AA8.toInt()); gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = px(12) })

        dim.addView(card, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        try { wm.addView(dim, params(MATCH_PARENT, MATCH_PARENT, true)); sheet = dim } catch (_: Exception) { return }
        card.translationY = px(400).toFloat()
        card.animate().translationY(0f).setDuration(420).setInterpolator(DecelerateInterpolator(2f)).start()
        vibrate(longArrayOf(0, 30, 60, 30))

        // 1.8초 동안 꾹 누르면 게이지가 다 차고 +5분. 중간에 떼면 게이지가 부드럽게 되돌아간다.
        var anim: ValueAnimator? = null
        fun runGauge(to: Int, ms: Long, done: (() -> Unit)? = null) {
            anim?.cancel()
            anim = ValueAnimator.ofInt(gauge.level, to).apply {
                duration = ms
                interpolator = if (done != null) LinearInterpolator() else DecelerateInterpolator()
                addUpdateListener { gauge.level = it.animatedValue as Int; keep.invalidate() }
                if (done != null) addListener(object : AnimatorListenerAdapter() {
                    var cancelled = false
                    override fun onAnimationCancel(a: Animator) { cancelled = true }
                    override fun onAnimationEnd(a: Animator) { if (!cancelled) done() }
                })
                start()
            }
        }
        keep.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    keep.text = "계속 누르고 있어요…"
                    val left = ((10000 - gauge.level) / 10000.0 * HOLD_MS).toLong()
                    runGauge(10000, left) {
                        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        vibrate(longArrayOf(0, 45))
                        store.extend(); removeSheet(); refresh()
                        main.postDelayed({ showToast("5분만 더 볼게요! 이번엔 꼭 멈추기로 해요.") }, 250)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (gauge.level < 10000) { keep.text = KEEP_LABEL; runGauge(0, 320) }
                    true
                }
                else -> true
            }
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

    companion object {
        private const val HOLD_MS = 1800L
        private const val KEEP_LABEL = "+5분 더 보기 (꾹 누르기)"
    }
}
