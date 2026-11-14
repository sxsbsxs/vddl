package com.videofetch.pro.data
import com.google.gson.annotations.SerializedName

data class CreateTaskRequest(@SerializedName("source_url") val sourceUrl: String)

data class DownloadTaskDto(
    val id: String,
    @SerializedName("platform_id") val platformId: String,
    val status: String,
    val progress: Int,
    @SerializedName("error_code") val errorCode: String?,
    @SerializedName("error_message") val errorMessage: String?,
    val metadata: Map<String, Any> = emptyMap(),
    @SerializedName("output_key") val outputKey: String?,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("updated_at") val updatedAt: String
)
