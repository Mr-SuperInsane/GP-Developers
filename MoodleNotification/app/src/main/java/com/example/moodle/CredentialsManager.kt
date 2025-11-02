package com.example.moodle

import android.content.Context
import android.content.SharedPreferences

object CredentialsManager {

    private const val PREFS_NAME = "MoodleWidgetPrefs"
    private const val KEY_USERNAME = "username"
    private const val KEY_PASSWORD = "password" // 暗号化推奨
    private const val KEY_UPDATE_INTERVAL = "updateInterval"
    // ▼▼▼ 通知時間用のキーを追加 ▼▼▼
    private const val KEY_NOTIFICATION_THRESHOLD = "notificationThreshold"


    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // 保存 (引数に notificationThresholdHours を追加)
    fun saveCredentials(context: Context, username: String, password: String?, updateIntervalHours: Long, notificationThresholdHours: Long) { // notificationThresholdHours を追加
        getPrefs(context).edit().apply {
            putString(KEY_USERNAME, username)
            putString(KEY_PASSWORD, password) // 本番では暗号化を検討
            putLong(KEY_UPDATE_INTERVAL, updateIntervalHours)
            // ▼▼▼ 通知時間を保存 ▼▼▼
            putLong(KEY_NOTIFICATION_THRESHOLD, notificationThresholdHours)
            apply()
        }
        println("CredentialsManager: Saved credentials, interval($updateIntervalHours H), threshold($notificationThresholdHours H)")
    }

    // 読み込み (変更なし)
    fun loadCredentials(context: Context): Pair<String?, String?> {
        val prefs = getPrefs(context)
        val username = prefs.getString(KEY_USERNAME, null)
        val password = prefs.getString(KEY_PASSWORD, null) // 復号処理が必要な場合も
        println("CredentialsManager: Loaded credentials. Username exists: ${username != null}")
        return Pair(username, password)
    }

    // 更新間隔の読み込み (変更なし)
    fun loadUpdateInterval(context: Context): Long {
        val interval = getPrefs(context).getLong(KEY_UPDATE_INTERVAL, 6L) // デフォルト6時間
        println("CredentialsManager: Loaded update interval: $interval H")
        return interval
    }

    // ▼▼▼ 通知時間の読み込み関数を追加 ▼▼▼
    fun loadNotificationThreshold(context: Context): Long {
        val threshold = getPrefs(context).getLong(KEY_NOTIFICATION_THRESHOLD, 12L) // デフォルト12時間
        println("CredentialsManager: Loaded notification threshold: $threshold H")
        return threshold
    }

}