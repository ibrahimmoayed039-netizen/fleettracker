package com.fleet.tracker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.widget.Toast
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** مزامنة Firebase: كل تعديل يُحفظ محلياً أولاً ثم يدخل طابور الإرسال (فوراً أو عند توفر الإنترنت) */
object Syncer {
    enum class R { OK, DISABLED, RETRY }
    private class Disabled : Exception("الحساب معطّل")
    private val lock = Any()
    private val running = AtomicBoolean(false)
    @Volatile private var again = false
    @Volatile var disabled = false
    @Volatile private var lastErr = ""

    /** مزامنة واحدة (متزامنة). force=false تتخطى إن لم يكن هناك جديد */
    fun syncNow(st: Store, force: Boolean = false): R = synchronized(lock) {
        if (!force && !st.dirty) return@synchronized R.OK
        val rev0 = st.rev
        try {
            doSync(st)
            st.syncedRev = maxOf(st.syncedRev, rev0); st.lastSync = System.currentTimeMillis(); disabled = false
            R.OK
        } catch (e: Disabled) {
            disabled = true; R.DISABLED
        } catch (e: Exception) {
            lastErr = e.message ?: e.javaClass.simpleName; R.RETRY
        }
    }

    fun run(st: Store, force: Boolean = true, cb: ((String) -> Unit)? = null) {
        if (!running.compareAndSet(false, true)) {
            again = true
            cb?.invoke("مزامنة أخرى جارية، ستُرسل التعديلات تلقائياً")
            return
        }
        Thread {
            var msg: String
            var f = force
            do {
                again = false
                msg = when (syncNow(st, f)) {
                    R.OK -> "تمت المزامنة بنجاح ✓"
                    R.DISABLED -> "الحساب معطّل"
                    R.RETRY -> "فشلت المزامنة: $lastErr"
                }
                f = false
            } while (again)
            running.set(false)
            cb?.invoke(msg)
            if (again) run(st, false)
        }.start()
    }

    /** يُستدعى بعد كل تعديل: يسجّله في الطابور ويحاول الإرسال الآن، وإلا يتولاه WorkManager عند عودة الإنترنت */
    fun auto(ctx: Context, st: Store) {
        if (!st.configured()) return
        val app = ctx.applicationContext
        st.touch()
        SyncWorker.enqueue(app)
        run(st, false) { msg ->
            Handler(Looper.getMainLooper()).post {
                val t = when {
                    msg.contains("✓") -> "تمت المزامنة ✓"
                    msg.contains("معطّل") -> "الحساب معطّل، راجع المدير"
                    else -> "حُفظ على الهاتف وسيُرسل عند توفر الإنترنت"
                }
                Toast.makeText(app, t, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun doSync(st: Store) {
        val base = st.dbUrl.trimEnd('/')
        // نسخ مرجعية: الواجهة قد تعدّل البيانات أثناء الإرسال
        val dels = st.deletions; val ptsA = st.points; val tripsA = st.trips; val invsA = st.invoices
        val uid = st.uid
        val token = Cloud.refresh(st.apiKey, st.refreshToken)

        val dr = Cloud.call("GET", "$base/drivers/$uid.json?auth=$token", null).trim()
        val d = if (dr == "null") null else JSONObject(dr)
        if (d == null || !d.optBoolean("active")) throw Disabled()
        st.driverName = d.optString("name"); st.vehicle = d.optString("vehicle")

        // تقارير حذف النقاط تُرسل أولاً؛ إن فشلت لا تُحدَّث النقاط عند المدير
        for (k in 0 until dels.length()) {
            val o = dels.getJSONObject(k)
            if (o.optBoolean("up")) continue
            val url = "$base/deletions/$uid/${o.getLong("id")}.json?auth=$token"
            if (Cloud.call("GET", url, null).trim() == "null") {
                val body = JSONObject(o.toString()); body.remove("up")
                Cloud.call("PUT", url, body.toString())
            }
            o.put("up", true); st.save()
        }

        // صورة السائق (إن وُجدت): تُرفع مرة واحدة عند تغييرها
        val af = st.avatarFile()
        if (af.exists() && !st.photoUp) {
            Cloud.call("PUT", "$base/avatars/$uid.json?auth=$token",
                JSONObject.quote(Base64.encodeToString(af.readBytes(), Base64.NO_WRAP)))
            st.photoUp = true
        }

        val pts = JSONObject()
        for (k in 0 until ptsA.length()) {
            val p = ptsA.getJSONObject(k); pts.put(p.getLong("id").toString(), p)
        }
        val trips = JSONObject()
        for (k in 0 until tripsA.length()) {
            val t = tripsA.getJSONObject(k); trips.put(t.getLong("id").toString(), t)
        }
        val invs = JSONObject()
        for (k in 0 until invsA.length()) {
            val o = invsA.getJSONObject(k)
            val id = o.getLong("id")
            val f = File(st.invDir(), o.getString("file"))
            if (!o.optBoolean("up") && f.exists()) {
                Cloud.call("PUT", "$base/images/$uid/$id.json?auth=$token", JSONObject.quote(b64(f)))
                o.put("up", true)
                st.save()
            }
            invs.put(id.toString(), JSONObject().put("id", id).put("note", o.optString("note"))
                .put("amount", o.optString("amount")).put("time", o.getLong("time")))
        }
        val node = JSONObject().put("name", st.driverName).put("vehicle", st.vehicle)
            .put("lastSync", System.currentTimeMillis()).put("photoTime", if (af.exists()) st.photoTime else 0L).put("points", pts).put("trips", trips).put("invoices", invs)
        Cloud.call("PUT", "$base/data/$uid.json?auth=$token", node.toString())
    }

    private fun b64(f: File): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var s = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / s > 1280) s *= 2
        var bm = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
        val deg = when (ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (deg != 0f) bm = Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height, Matrix().apply { postRotate(deg) }, true)
        val out = ByteArrayOutputStream()
        bm.compress(Bitmap.CompressFormat.JPEG, 65, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
