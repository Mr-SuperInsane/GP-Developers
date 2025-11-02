package com.example.moodle

import android.Manifest // Manifest を import
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent // Intent を import
import android.content.pm.PackageManager // PackageManager を import
import android.net.Uri // Uri を import
import android.os.Build // Build を import
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts // Permission request contract
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat // ContextCompat を import
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private val moodleUpdateWorkTag = "moodleUpdateWork"

    // --- 通知パーミッションリクエスト用のランチャーを定義 ---
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                // パーミッションが許可された場合の処理 (Toast表示など)
                Toast.makeText(this, "通知権限が許可されました", Toast.LENGTH_SHORT).show()
                println("MainActivity: Notification permission granted by user.")
            } else {
                // パーミッションが拒否された場合の処理 (Toast表示など)
                Toast.makeText(this, "通知を表示するには権限が必要です", Toast.LENGTH_SHORT).show()
                println("MainActivity: Notification permission denied by user.")
                // 必要であれば、なぜ権限が必要か再度説明するUIを表示する
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // --- Viewの参照を取得 ---
        val usernameInput = findViewById<EditText>(R.id.username_input)
        val passwordInput = findViewById<EditText>(R.id.password_input) // ← パスワード入力欄
        val togglePasswordButton = findViewById<ImageButton>(R.id.toggle_password_visibility)
        val saveButton = findViewById<Button>(R.id.save_button)
        val statusText = findViewById<TextView>(R.id.status_text)
        val intervalInput = findViewById<EditText>(R.id.update_interval_input)
        val notificationThresholdInput = findViewById<EditText>(R.id.notification_threshold_input) // ← 通知時間入力欄

        // --- 起動時に保存済みの情報を読み込んで表示 ---
        val (savedUsername, savedPassword) = CredentialsManager.loadCredentials(this) // ← パスワードも受け取る
        usernameInput.setText(savedUsername)
        passwordInput.setText(savedPassword) // ← パスワードを表示する行を追加！

        val savedInterval = CredentialsManager.loadUpdateInterval(this)
        intervalInput.setText(savedInterval.toString())
        val savedThreshold = CredentialsManager.loadNotificationThreshold(this) // ← 通知時間も読み込む
        notificationThresholdInput.setText(savedThreshold.toString()) // ← 通知時間を表示

        // --- パスワード表示/非表示の切り替え ---
        var isPasswordVisible = false
        // 初期状態は非表示に設定
        passwordInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        togglePasswordButton.setImageResource(R.drawable.ic_visibility_on) // ★要画像: 表示アイコン (初期状態用)

        togglePasswordButton.setOnClickListener {
            isPasswordVisible = !isPasswordVisible
            if (isPasswordVisible) {
                passwordInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                togglePasswordButton.setImageResource(R.drawable.ic_visibility_off) // ★要画像: 非表示アイコン
            } else {
                passwordInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                togglePasswordButton.setImageResource(R.drawable.ic_visibility_on) // ★要画像: 表示アイコン
            }
            // カーソル位置を最後に移動
            passwordInput.setSelection(passwordInput.text.length)
        }

        // --- 保存ボタンの処理 ---
        saveButton.setOnClickListener {
            val username = usernameInput.text.toString()
            val password = passwordInput.text.toString()
            val intervalString = intervalInput.text.toString()
            val thresholdString = notificationThresholdInput.text.toString() // ← 通知時間入力値を取得

            // 不正な値や空の場合はデフォルト値、1未満は1時間に補正
            val intervalHours = intervalString.toLongOrNull()?.coerceAtLeast(1L) ?: 6L
            val notificationThresholdHours = thresholdString.toLongOrNull()?.coerceAtLeast(1L) ?: 12L // ← 通知時間も補正

            // 補正した値をEditTextにも反映
            intervalInput.setText(intervalHours.toString())
            notificationThresholdInput.setText(notificationThresholdHours.toString()) // ← 通知時間も反映


            if (username.isNotBlank() && password.isNotBlank()) {
                // 情報を保存 (更新間隔と通知時間も一緒に保存)
                CredentialsManager.saveCredentials(this, username, password, intervalHours, notificationThresholdHours) // ← notificationThresholdHours を追加
                statusText.text = "保存しました！ウィジェットが更新されます。\n自動更新: $intervalHours 時間ごと\n通知: $notificationThresholdHours 時間前" // ← メッセージ更新

                // WorkManagerに定期実行タスクをスケジュール
                schedulePeriodicWork(intervalHours)

                // ウィジェットの即時更新をシステムに依頼
                triggerWidgetUpdate()

            } else {
                statusText.text = "IDとパスワードを入力してください。"
            }
        }

        // --- onCreateでIntentをチェック (ウィジェットからの起動など) ---
        handleIntent(intent)

        // --- 必要なら通知権限をリクエスト ---
        requestNotificationPermission()
    }

    // --- Activityが既に起動中に新しいIntentを受け取った場合の処理 ---
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // 新しい Intent で Activity が再開された場合も handleIntent を呼ぶ
        intent?.let { handleIntent(it) }
    }


    // --- Intent を処理して、必要ならブラウザを開く関数 ---
    private fun handleIntent(intent: Intent) {
        // デバッグ用に Intent の詳細をログ出力
        println("MainActivity: handleIntent called. Action: ${intent.action}, Has Extra URL: ${intent.hasExtra(MoodleWidgetProvider.EXTRA_ITEM_URL)}, Widget ID: ${intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)}")

        // ウィジェットからのカスタム Action かつ URL Extra があるかチェック
        if (intent.action == MoodleWidgetProvider.ACTION_WIDGET_ITEM_CLICK && intent.hasExtra(MoodleWidgetProvider.EXTRA_ITEM_URL)) {
            val urlString = intent.getStringExtra(MoodleWidgetProvider.EXTRA_ITEM_URL)
            println("MainActivity: Intent matched widget click. URL String: '$urlString'")

            if (!urlString.isNullOrBlank()) {
                println("MainActivity: URL is valid, attempting to launch browser...")
                try {
                    // ブラウザを開く Intent を作成して起動
                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(urlString)).apply {
                        // 外部アプリを起動する際は NEW_TASK フラグが推奨される
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(browserIntent)
                    println("MainActivity: startActivity called for browser.")
                    // ブラウザを開いたらMainActivityは閉じる (必要ならコメント解除)
                    // finish()

                } catch (e: Exception) {
                    // URL形式が不正などのエラー処理
                    e.printStackTrace()
                    Toast.makeText(this, "URLを開けませんでした: $urlString", Toast.LENGTH_LONG).show()
                    println("MainActivity: Error opening URL: ${e.message}")
                }
                // 重要: finish() しない場合、処理済み Intent のクリアが必要な場合がある
                // setIntent(Intent()) // 例: Intentを空にして通常起動と同じにする

            } else {
                println("MainActivity: Received null or blank URL string from widget.")
            }
        } else {
            // 通常起動や他の Intent の場合
            println("MainActivity: Launched normally or intent did not match widget click criteria.")
        }
    }


    // --- 通知権限をリクエストする関数 ---
    private fun requestNotificationPermission() {
        // Android 13 (APIレベル 33) 以降の場合のみ処理が必要
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                // すでに権限が付与されているかチェック
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    // 権限は既に許可されている
                    println("MainActivity: POST_NOTIFICATIONS permission already granted.")
                }
                // 権限リクエストの根拠を示すべきか (一度拒否されたことがあるか)
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    // ユーザーが一度拒否したが「今後は表示しない」は選択していない場合
                    println("MainActivity: Showing rationale for POST_NOTIFICATIONS permission.")
                    // ここで AlertDialog などを表示して、なぜ通知権限が必要なのかを説明するのが望ましい
                    // 説明の後、再度パーミッションリクエストを発行
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                else -> {
                    // 初めてリクエストする場合、またはユーザーが「今後は表示しない」を選択した場合
                    println("MainActivity: Requesting POST_NOTIFICATIONS permission.")
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } else {
            // Android 12L (APIレベル 32) 以前では、権限リクエストは不要
            println("MainActivity: Notification permission is not required before Android 13.")
        }
    }


    // --- ウィジェットの即時更新をトリガーする関数 ---
    private fun triggerWidgetUpdate() {
        val manager = AppWidgetManager.getInstance(this)
        val component = ComponentName(this, MoodleWidgetProvider::class.java)
        val widgetIds = manager.getAppWidgetIds(component)

        // データ更新が必要なことを Service に伝えるため、Provider に Intent を送る
        val intent = Intent(this, MoodleWidgetProvider::class.java).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, widgetIds)
        }
        sendBroadcast(intent)
        println("MainActivity: Sent broadcast to trigger widget update.")
    }

    // --- WorkManagerで定期実行をスケジュールする関数 ---
    private fun schedulePeriodicWork(intervalHours: Long) {
        // 定期実行リクエストを作成
        val periodicWorkRequest = PeriodicWorkRequestBuilder<MoodleUpdateWorker>(
            intervalHours, TimeUnit.HOURS
        ).build()

        // WorkManagerにタスクを登録 (既存のタスクがあれば置き換える)
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            moodleUpdateWorkTag, // タスクのユニークな名前
            ExistingPeriodicWorkPolicy.REPLACE, // 既存タスクポリシー
            periodicWorkRequest
        )
        println("WorkManager: Scheduled/Updated periodic work with interval $intervalHours hours.")
        Toast.makeText(this, "自動更新を${intervalHours}時間ごとにスケジュールしました", Toast.LENGTH_SHORT).show()
    }
}