package com.videofetch.pro.data
import com.videofetch.pro.BuildConfig
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {
    fun create(baseUrl: String): VideoFetchApi {
        require(baseUrl.startsWith("https://") || (BuildConfig.DEBUG && baseUrl.startsWith("http://10.0.2.2"))) {
            "The API endpoint must use HTTPS outside the Android emulator debug environment."
        }
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
        return Retrofit.Builder()
            .baseUrl(if (baseUrl.endsWith("/")) baseUrl else "$" + "baseUrl/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(VideoFetchApi::class.java)
    }
}

/*
 * The current backend uses a temporary bootstrap API token. Never embed that
 * shared administrator secret in the APK. Wire this client to per-user
 * credentials only after account authentication and individual key issuance
 * are implemented server-side.
 */
