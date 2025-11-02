package com.example.moodle

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import android.net.Uri // Uri を import
// このサービスは、ListViewにデータを供給するための「工場」のようなもの
class MoodleWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return MoodleRemoteViewsFactory(this.applicationContext)
    }
}

// 工場の中で実際にリストの各行を作っている「作業員」
class MoodleRemoteViewsFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {

    private var events = listOf<MoodleEvent>()

    // データが変更されたときに呼ばれる
    // MoodleRemoteViewsFactory 内

    override fun onDataSetChanged() {
        // ▼▼▼ ログ追加 ▼▼▼
        println("MoodleRemoteViewsFactory: onDataSetChanged started.")

        // SharedPreferencesからID/パスワードを読み込む
        val (username, password) = CredentialsManager.loadCredentials(context)
        if (username.isNullOrBlank() || password.isNullOrBlank()) {
            events = emptyList()
            // ▼▼▼ ログ追加 ▼▼▼
            println("MoodleRemoteViewsFactory: Credentials not found, events cleared.")
            println("MoodleRemoteViewsFactory: onDataSetChanged finished.") // 処理終了ログ
            return
        }

        // 同期的にネットワーク処理を実行
        runBlocking {
            try {
                val sesskey = MoodleApiService.loginAndGetSesskey(username, password)
                if (sesskey != null) {
                    // 30日先までの課題を取得
                    events = MoodleApiService.fetchAssignmentsFromApi(sesskey, 30)
                    // ▼▼▼ ログ追加 ▼▼▼
                    println("MoodleRemoteViewsFactory: Fetched ${events.size} events.")
                } else {
                    events = emptyList()
                    // ▼▼▼ ログ追加 ▼▼▼
                    println("MoodleRemoteViewsFactory: Login failed (sesskey was null), events cleared.")
                }
            } catch (e: Exception) {
                e.printStackTrace() // エラーの詳細を標準エラー出力に表示
                events = emptyList()
                // ▼▼▼ ログ追加 ▼▼▼
                println("MoodleRemoteViewsFactory: Exception occurred during API call, events cleared. Error: ${e.message}")
            }
        }
        // ▼▼▼ ログ追加 ▼▼▼
        println("MoodleRemoteViewsFactory: onDataSetChanged finished.")
    }


    // MoodleWidgetService.kt の MoodleRemoteViewsFactory 内

    override fun getViewAt(position: Int): RemoteViews {
        println("getViewAt called for position: $position")

        if (position < 0 || position >= events.size) {
            return RemoteViews(context.packageName, R.layout.widget_list_item)
        }

        val event = events[position]
        val views = RemoteViews(context.packageName, R.layout.widget_list_item)

        // タイトルと期限の設定 (変更なし)
        views.setTextViewText(R.id.assignment_title, event.name)
        val formattedDate = Instant.ofEpochSecond(event.timesort ?: 0L)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()
            .format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))
        views.setTextViewText(R.id.assignment_due_date, "期限: $formattedDate")

        // --- ここからクリック処理 (元のブラウザを開くコード) ---

        // 1. この行固有のデータ（URL）を持つ Intent (fillInIntent) を作る
        // 1. この行固有のデータ（URL）を Extra として持つ Intent を作る
        val fillInIntent = Intent().apply {
            val urlString = event.viewurl
            println("MoodleRemoteViewsFactory: Setting URL for click: $urlString")

            if (urlString != null) {
                // ▼▼▼ data = Uri.parse(urlString) の代わりに putExtra を使用 ▼▼▼
                putExtra(MoodleWidgetProvider.EXTRA_ITEM_URL, urlString)
            } else {
                println("MoodleRemoteViewsFactory: Warning: viewurl is null for event: ${event.name}")
            }
            // flags などはここでは不要
        }

        // 2. リストアイテム全体に fillInIntent を関連付ける (変更なし)
        views.setOnClickFillInIntent(R.id.list_item_layout, fillInIntent)

        // --- ここまでクリック処理 ---

        return views
    }

    override fun getCount(): Int = events.size
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = position.toLong()
    override fun hasStableIds(): Boolean = true
    override fun onCreate() { /* 初期化処理 */ }
    override fun onDestroy() { /* 破棄処理 */ }
    override fun getLoadingView(): RemoteViews? = null
}