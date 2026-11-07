package com.ptit.slinkmini

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import java.io.IOException
import java.util.concurrent.TimeUnit

object Api {
    private val JSON = "application/json".toMediaType()

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun postForm(url: String, form: Map<String, String>,
                 onResult: (Boolean, String) -> Unit) {
        val b = FormBody.Builder()
        form.forEach { (k, v) -> b.add(k, v) }
        val req = Request.Builder().url(url).post(b.build()).build()
        client.newCall(req).enqueue(cb(onResult))
    }

    fun postJson(url: String, body: String, headers: Map<String, String>,
                 onResult: (Boolean, String) -> Unit) {
        val req = Request.Builder().url(url)
            .post(body.toRequestBody(JSON))
            .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
            .build()
        client.newCall(req).enqueue(cb(onResult))
    }

    fun get(url: String, headers: Map<String, String>,
            onResult: (Boolean, String) -> Unit) {
        val req = Request.Builder().url(url).get()
            .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
            .build()
        client.newCall(req).enqueue(cb(onResult))
    }

    private fun cb(onResult: (Boolean, String) -> Unit) = object : Callback {
        override fun onFailure(call: Call, e: IOException) = onResult(false, e.message ?: "network error")
        override fun onResponse(call: Call, r: Response) {
            val body = r.body?.string() ?: ""
            onResult(r.isSuccessful, body)
        }
    }
}
