package com.example.moodle

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

// ====== ★★★ 設定項目 ★★★ ======
private const val MOODLE_URL = "https://moodle2025.mc2.osakac.ac.jp/2025"
private const val FETCH_DAYS = 7L

// JSONパーサーの設定（未知のキーを無視する）
private val jsonParser = Json { ignoreUnknownKeys = true }

// OkHttpClientのインスタンスを生成（Cookieを自動管理するCookieJarを設定）
private val client = OkHttpClient.Builder()
    .cookieJar(object : CookieJar {
        private val cookieStore = mutableMapOf<String, List<Cookie>>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookieStore[url.host] = cookies
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            return cookieStore[url.host] ?: emptyList()
        }
    })
    .build()

/**
 * Moodleにログインし、API通信に必要なsesskeyを取得する関数
 */
suspend fun loginAndGetSesskey(username: String, password: String): String? {
    println("Moodleへのログインを試みます...")
    val loginUrl = "$MOODLE_URL/login/index.php"

    // 1. ログインページから 'logintoken' を取得
    val getRequest = Request.Builder().url(loginUrl).build()
    val token: String? = client.newCall(getRequest).execute().use { response ->
        if (!response.isSuccessful) throw Exception("ログインページの取得に失敗: ${response.code}")
        val html = response.body?.string() ?: return@use null
        // 正規表現でlogintokenを探す
        val tokenRegex = """<input type="hidden" name="logintoken" value="([^"]+)">""".toRegex()
        tokenRegex.find(html)?.groupValues?.get(1)
    }

    if (token == null) {
        println("エラー: logintokenが見つかりませんでした。")
        return null
    }
    println("logintokenを取得しました。")

    // 2. ログイン情報をPOST
    val formBody = FormBody.Builder()
        .add("username", username)
        .add("password", password)
        .add("logintoken", token)
        .add("anchor", "")
        .build()
    val postRequest = Request.Builder().url(loginUrl).post(formBody).build()
    
    return client.newCall(postRequest).execute().use { response ->
        if (!response.isSuccessful) throw Exception("ログインPOSTリクエストに失敗: ${response.code}")
        val html = response.body?.string() ?: return@use null
        
        if ("logout" !in html) {
            println("エラー: ログインに失敗しました。ID/パスワードを確認してください。")
            return@use null
        }
        println("ログインに成功しました！")
        
        // 3. ログイン後のページから 'sesskey' を取得
        val sesskeyRegex = """"sesskey":"([^"]+)"""".toRegex()
        val sesskey = sesskeyRegex.find(html)?.groupValues?.get(1)
        
        if (sesskey == null) {
            println("エラー: sesskeyが見つかりませんでした。")
            null
        } else {
            println("sesskeyを取得しました: $sesskey")
            sesskey
        }
    }
}

/**
 * MoodleのAJAX APIを叩いて課題リストを取得する関数
 */
suspend fun fetchAssignmentsFromApi(sesskey: String, daysInFuture: Long): List<MoodleEvent> {
    println("""
APIから${daysInFuture}日以内の課題を取得します...""")
    val methodName = "core_calendar_get_action_events_by_timesort"
    val ajaxUrl = "$MOODLE_URL/lib/ajax/service.php?sesskey=$sesskey&info=$methodName"

    val now = Instant.now()
    val future = now.plusSeconds(TimeUnit.DAYS.toSeconds(daysInFuture))

    val payloadObject = listOf(
        AjaxRequestPayload(
            methodname = methodName,
            args = AjaxRequestArgs(
                timesortfrom = now.epochSecond,
                timesortto = future.epochSecond
            )
        )
    )

    val jsonPayload = jsonParser.encodeToString(payloadObject)
    val requestBody = jsonPayload.toRequestBody("application/json".toMediaType())
    val request = Request.Builder().url(ajaxUrl).post(requestBody).build()

    return client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw Exception("APIリクエストに失敗: ${response.code}")
        val jsonString = response.body?.string()
        
        if (jsonString.isNullOrBlank()) {
            emptyList()
        } else {
            val apiResponse = jsonParser.decodeFromString<List<MoodleApiResponse>>(jsonString)
            
            if (apiResponse.first().error) {
                val errorMsg = apiResponse.first().exception?.message ?: "不明なAPIエラー"
                println("エラー: $errorMsg")
                emptyList()
            } else {
                apiResponse.first().data?.events?.filter { it.viewurl.isNotEmpty() } ?: emptyList()
            }
        }
    }
}
