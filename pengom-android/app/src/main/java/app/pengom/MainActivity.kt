package app.pengom

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * 앱 본 화면은 웹 프로토타입(assets/web/index.html)을 그대로 쓴다.
 * 웹 화면은 window.PengomNative 로 실제 사용 시간을 읽고 아침 약속을 저장한다.
 */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var store: CounterStore

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = CounterStore(this)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            webViewClient = WebViewClient()
            addJavascriptInterface(Bridge(), "PengomNative")
            loadUrl("file:///android_asset/web/index.html")
        }
        setContentView(web)
    }

    override fun onResume() {
        super.onResume()
        if (Perms.all(this)) UsageWatchService.start(this)
        // 설정 화면이나 인스타에서 돌아오면 바로 최신 시간으로
        web.evaluateJavascript("window.__pengomNativeRefresh && window.__pengomNativeRefresh()", null)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    /** 아직 없는 권한 하나를 골라 그 설정 화면을 연다 */
    private fun askNextPermission() {
        when {
            !Perms.usage(this) ->
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            !Perms.overlay(this) ->
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ->
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            else -> UsageWatchService.start(this)
        }
    }

    private fun labelOf(pkg: String): String? = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { null }

    /** 설정 화면에서 돌아오면 권한 체크리스트를 다시 그리게 알린다 */
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        web.evaluateJavascript("window.__pengomNativeRefresh && window.__pengomNativeRefresh()", null)
    }

    inner class Bridge {
        @JavascriptInterface fun getState(): String = store.stateJson()

        @JavascriptInterface fun isCounting(): Boolean = Perms.all(this@MainActivity)

        /** 세션 시간을 다 쓴 뒤 +5분 (세션당 두 번까지) */
        @JavascriptInterface fun extend(min: Int) = store.extend(min)

        /** 설정: 잴 앱 목록 [{key, label, on, installed}] */
        @JavascriptInterface fun apps(): String {
            val a = org.json.JSONArray()
            for (app in CounterStore.APPS) {
                val installed = app.pkgs.any { pkg ->
                    try { packageManager.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }
                }
                a.put(org.json.JSONObject().put("key", app.key).put("label", app.label)
                    .put("on", store.isAppOn(app.key)).put("installed", installed))
            }
            // 사용자가 추가한 앱 (지운 앱은 목록에서 뺀다)
            for (pkg in store.customApps()) {
                val label = labelOf(pkg) ?: continue
                a.put(org.json.JSONObject().put("key", pkg).put("label", label)
                    .put("on", true).put("installed", true).put("custom", true))
            }
            return a.toString()
        }

        @JavascriptInterface fun setApp(key: String, on: Boolean) = store.setApp(key, on)

        /** "+ 다른 앱 추가": 홈 화면에서 실행할 수 있는 앱 목록 [{pkg, label, on}] (LAUNCHER queries 로 보이는 앱만) */
        @JavascriptInterface fun launcherApps(): String {
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val skip = CounterStore.APPS.flatMap { it.pkgs }.toSet() + packageName
            val custom = store.customApps()
            val seen = HashSet<String>()
            val list = packageManager.queryIntentActivities(i, 0).mapNotNull { r ->
                val pkg = r.activityInfo.packageName
                if (pkg in skip || !seen.add(pkg)) null else Pair(pkg, r.loadLabel(packageManager).toString())
            }.sortedBy { it.second }
            val a = org.json.JSONArray()
            list.forEach { (pkg, label) -> a.put(org.json.JSONObject().put("pkg", pkg).put("label", label).put("on", pkg in custom)) }
            return a.toString()
        }

        @JavascriptInterface fun setCustomApp(pkg: String, on: Boolean) = store.setCustomApp(pkg, on)

        @JavascriptInterface fun setReason(reason: String) = store.setReason(reason)

        /** 권한 하나씩: 온보딩 체크리스트용 */
        @JavascriptInterface fun permState(): String = org.json.JSONObject()
            .put("usage", Perms.usage(this@MainActivity))
            .put("overlay", Perms.overlay(this@MainActivity))
            .put("notify", Build.VERSION.SDK_INT < 33 ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
            .toString()

        @JavascriptInterface fun openUsageSettings() = runOnUiThread {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }

        @JavascriptInterface fun openOverlaySettings() = runOnUiThread {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }

        @JavascriptInterface fun requestNotify() = runOnUiThread {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        /** 감지 중인 앱의 실제 아이콘 (작은 PNG, data URL). 없으면 빈 문자열 */
        @JavascriptInterface fun appIcon(key: String): String {
            // key: 기본 앱 키(youtube 등) 또는 사용자가 추가한 앱의 패키지 이름
            val pkgs = CounterStore.APPS.firstOrNull { it.key == key }?.pkgs ?: setOf(key)
            for (pkg in pkgs) {
                val d = try { packageManager.getApplicationIcon(pkg) } catch (e: Exception) { continue }
                val size = 72
                val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
                val c = android.graphics.Canvas(bmp)
                d.setBounds(0, 0, size, size); d.draw(c)
                val out = java.io.ByteArrayOutputStream()
                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                return "data:image/png;base64," + android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
            }
            return ""
        }

        @JavascriptInterface fun clearAll() = store.clearAll()

        @JavascriptInterface fun wakeMin(): Int = store.wakeMin()

        @JavascriptInterface fun setWakeMin(min: Int) = store.setWakeMin(min)

        @JavascriptInterface fun appVersion(): String =
            try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (e: Exception) { "" }

        /** 1회 시청 시간(분)과 아낀 시간에 할 일 */
        @JavascriptInterface fun setSession(min: Int, reason: String) = store.setSession(min, reason)

        /** 이름은 웹 화면과 맞추느라 그대로 둠: 필요한 권한 화면을 차례로 연다 */
        @JavascriptInterface fun openAccessibilitySettings() = runOnUiThread { askNextPermission() }

        /** 테스트용 빌드인가 (테스트 도구는 이때만 보인다) */
        @JavascriptInterface fun isDebug(): Boolean =
            (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

        /** 배터리 최적화에서 빠져 있는가 (빠져 있지 않으면 폰이 시간 재기를 끌 수 있다) */
        @JavascriptInterface fun batteryOk(): Boolean =
            (getSystemService(POWER_SERVICE) as android.os.PowerManager).isIgnoringBatteryOptimizations(packageName)

        /** 시스템 "허용/거부" 창을 바로 띄운다. 안 되는 폰이면 배터리 최적화 목록으로 */
        @JavascriptInterface fun openBatterySettings() = runOnUiThread {
            @SuppressLint("BatteryLife")
            val ask = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            try { startActivity(ask) } catch (e: Exception) {
                try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                catch (e2: Exception) { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
            }
        }
    }
}
