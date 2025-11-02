package com.example.moodle

import android.app.PendingIntent
import android.appwidget.AppWidgetManager // AppWidgetManager も import しておきましょう
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import com.example.moodle.R

class MoodleWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // ウィジェットが複数置かれている場合もあるので、すべてを更新する
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    companion object {
        // ▼▼▼ MainActivity に渡す Extra のキー名を定義 ▼▼▼
        const val EXTRA_ITEM_URL = "com.example.moodle.EXTRA_ITEM_URL"
        // ▼▼▼ カスタム Action の合言葉をここで定義する ▼▼▼
        const val ACTION_WIDGET_ITEM_CLICK = "com.example.moodle.ACTION_WIDGET_ITEM_CLICK"

        fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val views = RemoteViews(context.packageName, R.layout.moodle_widget_layout)

            // ListViewとServiceの接続 (変更なし)
            val serviceIntent = Intent(context, MoodleWidgetService::class.java)
            views.setRemoteAdapter(R.id.widget_list_view, serviceIntent)
            views.setEmptyView(R.id.widget_list_view, R.id.widget_empty_view)

            // --- ここから PendingIntent の設定 (MainActivity を開く) ---

            // 1. MainActivity を開く Intent を作成 (URL情報は含めない)
            val clickIntentTemplate = Intent(context, MainActivity::class.java).apply {
                // ▼▼▼ Action を定義した合言葉 (カスタム Action) に変更 ▼▼▼
                action = ACTION_WIDGET_ITEM_CLICK
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                // デバッグ用に Widget ID も追加 (オプション)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }

            // 2. PendingIntent の作成
            val clickPendingIntentTemplate = PendingIntent.getActivity(
                context,
                appWidgetId, // requestCode
                clickIntentTemplate,
                // fillInIntent の Extra を反映させるため UPDATE_CURRENT が必要
                // Android 12 以降は MUTABLE か IMMUTABLE の指定が必須
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // 3. ListView全体に、このPendingIntentテンプレートを設定する
            views.setPendingIntentTemplate(R.id.widget_list_view, clickPendingIntentTemplate)

            // --- ここまで PendingIntent の設定 ---

            // データの更新通知とウィジェット更新 (変更なし)
            appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widget_list_view)
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}