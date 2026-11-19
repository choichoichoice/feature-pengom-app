package app.pengom

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 폰을 다시 켜거나 앱을 새 버전으로 업데이트해도 시간 재기를 이어간다 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val restart = intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (restart && Perms.all(context)) {
            UsageWatchService.start(context)
        }
    }
}
