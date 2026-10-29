package com.fleet.tracker

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** اتصال مع Firebase عبر REST (بدون مكتبات) */
object Cloud {
    fun call(method: String, url: String, body: String?, form: Boolean = false): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 10000; c.readTimeout = 60000
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", if (form) "application/x-www-form-urlencoded" else "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val txt = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        if (code >= 400) throw Exception("HTTP $code $txt")
        return txt
    }

    fun signIn(apiKey: String, email: String, password: String): JSONObject =
        JSONObject(call("POST", "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=$apiKey",
            JSONObject().put("email", email).put("password", password).put("returnSecureToken", true).toString()))

    /** توكن جديد من refresh token */
    fun refresh(apiKey: String, refreshToken: String): String =
        JSONObject(call("POST", "https://securetoken.googleapis.com/v1/token?key=$apiKey",
            "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(refreshToken, "UTF-8"), true)).getString("id_token")
}
