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

/** شريط حالة الإرسال: يُحدَّث تلقائياً ويعرض ما هو معلّق في الطابور */
private fun Context.syncBanner(st: Store): TextView {
    val tv = TextView(this).apply { textSize = 13f; setPadding(dp(10), dp(6), dp(10), dp(6)) }
    fun refresh() {
        if (!st.configured()) { tv.visibility = View.GONE; return }
        tv.visibility = View.VISIBLE
        if (Syncer.disabled) {
            tv.text = "⚠ الحساب معطّل — بياناتك محفوظة على الهاتف، راجع المدير"
            tv.setTextColor(Color.parseColor("#C62828"))
        } else if (st.dirty) {
            val inv = st.pendingInvoices(); val del = st.pendingDeletions()
            tv.text = "⏳ ${st.pendingChanges()} تعديل بانتظار الإرسال" +
                (if (inv > 0) " — $inv فاتورة" else "") + (if (del > 0) " — $del تقرير حذف" else "") +
                "\nمحفوظة على الهاتف وتُرسل تلقائياً عند توفر الإنترنت"
            tv.setTextColor(Color.parseColor("#E65100"))
        } else {
            tv.text = "✓ كل البيانات مرسلة" + (if (st.lastSync > 0) " — آخر مزامنة ${timeStr(st.lastSync)}" else "")
            tv.setTextColor(Color.parseColor("#2E7D32"))
        }
    }
    val tick = object : Runnable { override fun run() { refresh(); tv.postDelayed(this, 2000) } }
    tv.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) { tick.run() }
        override fun onViewDetachedFromWindow(v: View) { v.removeCallbacks(tick) }
    })
    refresh()
    return tv
}

private fun confirm(c: Context, msg: String, ok: () -> Unit) {
    AlertDialog.Builder(c).setMessage(msg)
        .setPositiveButton("حذف") { _, _ -> ok() }
        .setNegativeButton("إلغاء", null).show()
}

/** حذف نقطة يتطلب سبباً إجبارياً، ويُرسل تقرير بالحذف للمدير */
private fun askReason(c: Context, title: String, ok: (String) -> Unit) {
    val et = EditText(c).apply { hint = "سبب الحذف (إجباري)"; minLines = 2 }
    val box = FrameLayout(c).apply { setPadding(c.dp(20), c.dp(8), c.dp(20), 0); addView(et) }
    val d = AlertDialog.Builder(c).setTitle(title)
        .setMessage("سيُرسل تقرير بسبب الحذف إلى المدير.")
        .setView(box)
        .setPositiveButton("حذف وإرسال التقرير", null)
        .setNegativeButton("إلغاء", null).create()
    d.setOnShowListener {
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val r = et.text.toString().trim()
            if (r.length < 3) et.error = "اكتب سبب الحذف" else { d.dismiss(); ok(r) }
        }
    }
    d.show()
}

private fun dur(ms: Long): String {
    val m = maxOf(0L, ms) / 60000
    val h = m / 60
    return if (h > 0) "$h س ${m % 60} د" else "$m د"
}

// ---------- تبويب الرحلة: بداية رحلة ← نقاط توقف (وصول/مغادرة) ← نهاية رحلة ----------
class TripFragment : Fragment() {
    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val ctx = requireContext(); val st = store()
        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val root = ctx.page { addView(ctx.syncBanner(st)); addView(body) }
        lateinit var render: () -> Unit

        fun mk(id: Long, n: String, o: Double, kind: String, tid: Long) =
            JSONObject().put("id", id).put("name", n).put("odo", o).put("time", id).put("kind", kind).put("tripId", tid)
        fun after() { st.save(); render(); Syncer.auto(ctx, st) }
        fun allPts() = (0 until st.points.length()).map { st.points.getJSONObject(it) }.sortedBy { it.getLong("time") }

        fun pointRow(idx: Int, p: JSONObject, prev: JSONObject?, tripActive: Boolean): TextView {
            val seg = if (prev != null) maxOf(0.0, p.getDouble("odo") - prev.getDouble("odo")) else 0.0
            val kind = p.optString("kind")
            val icon = when (kind) { "start" -> "🟢 انطلاق"; "end" -> "🏁 نهاية"; "stop" -> "📍 توقف"; else -> "📍" }
            val extra = if (prev != null) "  (+${fmt(seg)} كم)" else ""
            val d = if (kind == "stop") {
                val lv = p.optLong("leave")
                if (lv > 0) "\n⏱ مدة التوقف: ${dur(lv - p.getLong("time"))}"
                else if (tripActive) "\n⏱ متوقف حالياً منذ ${dur(System.currentTimeMillis() - p.getLong("time"))}" else ""
            } else ""
            return ctx.label("${idx + 1}. $icon — ${p.getString("name")} — ${fmt(p.getDouble("odo"))}$extra\n${timeStr(p.getLong("time"))}$d", 15f).apply {
                setOnLongClickListener {
                    askReason(ctx, "حذف النقطة: ${p.getString("name")}") { reason ->
                        st.delPoint(p.getLong("id"), reason); render(); Syncer.auto(ctx, st)
                    }
                    true
                }
            }
        }

        render = {
            body.removeAllViews()
            val all = allPts()
            val trip = st.activeTrip()
            val name = ctx.input("اسم النقطة")
            val odo = ctx.input("قراءة العداد (كم)", true)
            val now = System.currentTimeMillis()

            if (trip == null) {
                body.addView(ctx.label("لا توجد رحلة جارية", 20f, true))
                name.hint = "نقطة الانطلاق"
                body.addView(name); body.addView(odo)
                body.addView(ctx.btn("▶ بدء رحلة") {
                    val n = name.text.toString().trim(); val o = odo.text.toString().toDoubleOrNull()
                    if (n.isEmpty() || o == null) {
                        Toast.makeText(ctx, "أدخل نقطة الانطلاق وقراءة العداد", Toast.LENGTH_SHORT).show()
                    } else {
                        val t = System.currentTimeMillis()
                        st.trips.put(JSONObject().put("id", t).put("startTime", t).put("startOdo", o).put("endTime", 0L).put("endOdo", 0.0))
                        st.points.put(mk(t, n, o, "start", t)); after()
                    }
                })
            } else {
                val tid = trip.getLong("id")
                val tp = all.filter { it.optLong("tripId") == tid }
                val lastOdo = tp.lastOrNull()?.getDouble("odo") ?: trip.getDouble("startOdo")
                val open = tp.lastOrNull { it.optString("kind") == "stop" && it.optLong("leave") == 0L }
                body.addView(ctx.label("🚚 رحلة جارية", 20f, true))
                body.addView(ctx.label("بدأت ${timeStr(trip.getLong("startTime"))} — مضى ${dur(now - trip.getLong("startTime"))}", 15f))
                if (open != null) {
                    body.addView(ctx.label("أنت متوقف الآن في «${open.getString("name")}» منذ ${dur(now - open.getLong("time"))}", 16f, true))
                    body.addView(ctx.btn("➡ مغادرة النقطة") { open.put("leave", System.currentTimeMillis()); after() })
                }
                name.hint = "اسم نقطة التوقف (أو نقطة النهاية)"
                body.addView(name); body.addView(odo)
                fun check(): Pair<String, Double>? {
                    val o = odo.text.toString().toDoubleOrNull()
                    if (o == null) { Toast.makeText(ctx, "أدخل قراءة العداد", Toast.LENGTH_SHORT).show(); return null }
                    if (o < lastOdo) { Toast.makeText(ctx, "القراءة أقل من السابقة (${fmt(lastOdo)})", Toast.LENGTH_SHORT).show(); return null }
                    return Pair(name.text.toString().trim(), o)
                }
                body.addView(ctx.btn("📍 تسجيل نقطة توقف") {
                    val r = check() ?: return@btn
                    if (r.first.isEmpty()) { Toast.makeText(ctx, "أدخل اسم النقطة", Toast.LENGTH_SHORT).show(); return@btn }
                    val t = System.currentTimeMillis()
                    open?.put("leave", t)  // مغادرة النقطة السابقة تلقائياً
                    st.points.put(mk(t + 1, r.first, r.second, "stop", tid)); after()
                })
                body.addView(ctx.btn("🏁 إنهاء الرحلة") {
                    val r = check() ?: return@btn
                    val t = System.currentTimeMillis()
                    open?.put("leave", t)
                    st.points.put(mk(t + 1, r.first.ifEmpty { "نهاية الرحلة" }, r.second, "end", tid))
                    trip.put("endTime", t).put("endOdo", r.second); after()
                })
            }

            // سجل الرحلات
            val trips = (0 until st.trips.length()).map { st.trips.getJSONObject(it) }.sortedByDescending { it.getLong("startTime") }
            for (t in trips) {
                val tid = t.getLong("id"); val end = t.optLong("endTime"); val active = end == 0L
                val tp = all.filter { it.optLong("tripId") == tid }
                val stops = tp.filter { it.optString("kind") == "stop" }
                val stopMs = stops.sumOf { (if (it.optLong("leave") > 0) it.getLong("leave") else if (active) now else it.getLong("time")) - it.getLong("time") }
                val endOdo = if (!active) t.getDouble("endOdo") else (tp.lastOrNull()?.getDouble("odo") ?: t.getDouble("startOdo"))
                body.addView(ctx.label("— رحلة ${timeStr(t.getLong("startTime"))} ${if (active) "(جارية)" else ""}", 17f, true).apply { setPadding(0, ctx.dp(16), 0, 0) })
                body.addView(ctx.label("المدة: ${dur((if (active) now else end) - t.getLong("startTime"))}   |   المسافة: ${fmt(maxOf(0.0, endOdo - t.getDouble("startOdo")))} كم\nالتوقفات: ${stops.size}   |   مجموع وقت التوقف: ${dur(stopMs)}", 14f))
                tp.forEachIndexed { idx, p -> body.addView(pointRow(idx, p, tp.getOrNull(idx - 1), active)) }
            }
            // نقاط قديمة بلا رحلة
            val legacy = all.filter { it.optLong("tripId") == 0L }
            if (legacy.isNotEmpty()) {
                body.addView(ctx.label("— نقاط سابقة (بدون رحلة)", 17f, true).apply { setPadding(0, ctx.dp(16), 0, 0) })
                legacy.forEachIndexed { idx, p -> body.addView(pointRow(idx, p, legacy.getOrNull(idx - 1), false)) }
            }
            body.addView(ctx.label("(اضغط مطولاً على نقطة لحذفها: يلزم كتابة السبب ويُرسل تقرير للمدير)", 12f))
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
            addView(ctx.syncBanner(st))
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
    private var tmp: File? = null
    private val cam = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) tmp?.let { setAvatar(it) }
    }
    private val gal = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val f = File(store().invDir(), "avatar_tmp.jpg")
            requireContext().contentResolver.openInputStream(uri)?.use { inp -> f.outputStream().use { inp.copyTo(it) } }
            setAvatar(f)
        }
    }

    /** تصغير الصورة إلى مربع 256 بكسل وحفظها كصورة للسائق ثم مزامنتها */
    private fun setAvatar(src: File) {
        val st = store()
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.path, b)
        var s = 1
        while (minOf(b.outWidth, b.outHeight) / (s * 2) >= 256) s *= 2
        var bm = BitmapFactory.decodeFile(src.path, BitmapFactory.Options().apply { inSampleSize = s }) ?: return
        val deg = when (ExifInterface(src.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (deg != 0f) bm = Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height, Matrix().apply { postRotate(deg) }, true)
        val side = minOf(bm.width, bm.height)
        bm = Bitmap.createBitmap(bm, (bm.width - side) / 2, (bm.height - side) / 2, side, side)
        bm = Bitmap.createScaledBitmap(bm, 256, 256, true)
        st.avatarFile().outputStream().use { bm.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        src.delete()
        st.photoTime = System.currentTimeMillis(); st.photoUp = false
        Syncer.auto(requireContext(), st)
        reload()
    }

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
            addView(ctx.syncBanner(st))
            val af = st.avatarFile()
            if (af.exists()) addView(ImageView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(ctx.dp(96), ctx.dp(96))
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(BitmapFactory.decodeFile(af.path))
            })
            addView(ctx.btn("📷 التقاط صورتي") {
                val f = File(st.invDir(), "avatar_tmp.jpg"); tmp = f
                cam.launch(FileProvider.getUriForFile(ctx, "com.fleet.tracker.provider", f))
            })
            addView(ctx.btn("🖼 اختيار صورتي من المعرض") { gal.launch("image/*") })
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
            addView(ctx.label("يعمل التطبيق بدون إنترنت: كل شيء يُحفظ على الهاتف ويُرسل تلقائياً (حتى لو كان التطبيق مغلقاً) عند توفر الاتصال.", 13f))
        }
    }
}
