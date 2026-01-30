package com.claudelists.app.api

import com.claudelists.app.BuildConfig
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {

    // Stubbed auth - in production this would come from OAuth flow
    // TODO: Replace with real OAuth flow (token valid for 24h)
    private var authToken: String? = "eyJhbGciOiJSUzI1NiIsInR5cCIgOiAiSldUIiwia2lkIiA6ICJxRkUwcThlRzNIOWx3eVpCcnp0YUd2eVBPWWhkR2hIWHdEUjVtcDQtdkdFIn0.eyJleHAiOjE3Njk4NjQyMjQsImlhdCI6MTc2OTc3NzgyNCwianRpIjoiZjZjYzZmNmMtZDNiMy00MTI3LThhZGMtZWJjYjU4M2Q0NTNkIiwiaXNzIjoiaHR0cDovL2xvY2FsaG9zdDo4MTgwL3JlYWxtcy9jbGF1ZGUtbGlzdHMiLCJzdWIiOiJlOWFmYjM4Ni01NzFlLTRmYzktYTRkZi0xN2U4Zjc2ZDc0NmEiLCJ0eXAiOiJCZWFyZXIiLCJhenAiOiJjbGF1ZGUtbGlzdHMtd2ViIiwic2lkIjoiY2EzOWZhNjUtMGQ4ZS00MzFkLTg3YzEtODc2Y2RhZDFjMWUxIiwic2NvcGUiOiJwb3N0Z3Jlc3QiLCJyb2xlIjoid2ViX3VzZXIiLCJwcmVmZXJyZWRfdXNlcm5hbWUiOiJhbGljZSIsImdpdmVuX25hbWUiOiJBbGljZSJ9.hg6Ie_6oo8pqDlPTtZtvXm_4v5LM5oLw10OzXN4qZ0pm3_c4xtZwulbn9WwnkYjWq8iaAm1hXZTZrkQCes2fu4LGMEgezVnnN97xDI10HXRWs1ieiMIygYWYxHILjnDkqHW0JcHRljFNIWZj4yIr56jh8dc1vBciVKHSdFGs2tECdiCOcG3-B2_j4q1wkWUy6acI0a6BQYt-DSWwIROj8RU2MPA-OkAx7xE0A-V6EkNKQrkdHnpCv9qcgVRWrc5r1zHZ1WB5rk3Ui7PD13QLojAV-UUypofrmuM9cSZMaAY6akiV7xyrxNuwPSEm_T6FVT0JofICf4FY3ORHbFWSgA"

    // User info from JWT - alice's sub claim
    var currentUserId: String = "e9afb386-571e-4fc9-a4df-17e8f76d746a"
    var currentUserName: String = "Alice"

    fun setAuthToken(token: String?) {
        authToken = token
    }

    private val authInterceptor = Interceptor { chain ->
        val requestBuilder = chain.request().newBuilder()
            .addHeader("Content-Type", "application/json")

        authToken?.let {
            requestBuilder.addHeader("Authorization", "Bearer $it")
        }

        chain.proceed(requestBuilder.build())
    }

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .addInterceptor(loggingInterceptor)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(BuildConfig.API_BASE_URL + "/")
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val apiService: ApiService = retrofit.create(ApiService::class.java)

    // WebSocket URL for real-time updates
    val wsUrl: String = BuildConfig.WS_BASE_URL
}
