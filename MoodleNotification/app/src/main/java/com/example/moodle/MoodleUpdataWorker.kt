package com.example.moodle

import android.Manifest // Manifest を import
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager // AppWidgetManager を import
import android.content.ComponentName    // ComponentName を import
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager // PackageManager を import
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat // ContextCompat を import
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

class MoodleUpdateWorker(appContext: Context, workerParams: WorkerParameters):
    CoroutineWorker(appContext, workerParams) {

    // --- 通知チャンネルIDと通知IDプレフィックス ---
    private val CHANNEL_ID = "MOODLE_ASSIGNMENT_REMINDER"
    private val NOTIFICATION_ID_PREFIX = 1000 // 通知IDのベース (課題ごとに変えるため)

    override suspend fun doWork(): Result {
        println("MoodleUpdateWorker: doWork() started.")
        val context = applicationContext
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val componentName = ComponentName(context, MoodleWidgetProvider::class.java)
        val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)

        // ウィジェットがなければ何もしない
        if (appWidgetIds.isEmpty()) {
            println("MoodleUpdateWorker: No widgets found. Skipping work.")
            return Result.success() // 成功として終了
        }

        // --- 認証情報を読み込む ---
        val (username, password) = CredentialsManager.loadCredentials(context)
        if (username.isNullOrBlank() || password.isNullOrBlank()) {
            println("MoodleUpdateWorker: Credentials not found. Cannot proceed.")
            // 認証情報がない場合、リトライしても意味がないので成功とするか、
            // 設定を促すために失敗とするかは設計次第 (今回は失敗としておく)
            return Result.failure()
        }

        try {
            // --- Moodleにログインしてセッションキーを取得 ---
            val sesskey = MoodleApiService.loginAndGetSesskey(username, password)
            if (sesskey != null) {
                // --- 課題を取得 (通知判定用に十分な期間を取得) ---
                val events = MoodleApiService.fetchAssignmentsFromApi(sesskey, 30) // 例: 30日分
                println("MoodleUpdateWorker: Fetched ${events.size} assignments for notification check.")

                // --- 保存された通知時間のしきい値を読み込む ---
                val notificationThresholdHours = CredentialsManager.loadNotificationThreshold(context)
                println("MoodleUpdateWorker: Using notification threshold: $notificationThresholdHours hours.")

                // --- 期限をチェックして必要なら通知 ---
                checkDueDatesAndNotify(events, context, notificationThresholdHours) // しきい値を渡す

            } else {
                println("MoodleUpdateWorker: Login failed during scheduled update.")
                // ログイン失敗。リトライさせるために failure() を返すことも検討できる
                // return Result.failure()
            }
        } catch (e: Exception) {
            // ネットワークエラーなど
            e.printStackTrace()
            println("MoodleUpdateWorker: Exception during update/notification check: ${e.message}")
            // 例外発生時もリトライするかどうか (failure() を返すとリトライポリシーに従う)
            // return Result.failure()
            // 今回はリトライさせずに次のスケジュールを待つ (success() を返す)
        }

        // --- ウィジェットの表示を更新するようシステムに通知 ---
        // (注: ログイン失敗や例外発生時でもウィジェット更新は試みる)
        println("MoodleUpdateWorker: Triggering widget update for ${appWidgetIds.size} widgets.")
        appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetIds, R.id.widget_list_view)
        println("MoodleUpdateWorker: Widget update notification sent.")

        return Result.success() // 処理完了 (成功)
    }

    /**
     * 課題リストをチェックし、指定された時間以内に期限が来るものがあれば通知する
     */
    private fun checkDueDatesAndNotify(events: List<MoodleEvent>, context: Context, thresholdHours: Long) {
        val now = Instant.now()
        // 指定された時間後の時刻を計算
        val notificationTimeLimit = now.plus(thresholdHours, ChronoUnit.HOURS)

        // 通知チャンネルを作成 (Android 8.0以降で必要)
        createNotificationChannel(context)

        events.forEach { event ->
            // イベントの期限を取得 (nullなら0秒として扱う)
            val dueDate = Instant.ofEpochSecond(event.timesort ?: 0L)

            // 期限が現在より後で、かつ指定した時間制限より前かチェック
            if (dueDate.isAfter(now) && dueDate.isBefore(notificationTimeLimit)) {
                println("MoodleUpdateWorker: Assignment due within $thresholdHours H: ${event.name}")
                // 通知を表示
                showNotification(event, context, thresholdHours) // しきい値を渡す
            }
        }
    }

    /**
     * 通知チャンネルを作成する (Android 8.0 Oreo以降で必要)
     * 冪等性があるため、毎回呼んでも問題ない
     */
    private fun createNotificationChannel(context: Context) {
        // APIレベル 26 (Oreo) 以上でのみ実行
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "課題期限リマインダー" // ユーザーに表示されるチャンネル名
            val descriptionText = "Moodle課題の期限が近づくと通知します" // チャンネルの説明
            val importance = NotificationManager.IMPORTANCE_DEFAULT // 通知の重要度
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                // ここでライト、バイブレーションなどの設定も可能
                // enableLights(true)
                // lightColor = Color.RED
                // enableVibration(true)
            }
            // システムにチャンネルを登録
            val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            println("MoodleUpdateWorker: Notification channel '$CHANNEL_ID' ensured to exist.")
        }
    }

    /**
     * 指定された課題イベントの通知を表示する
     */
    private fun showNotification(event: MoodleEvent, context: Context, thresholdHours: Long) {
        // 通知タップ時に MainActivity を開く Intent を作成
        val intent = Intent(context, MainActivity::class.java).apply {
            // 新しいタスクで開くか、既存タスクを前面に持ってくる
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            // 必要なら MainActivity にどの通知から来たかなどの情報を渡す
            // putExtra("notification_event_id", event.id)
        }
        // PendingIntent を作成 (requestCodeは通知ごとにユニークにする必要があるためevent.idを使用)
        // event.idがnullの場合を考慮し、hashCodeを使うなどのフォールバックが必要な場合もある
        val pendingIntent: PendingIntent = PendingIntent.getActivity(
            context,
            event.id ?: event.hashCode(), // requestCode (ユニークである必要がある)
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT // IMMUTABLE推奨 + Extra更新用
        )


        // 通知を構築
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_icon) // ★★★★★ 要変更: 白黒の通知用アイコン ★★★★★
            .setContentTitle("課題期限接近: ${event.name}") // 通知タイトル
            .setContentText("期限まであと${thresholdHours}時間以内です") // 通知本文
            .setPriority(NotificationCompat.PRIORITY_DEFAULT) // 通知の優先度
            .setContentIntent(pendingIntent) // タップ時のアクション
            .setAutoCancel(true) // タップ後に通知を自動で消す

        // --- 通知権限のチェック (Android 13 Tiramasu以降) ---
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                // パーミッションがない場合は通知を出せない
                println("MoodleUpdateWorker: POST_NOTIFICATIONS permission not granted. Cannot show notification.")
                // Workerから権限リクエストはできないため、ここで処理を終了
                return
            }
        }


        // --- 通知を表示 ---
        // 通知IDを課題ごとにユニークにする (同じ課題の通知は上書きされるように)
        val notificationId = NOTIFICATION_ID_PREFIX + (event.id ?: event.hashCode())

        with(NotificationManagerCompat.from(context)) {
            notify(notificationId, builder.build())
            println("MoodleUpdateWorker: Notification shown for event ID ${event.id}. Notification ID: $notificationId (Threshold: $thresholdHours H)")
        }
    }
}