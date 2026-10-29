package com.fleet.tracker

import android.app.AlertDialog
import android.content.Context
import android.graphics.*
import android.media.ExifInterface
import android.os.Bundle
import android.text.InputType
import android.util.Base64
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun Context.dp(n: Int) = (n * resources.displayMetrics.density).toInt()
private fun Context.label(t: String, sz: Float = 16f, bold: Boolean = false) = TextView(this).apply {
    text = t; textSize = sz; setPadding(0, dp(6), 0, dp(6))
    if (bold) setTypeface(null, Typeface.BOLD)
}
private fun Context.input(h: String, num: Boolean = false) = EditText(this).apply {
    hint = h
    if (num) inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
}
private fun Context.btn(t: String, f: () -> Unit) = Button(this).apply { text = t; setOnClickListener { f() } }
private fun Context.page(body: LinearLayout.() -> Unit): ScrollView {
    val col = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        body()
    }
    return ScrollView(this).apply { addView(col) }
}
private fun fmt(d: Double) = if (d % 1.0 == 0.0) d.toLong().toString() else String.format(Locale.US, "%.1f", d)
private fun timeStr(t: Long) = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).format(Date(t))
private fun Fragment.store() = (requireActivity() as MainActivity).store

private fun confirm(c: Context, msg: String, ok: () -> Unit) {
    AlertDialog.Builder(c).setMessage(msg)
        .setPositiveButton("حذف") { _, _ -> ok() }
        .setNegativeButton("إلغاء", null).show()
}

// ---------- تبويب الرحلة ----------
class TripFragment : Fragment() {
    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val ctx = requireContext(); val st = store()
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val summary = ctx.label("", 18f, true)
        val name = ctx.input("اسم نقطة التوقف")
        val odo = ctx.input("قراءة العداد (كم)", true)

        fun render() {
            list.removeAllViews()
            val pts = (0 until st.points.length()).map { st.points.getJSONObject(it) }.sortedBy { it.getLong("time") }
            var total = 0.0
            pts.forEachIndexed { idx, p ->
                val seg = if (idx > 0) maxOf(0.0, p.getDouble("odo") - pts[idx - 1].getDouble("odo")) else 0.0
                total += seg
                val extra = if (idx > 0) "  (+${fmt(seg)} كم)" else ""
                list.addView(ctx.label("${idx + 1}. ${p.getString("name")} — ${fmt(p.getDouble("odo"))}$extra\n${timeStr(p.getLong("time"))}", 15f).apply {
                    setOnLongClickListener {
                        confirm(ctx, "حذف هذه النقطة؟") { st.delPoint(p.getLong("id")); render(); Syncer.auto(ctx, st) }
                        true
                    }
                })
            }
            summary.text = "المسافة: ${fmt(total)} كم   |   نقاط التوقف: ${pts.size}"
        }

        val root = ctx.page {
            addView(ctx.label("تسجيل نقطة توقف", 20f, true))
            addView(name); addView(odo)
            addView(ctx.btn("إضافة النقطة") {
                val n = name.text.toString().trim(); val o = odo.text.toString().toDoubleOrNull()
                if (n.isEmpty() || o == null) {
                    Toast.makeText(ctx, "أدخل الاسم وقراءة العداد", Toast.LENGTH_SHORT).show()
                } else {
                    val now = System.currentTimeMillis()
                    st.points.put(JSONObject().put("id", now).put("name", n).put("odo", o).put("time", now))
                    st.save(); name.text.clear(); odo.text.clear(); render(); Syncer.auto(ctx, st)
                }
            })
            addView(summary); addView(list)
            addView(ctx.label("(اضغط مطولاً على نقطة لحذفها)", 12f))
        }
        render()
        return root
    }
}

// ---------- تبويب الفواتير ----------
class InvoiceFragment : Fragment() {
    private var pending: File? = null
    private lateinit var note: EditText
    private lateinit var amount: EditText
    private lateinit var list: LinearLayout
    private lateinit var refresh: () -> Unit

    private val cam = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) pending?.let { addInvoice(it) }
    }
    private val gal = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val f = newFile()
            requireContext().contentResolver.openInputStream(uri)?.use { inp -> f.outputStream().use { inp.copyTo(it) } }
            addInvoice(f)
        }
    }

    private fun newFile() = File(store().invDir(), "inv_${System.currentTimeMillis()}.jpg")

    private fun addInvoice(f: File) {
        val st = store()
        val id = f.name.removePrefix("inv_").removeSuffix(".jpg").toLong()
        st.invoices.put(JSONObject().put("id", id).put("note", note.text.toString().trim())
            .put("amount", amount.text.toString().trim()).put("time", id).put("file", f.name))
        st.save(); note.text.clear(); amount.text.clear(); refresh(); Syncer.auto(requireContext(), st)
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val ctx = requireContext(); val st = store()
        note = ctx.input("ملاحظة (مثال: وقود، صيانة)")
        amount = ctx.input("المبلغ", true)
        list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        refresh = {
            list.removeAllViews()
            for (k in (0 until st.invoices.length()).reversed()) {
                val inv = st.invoices.getJSONObject(k)
                val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, ctx.dp(8), 0, ctx.dp(8)) }
                val img = ImageView(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(ctx.dp(80), ctx.dp(80))
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    val p = File(st.invDir(), inv.getString("file")).path
                    setImageBitmap(BitmapFactory.decodeFile(p, BitmapFactory.Options().apply { inSampleSize = 8 }))
                }
                row.addView(img)
                row.addView(ctx.label("  ${inv.optString("note")}\n  المبلغ: ${inv.optString("amount")}\n  ${timeStr(inv.getLong("time"))}", 14f))
                row.setOnLongClickListener {
                    confirm(ctx, "حذف هذه الفاتورة؟") {
                        File(st.invDir(), inv.getString("file")).delete()
                        st.delInvoice(inv.getLong("id")); refresh(); Syncer.auto(ctx, st)
                    }
                    true
                }
                list.addView(row)
            }
        }

        val root = ctx.page {
            addView(ctx.label("فاتورة جديدة", 20f, true))
            addView(note); addView(amount)
            addView(ctx.btn("📷 تصوير الفاتورة") {
                val f = newFile(); pending = f
                cam.launch(FileProvider.getUriForFile(ctx, "com.fleet.tracker.provider", f))
            })
            addView(ctx.btn("🖼 اختيار من المعرض") { gal.launch("image/*") })
            addView(list)
        }
        refresh()
        return root
    }
}

// ---------- تبويب الحساب والمزامنة ----------
class SyncFragment : Fragment() {
    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val ctx = requireContext(); val st = store()
        return if (st.configured()) loggedIn(ctx, st) else loginForm(ctx, st)
    }

    private fun reload() {
        parentFragmentManager.beginTransaction().replace(R.id.host, SyncFragment()).commit()
    }

    private fun loginForm(ctx: Context, st: Store): View {
        val built = st.builtApi.isNotEmpty() && st.builtDb.isNotEmpty()
        val cfg = ctx.input("كود الإعداد (يعطيك إياه المدير)")
        val user = ctx.input("اسم المستخدم")
        val pw = ctx.input("كلمة المرور").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val status = ctx.label("", 15f)
        return ctx.page {
            addView(ctx.label("تسجيل دخول السائق", 20f, true))
            if (!built) addView(cfg)
            addView(user); addView(pw)
            addView(ctx.btn("تسجيل الدخول") {
                if ((!built && cfg.text.isBlank()) || user.text.isBlank() || pw.text.isEmpty()) {
                    status.text = "أكمل كل الحقول"
                } else {
                    status.text = "جارٍ الدخول..."
                    login(st, cfg.text.toString(), user.text.toString(), pw.text.toString(), status)
                }
            })
            addView(status)
        }
    }

    private fun login(st: Store, cfgCode: String, user: String, pw: String, status: TextView) {
        Thread {
            var err: String? = null
            try {
                var api = st.builtApi; var db = st.builtDb.trimEnd('/')
                if (api.isEmpty() || db.isEmpty()) {
                    val j = JSONObject(String(Base64.decode(cfgCode.trim().removePrefix("FLEET:"), Base64.DEFAULT)))
                    api = j.getString("a"); db = j.getString("d").trimEnd('/')
                }
                val r = Cloud.signIn(api, user.trim().lowercase() + "@fleet.local", pw)
                val uid = r.getString("localId")
                val dr = Cloud.call("GET", "$db/drivers/$uid.json?auth=${r.getString("idToken")}", null).trim()
                if (dr == "null") throw Exception("INACTIVE")
                val d = JSONObject(dr)
                if (!d.optBoolean("active")) throw Exception("INACTIVE")
                st.apiKey = api; st.dbUrl = db; st.uid = uid
                st.refreshToken = r.getString("refreshToken")
                st.username = user.trim(); st.driverName = d.optString("name"); st.vehicle = d.optString("vehicle")
            } catch (e: Exception) {
                val m = e.message ?: ""
                err = when {
                    m.contains("INVALID") || m.contains("EMAIL_NOT_FOUND") -> "اسم المستخدم أو كلمة المرور غير صحيحة"
                    m.contains("INACTIVE") -> "الحساب غير مفعّل، راجع المدير"
                    else -> "تعذّر الدخول: $m"
                }
            }
            activity?.runOnUiThread {
                if (err == null) { Syncer.run(st); reload() } else status.text = err
            }
        }.start()
    }

    private fun loggedIn(ctx: Context, st: Store): View {
        val status = ctx.label("", 15f)
        return ctx.page {
            addView(ctx.label("الحساب", 20f, true))
            addView(ctx.label("السائق: ${st.driverName}", 17f))
            addView(ctx.label("المركبة: ${st.vehicle.ifEmpty { "غير محددة" }}", 17f))
            addView(ctx.label("اسم المستخدم: ${st.username}", 15f))
            addView(ctx.btn("مزامنة الآن") {
                status.text = "جارٍ المزامنة..."
                Syncer.run(st) { m -> activity?.runOnUiThread { status.text = m } }
            })
            addView(ctx.btn("تسجيل الخروج") {
                AlertDialog.Builder(ctx)
                    .setMessage("ستُرسل بياناتك أولاً ثم تُحذف من هذا الهاتف. متابعة؟")
                    .setPositiveButton("خروج") { _, _ ->
                        status.text = "جارٍ المزامنة قبل الخروج..."
                        Syncer.run(st) { m ->
                            activity?.runOnUiThread {
                                if (m.contains("✓")) { st.logout(); reload() } else status.text = "لم يتم الخروج: $m"
                            }
                        }
                    }
                    .setNegativeButton("إلغاء", null).show()
            })
            addView(status)
            addView(ctx.label("تُرسل كل تعديلاتك تلقائياً عند توفر الإنترنت.", 13f))
        }
    }
}
