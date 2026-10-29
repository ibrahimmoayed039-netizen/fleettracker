package com.fleet.tracker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** تخزين محلي دائم: ملف JSON + SharedPreferences */
class Store(private val ctx: Context) {
    private val file = File(ctx.filesDir, "data.json")
    private val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun str(k: String) = prefs.getString(k, "") ?: ""
    private fun put(k: String, v: String) = prefs.edit().putString(k, v).apply()

    var apiKey: String get() = str("apiKey"); set(v) = put("apiKey", v)
    var dbUrl: String get() = str("dbUrl"); set(v) = put("dbUrl", v)
    var uid: String get() = str("uid"); set(v) = put("uid", v)
    var refreshToken: String get() = str("rt"); set(v) = put("rt", v)
    var username: String get() = str("username"); set(v) = put("username", v)
    var driverName: String get() = str("driverName"); set(v) = put("driverName", v)
    var vehicle: String get() = str("vehicle"); set(v) = put("vehicle", v)
    var photoTime: Long
        get() = prefs.getLong("pt", 0L)
        set(v) { prefs.edit().putLong("pt", v).apply() }
    var photoUp: Boolean
        get() = prefs.getBoolean("pu", true)
        set(v) { prefs.edit().putBoolean("pu", v).apply() }
    fun avatarFile(): File = File(ctx.filesDir, "avatar.jpg")
    var dirty: Boolean
        get() = prefs.getBoolean("dirty", false)
        set(v) { prefs.edit().putBoolean("dirty", v).apply() }

    private fun res(name: String): String {
        val id = ctx.resources.getIdentifier(name, "string", ctx.packageName)
        return if (id != 0) ctx.getString(id) else ""
    }
    /** إعدادات Firebase المدمجة من google-services.json (إن وُجد) */
    val builtApi: String get() = res("google_api_key")
    val builtDb: String get() = res("firebase_database_url")

    /** هل السائق مسجّل الدخول؟ */
    fun configured() = uid.isNotEmpty() && refreshToken.isNotEmpty() && apiKey.isNotEmpty() && dbUrl.isNotEmpty()

    var points = JSONArray()
    var invoices = JSONArray()
    /** سجل حذف النقاط (سبب الحذف يُرسل للمدير) */
    var deletions = JSONArray()

    init {
        try {
            if (file.exists()) {
                val o = JSONObject(file.readText())
                points = o.optJSONArray("points") ?: JSONArray()
                invoices = o.optJSONArray("invoices") ?: JSONArray()
                deletions = o.optJSONArray("deletions") ?: JSONArray()
            }
        } catch (_: Exception) { }
    }

    fun save() {
        file.writeText(JSONObject().put("points", points).put("invoices", invoices).put("deletions", deletions).toString())
    }

    fun invDir(): File = File(ctx.filesDir, "invoices").apply { mkdirs() }

    private fun filtered(a: JSONArray, id: Long): JSONArray {
        val n = JSONArray()
        for (i in 0 until a.length()) if (a.getJSONObject(i).getLong("id") != id) n.put(a.getJSONObject(i))
        return n
    }
    /** حذف نقطة مع تسجيل السبب (يُرسل تقرير الحذف للمدير عند المزامنة) */
    fun delPoint(id: Long, reason: String) {
        for (i in 0 until points.length()) {
            val p = points.getJSONObject(i)
            if (p.getLong("id") == id) {
                val now = System.currentTimeMillis()
                deletions.put(JSONObject().put("id", now).put("pointId", id).put("name", p.getString("name"))
                    .put("odo", p.getDouble("odo")).put("pointTime", p.getLong("time"))
                    .put("reason", reason).put("time", now).put("up", false))
                break
            }
        }
        points = filtered(points, id); save()
    }
    fun delInvoice(id: Long) { invoices = filtered(invoices, id); save() }

    /** تسجيل الخروج: مسح الحساب والبيانات المحلية */
    fun logout() {
        invDir().listFiles()?.forEach { it.delete() }
        avatarFile().delete()
        points = JSONArray(); invoices = JSONArray(); deletions = JSONArray(); save()
        prefs.edit().clear().apply()
    }
}
