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

/** مزامنة Firebase: يدوية (من الزر) أو تلقائية بعد كل تعديل */
object Syncer {
    private val running = AtomicBoolean(false)
    @Volatile private var again = false

    fun run(st: Store, cb: ((String) -> Unit)? = null) {
        if (!running.compareAndSet(false, true)) {
            again = true
            cb?.invoke("مزامنة أخرى جارية، ستُرسل التعديلات تلقائياً")
            return
        }
        Thread {
            var msg: String
            do {
                again = false
                msg = try {
                    doSync(st); st.dirty = false; "تمت المزامنة بنجاح ✓"
                } catch (e: Exception) {
                    st.dirty = true; "فشلت المزامنة: ${e.message}"
                }
            } while (again)
            running.set(false)
            cb?.invoke(msg)
            if (again) run(st)
        }.start()
    }

    fun auto(ctx: Context, st: Store) {
        if (!st.configured()) return
        val app = ctx.applicationContext
        run(st) { msg ->
            Handler(Looper.getMainLooper()).post {
                val t = when {
                    msg.contains("✓") -> "تمت المزامنة ✓"
                    msg.contains("معطّل") -> "الحساب معطّل، راجع المدير"
                    else -> "لا يوجد اتصال، ستُعاد المحاولة لاحقاً"
                }
                Toast.makeText(app, t, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun doSync(st: Store) {
        val base = st.dbUrl.trimEnd('/')
        val uid = st.uid
        val token = Cloud.refresh(st.apiKey, st.refreshToken)

        val dr = Cloud.call("GET", "$base/drivers/$uid.json?auth=$token", null).trim()
        val d = if (dr == "null") null else JSONObject(dr)
        if (d == null || !d.optBoolean("active")) throw Exception("الحساب معطّل")
        st.driverName = d.optString("name"); st.vehicle = d.optString("vehicle")

        val pts = JSONObject()
        for (k in 0 until st.points.length()) {
            val p = st.points.getJSONObject(k); pts.put(p.getLong("id").toString(), p)
        }
        val invs = JSONObject()
        for (k in 0 until st.invoices.length()) {
            val o = st.invoices.getJSONObject(k)
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
            .put("lastSync", System.currentTimeMillis()).put("points", pts).put("invoices", invs)
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
