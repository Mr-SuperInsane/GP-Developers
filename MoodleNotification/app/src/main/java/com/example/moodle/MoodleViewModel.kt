package com.example.moodle

// ▼▼▼ AndroidViewModel と Application を import ▼▼▼
import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel // ViewModel から変更
import androidx.lifecycle.viewModelScope
// import com.example.moodle.MoodleEvent // MoodleEventは現在使われていないのでコメントアウトしてもOK
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ▼▼▼ ViewModel を AndroidViewModel に変更し、Application を受け取る ▼▼▼
class MoodleViewModel(application: Application) : AndroidViewModel(application) {

    private val _assignmentsState = MutableStateFlow<String>("ID/パスワードを読み込み中...") // 初期メッセージ変更
    val assignmentsState: StateFlow<String> = _assignmentsState

    init {
        // ▼▼▼ fetchAssignments を呼び出す前に Context を渡す ▼▼▼
        fetchAssignments(application.applicationContext)
    }

    // ▼▼▼ Context を引数で受け取るように変更 ▼▼▼
    private fun fetchAssignments(context: Context) {
        viewModelScope.launch {
            try {
                // ▼▼▼ CredentialsManager を使って ID/パスワードを読み込む ▼▼▼
                val (username, password) = CredentialsManager.loadCredentials(context)

                // ID/パスワードがない場合の処理を追加
                if (username.isNullOrBlank() || password.isNullOrBlank()) {
                    _assignmentsState.value = "MainActivityでIDとパスワードを保存してください。"
                    return@launch // 処理を中断
                }

                // IOスレッドで通信処理を実行
                val result = withContext(Dispatchers.IO) {
                    // ▼▼▼ 読み込んだ ID/パスワードを使う ▼▼▼
                    val sesskey = MoodleApiService.loginAndGetSesskey(username, password)
                    if (sesskey != null) {
                        // 取得期間を Long 型で渡す (7L)
                        MoodleApiService.fetchAssignmentsFromApi(sesskey, 7L) // 7日分取得
                    } else {
                        null // ログイン失敗
                    }
                }

                // --- 結果の表示 (変更なし) ---
                if (result == null) {
                    _assignmentsState.value = "ログインに失敗しました。ID/パスワードを確認してください。" // メッセージ少し変更
                } else if (result.isEmpty()) {
                    _assignmentsState.value = "取得できる課題はありませんでした(直近7日間)。" // メッセージ少し変更
                } else {
                    _assignmentsState.value = formatAssignments(result)
                }

            } catch (e: Exception) {
                e.printStackTrace() // デバッグ用にスタックトレースは残す
                _assignmentsState.value = "エラーが発生しました:\n${e.localizedMessage ?: "不明なエラー"}" // エラーメッセージ改善
            }
        }
    }

    // --- formatAssignments 関数 (変更なし) ---
    private fun formatAssignments(assignments: List<MoodleEvent>): String {
        val formatter = DateTimeFormatter.ofPattern("yyyy年MM月dd日(E) HH:mm")
        return buildString {
            assignments.forEach { task ->
                // timesort が null の場合のデフォルト値を設定 (例: 0L)
                val deadlineEpochSecond = task.timesort ?: 0L
                val deadline = LocalDateTime.ofInstant(Instant.ofEpochSecond(deadlineEpochSecond), ZoneId.systemDefault())
                append("■ ${task.name}\n")
                // course や fullname が null の可能性も考慮
                append("  コース: ${task.course?.fullname ?: "不明なコース"}\n")
                append("  締切: ${deadline.format(formatter)}\n\n")
            }
        }
    }
}