package com.videofetch.pro.data
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface VideoFetchApi {
    @POST("api/v1/tasks")
    suspend fun createTask(@Header("X-API-Key") apiKey: String, @Body request: CreateTaskRequest): DownloadTaskDto

    @GET("api/v1/tasks")
    suspend fun listTasks(@Header("X-API-Key") apiKey: String, @Query("limit") limit: Int = 50): List<DownloadTaskDto>

    @GET("api/v1/tasks/{taskId}")
    suspend fun getTask(@Header("X-API-Key") apiKey: String, @Path("taskId") taskId: String): DownloadTaskDto
}
