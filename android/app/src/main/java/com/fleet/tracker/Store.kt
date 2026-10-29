package com.fleet.tracker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** تخزين محلي دائم: ملف JSON + SharedPreferences */
class Store private constructor(private val ctx: Context) {
    companion object {
        @Volatile private var inst: Store? = null
        /** نسخة واحدة للتطبيق كله (الواجهة + العامل الخلفي) حتى لا تتعارض البيانات */
        fun get(c: Context): Store = inst ?: synchronized(this) { inst ?: Store(c.applicationContext).also { inst = it } }
    }

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
    /** طابور الإرسال: rev يزيد مع كل تعديل، و syncedRev آخر رقم أُرسل بنجاح */
    var rev: Long
        get() = prefs.getLong("rev", 0L)
        set(v) { prefs.edit().putLong("rev", v).apply() }
    var syncedRev: Long
        get() = prefs.getLong("srev", 0L)
        set(v) { prefs.edit().putLong("srev", v).apply() }
    var lastSync: Long
        get() = prefs.getLong("lastSync", 0L)
        set(v) { prefs.edit().putLong("lastSync", v).apply() }
    @Synchronized fun touch() { rev = rev + 1 }
    val dirty: Boolean get() = rev > syncedRev
    fun pendingChanges(): Long = maxOf(0L, rev - syncedRev)
    fun pendingInvoices(): Int = (0 until invoices.length()).count { !invoices.getJSONObject(it).optBoolean("up") }
    fun pendingDeletions(): Int = (0 until deletions.length()).count { !deletions.getJSONObject(it).optBoolean("up") }

    private fun res(name: String): String {
        val id = ctx.resources.getIdentifier(name, "string", ctx.packageName)
        return if (id != 0) ctx.getString(id) else ""
    }
    /** إعدادات Firebase المدمجة من google-services.json (إن وُجد) */
    val builtApi: String get() = res("google_api_key")
    val builtDb: String get() = res("firebase_database_url")

    /** بيانات السائق والمركبة (إجبارية عند أول تسجيل دخول) */
    val profileKeys = listOf("name", "phone", "carType", "model", "color", "plate", "vin")
    fun prof(k: String) = str("p_$k")
    fun setProf(k: String, v: String) = put("p_$k", v)
    fun profileDone() = profileKeys.all { prof(it).isNotBlank() } && avatarFile().exists()
    fun profileJson(): JSONObject { val o = JSONObject(); profileKeys.forEach { o.put(it, prof(it)) }; return o }

    /** هل السائق مسجّل الدخول؟ */
    fun configured() = uid.isNotEmpty() && refreshToken.isNotEmpty() && apiKey.isNotEmpty() && dbUrl.isNotEmpty()

    var points = JSONArray()
    var invoices = JSONArray()
    /** سجل حذف النقاط (سبب الحذف يُرسل للمدير) */
    var deletions = JSONArray()
    /** الرحلات: id, startTime, startOdo, endTime (0 = جارية), endOdo */
    var trips = JSONArray()
    fun activeTrip(): JSONObject? =
        (0 until trips.length()).map { trips.getJSONObject(it) }.lastOrNull { it.optLong("endTime") == 0L }

    init {
        // ترحيل من الإصدار القديم: بيانات لم تُرسل بعد
        if (prefs.getBoolean("dirty", false)) { prefs.edit().remove("dirty").apply(); touch() }
        try {
            if (file.exists()) {
                val o = JSONObject(file.readText())
                points = o.optJSONArray("points") ?: JSONArray()
                invoices = o.optJSONArray("invoices") ?: JSONArray()
                deletions = o.optJSONArray("deletions") ?: JSONArray()
                trips = o.optJSONArray("trips") ?: JSONArray()
            }
        } catch (_: Exception) { }
    }

    @Synchronized fun save() {
        file.writeText(JSONObject().put("points", points).put("invoices", invoices).put("deletions", deletions).put("trips", trips).toString())
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
    /** رحلات حذفها المدير: تُزال مع نقاطها من الهاتف كي لا تُرفع من جديد */
    @Synchronized fun applyRemoved(ids: Set<Long>) {
        if (ids.isEmpty()) return
        val np = JSONArray(); val nt = JSONArray()
        for (i in 0 until points.length()) { val p = points.getJSONObject(i); if (p.optLong("tripId") !in ids) np.put(p) }
        for (i in 0 until trips.length()) { val t = trips.getJSONObject(i); if (t.getLong("id") !in ids) nt.put(t) }
        if (np.length() != points.length() || nt.length() != trips.length()) { points = np; trips = nt; save() }
    }
    fun delInvoice(id: Long) { invoices = filtered(invoices, id); save() }

    /** تسجيل الخروج: مسح الحساب والبيانات المحلية */
    fun logout() {
        invDir().listFiles()?.forEach { it.delete() }
        avatarFile().delete()
        points = JSONArray(); invoices = JSONArray(); deletions = JSONArray(); trips = JSONArray(); save()
        prefs.edit().clear().apply()
    }
}
