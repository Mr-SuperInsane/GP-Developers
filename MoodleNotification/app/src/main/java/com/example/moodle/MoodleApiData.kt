@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)
package com.example.moodle

import kotlinx.serialization.Serializable

@Serializable
data class MoodleApiResponse(
    val data: MoodleData? = null,
    val error: Boolean = false,
    val exception: MoodleException? = null
)

@Serializable
data class MoodleData(
    val events: List<MoodleEvent> = emptyList()
)

@Serializable
data class MoodleEvent(
    val id: Int,
    val name: String,
    val viewurl: String,
    val course: MoodleCourse,
    val timesort: Long // Unixタイムスタンプ
)

@Serializable
data class MoodleCourse(
    val fullname: String
)

@Serializable
data class MoodleException(
    val message: String
)

// APIに送信するペイロード用のデータクラス
@Serializable
data class AjaxRequestPayload(
    val index: Int = 0,
    val methodname: String,
    val args: AjaxRequestArgs
)

@Serializable
data class AjaxRequestArgs(
    val limitnum: Int = 20,
    val timesortfrom: Long,
    val timesortto: Long,
    val limittononsuspendedevents: Boolean = true
)
