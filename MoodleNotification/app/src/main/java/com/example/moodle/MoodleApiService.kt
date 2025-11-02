package com.example.moodle

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.time.temporal.ChronoUnit // ← 修正点: TimeUnitから変更

object MoodleApiService {

    const val MOODLE_URL = "https://moodle2025.mc2.osakac.ac.jp/2025"
    private val jsonParser = Json { ignoreUnknownKeys = true }

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



// MoodleApiService.kt の中

// ... (他の import 文や定数、OkHttpClient の定義は省略) ...

    // MoodleApiService.kt の loginAndGetSkey

    suspend fun loginAndGetSesskey(username: String, password: String): String? {
        val loginUrl = "$MOODLE_URL/login/index.php"

        // 1. まずログインページにアクセスしてみる
        println("Attempting to access login page...")
        val getRequest = Request.Builder().url(loginUrl).build()

        var token: String? = null // logintoken を格納する変数
        var alreadyLoggedInSesskey: String? = null // ログイン済みの場合のsesskeyを格納する変数

        // まずHTMLを取得して、ログイン済みか、ログインフォームか判断する
        val initialHtml = withContext(Dispatchers.IO) {
            try {
                client.newCall(getRequest).execute().use { response ->
                    if (!response.isSuccessful) {
                        println("loginAndGetSkey: Error getting login page: ${response.code}")
                        return@withContext null
                    }
                    response.body?.string() // HTMLを返す
                }
            } catch (e: Exception) {
                println("loginAndGetSesskey: Exception during initial GET request: ${e.message}")
                return@withContext null
            }
        }

        if (initialHtml == null) {
            println("loginAndGetSkey: Initial login page response was null.")
            return null
        }

        // --- ログイン済みかどうかの判定 ---
        val sesskeyRegexInCfg = """"sesskey":"([^"]+)"""".toRegex() // M.cfgからsesskeyを探す正規表現
        alreadyLoggedInSesskey = sesskeyRegexInCfg.find(initialHtml)?.groupValues?.get(1)

        if (alreadyLoggedInSesskey != null && initialHtml.contains("あなたはすでに")) {
            // すでにログインしている場合
            println("loginAndGetSesskey: Already logged in. Using existing sesskey.")
            return alreadyLoggedInSesskey // 取得したsesskeyをそのまま返す
        } else {
            // ログインしていない（通常のログインフォームが表示されている）場合
            println("loginAndGetSesskey: Not logged in. Proceeding with standard login.")

            // 2. logintoken を抽出 (initialHtml から)
            val tokenRegex = """<input type=\"hidden\" name=\"logintoken\" value=\"([^"]+)\">""".toRegex()
            token = tokenRegex.find(initialHtml)?.groupValues?.get(1)

            if (token == null) {
                println("loginAndGetSesskey: Failed to extract logintoken from login form HTML.")
                println("--- Moodleからの応答HTML (トークン抽出前) ---")
                println(initialHtml) // HTMLを出力して確認
                return null
            }
            println("Got logintoken.")

            // 3. Post login credentials
            println("Posting login credentials...")
            val formBody = FormBody.Builder()
                .add("username", username)
                .add("password", password)
                .add("logintoken", token)
                .add("anchor", "")
                .build()
            val postRequest = Request.Builder().url(loginUrl).post(formBody).build()

            // 4. Get sesskey from response after POST
            return withContext(Dispatchers.IO) {
                try {
                    client.newCall(postRequest).execute().use { response ->
                        if (!response.isSuccessful) {
                            println("loginAndGetSesskey: Error on login POST: ${response.code}")
                            return@use null
                        }
                        val postHtml = response.body?.string()
                        if (postHtml == null) {
                            println("loginAndGetSesskey: Login POST response body was null.")
                            return@use null
                        }

                        if ("logout" !in postHtml) { // POST後のHTMLでlogoutを確認
                            println("loginAndGetSesskey: Login POST failed ('logout' not found). Check credentials?")
                            println("--- Moodleからの応答HTML (ログインPOST失敗時) ---")
                            println(postHtml)
                            return@use null
                        }
                        println("Login successful after POST!")

                        // POST後のHTMLから sesskey を抽出 (M.cfg から)
                        val postSesskey = sesskeyRegexInCfg.find(postHtml)?.groupValues?.get(1)

                        if (postSesskey == null) {
                            println("loginAndGetSesskey: sesskey not found in response after successful POST.")
                            println("--- Moodleからの応答HTML (sesskey抽出失敗時) ---")
                            println(postHtml)
                            return@use null
                        } else {
                            println("Got sesskey after POST: $postSesskey")
                            postSesskey // 取得したsesskeyを返す
                        }
                    }
                } catch (e: Exception) {
                    println("loginAndGetSesskey: Exception during login POST request: ${e.message}")
                    null
                }
            }
        }
    }

    suspend fun fetchAssignmentsFromApi(sesskey: String, daysInFuture: Long): List<MoodleEvent> {
        val methodName = "core_calendar_get_action_events_by_timesort"
        val ajaxUrl = "$MOODLE_URL/lib/ajax/service.php?sesskey=$sesskey&info=$methodName"
        println("\nFetching assignments from API...")

        val now = Instant.now()
        // ▼▼▼ 修正点: 日付の計算をよりモダンな書き方に ▼▼▼
        val future = now.plus(daysInFuture, ChronoUnit.DAYS)

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

        // ▼▼▼ 修正点: ネットワーク処理をバックグラウンドで行う ▼▼▼
        return withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    println("API request failed: ${response.code}")
                    throw Exception("APIリクエストに失敗: ${response.code}")
                }
                val jsonString = response.body?.string()

                if (jsonString.isNullOrBlank()) {
                    println("API response was null or empty.")
                    return@use emptyList()
                }

                try {
                    val apiResponseList = jsonParser.decodeFromString<List<MoodleApiResponse>>(jsonString)
                    val apiResponse = apiResponseList.firstOrNull()

                    if (apiResponse == null) {
                        println("Failed to parse API response or response was an empty list.")
                        return@use emptyList()
                    }

                    if (apiResponse.error) {
                        val errorMsg = apiResponse.exception?.message ?: "Unknown API error"
                        println("API returned an error: $errorMsg")
                        emptyList()
                    } else {
                        val events = apiResponse.data?.events?.filter { it.viewurl.isNotEmpty() } ?: emptyList()
                        println("Successfully fetched ${events.size} events.")
                        events
                    }
                } catch (e: Exception) {
                    println("Exception during JSON parsing: ${e.message}")
                    println("Original JSON string: $jsonString")
                    emptyList<MoodleEvent>()
                }
            }
        }
    }
}