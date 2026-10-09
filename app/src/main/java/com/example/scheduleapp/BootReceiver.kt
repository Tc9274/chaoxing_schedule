package com.example.scheduleapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 手机重启后重新调度每天 8:00 的后台刷新。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            DailyRefreshReceiver.scheduleDaily(context)
        }
    }
}
