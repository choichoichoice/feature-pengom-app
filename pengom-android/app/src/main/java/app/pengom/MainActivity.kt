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

    inner class Bridge {
        @JavascriptInterface fun getState(): String = store.stateJson()

        @JavascriptInterface fun isCounting(): Boolean = Perms.all(this@MainActivity)

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
            return a.toString()
        }

        @JavascriptInterface fun setApp(key: String, on: Boolean) = store.setApp(key, on)

        @JavascriptInterface fun clearAll() = store.clearAll()

        @JavascriptInterface fun appVersion(): String =
            try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (e: Exception) { "" }

        @JavascriptInterface fun setPromise(budgetMin: Int, reason: String, th: String, overTh: String) =
            store.setPromise(budgetMin, reason, th, overTh)

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
