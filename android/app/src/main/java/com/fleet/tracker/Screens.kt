package com.fleet.tracker

import android.app.AlertDialog
import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.media.ExifInterface
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.util.Base64
import android.view.*
import android.view.inputmethod.EditorInfo
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
/** ألوان الهوية (Agrimatco - Fleet) */
private object Brand {
    val g1 = Color.parseColor("#1AC56D"); val g2 = Color.parseColor("#0A8F4B"); val g3 = Color.parseColor("#05643A")
    val edge = Color.parseColor("#04512E"); val ink = Color.parseColor("#10241A")
    val bg = Color.parseColor("#E9F0EC"); val line = Color.parseColor("#C3D3C9")
}

/** هوامش بالـ dp */
private fun Context.lp(topDp: Int = 6, bottomDp: Int = 6) = LinearLayout.LayoutParams(
    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
).apply { setMargins(0, dp(topDp), 0, dp(bottomDp)) }

/** سطح مجسّم: وجه متدرّج فوق حافة داكنة، وعند الضغط ينزل الوجه (إحساس زر حقيقي) */
private fun Context.raised(top: Int, bottom: Int, edge: Int, rDp: Int = 14, depthDp: Int = 4): Drawable {
    val r = dp(rDp).toFloat(); val d = dp(depthDp)
    fun layer(pressed: Boolean): LayerDrawable {
        val base = GradientDrawable().apply { setColor(edge); cornerRadius = r }
        val face = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom)).apply { cornerRadius = r }
        return LayerDrawable(arrayOf<Drawable>(base, face)).apply {
            if (pressed) setLayerInset(1, 0, d - dp(1), 0, dp(1)) else setLayerInset(1, 0, 0, 0, d)
        }
    }
    return StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), layer(true))
        addState(intArrayOf(), layer(false))
    }
}

/** قصّ الصورة بزوايا مدوّرة */
private fun View.roundClip(rPx: Int) {
    clipToOutline = true
    outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, rPx.toFloat()) }
    }
}

private fun Context.label(t: String, sz: Float = 16f, bold: Boolean = false) = TextView(this).apply {
    text = t; textSize = sz; setPadding(0, dp(6), 0, dp(6))
    setTextColor(if (bold) Brand.g3 else Brand.ink)
    if (bold) setTypeface(null, Typeface.BOLD)
}
private fun Context.input(h: String, num: Boolean = false) = EditText(this).apply {
    hint = h; textSize = 16f
    setHintTextColor(Color.parseColor("#7C8B83")); setTextColor(Brand.ink)
    background = GradientDrawable().apply {
        setColor(Color.parseColor("#FBFDFC")); cornerRadius = dp(12).toFloat(); setStroke(dp(1), Brand.line)
    }
    setPadding(dp(14), dp(12), dp(14), dp(12))
    layoutParams = lp()
    if (num) inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
}
private fun Context.btn(t: String, f: () -> Unit) = Button(this).apply {
    text = t; setOnClickListener { f() }
    transformationMethod = null
    textSize = 16f; setTypeface(null, Typeface.BOLD); setTextColor(Color.WHITE)
    setShadowLayer(2f, 0f, 1f, Color.parseColor("#66000000"))
    background = raised(Brand.g1, Brand.g2, Brand.edge)
    stateListAnimator = null
    minHeight = dp(52); minimumHeight = dp(52)
    setPadding(dp(16), dp(10), dp(16), dp(14))
    layoutParams = lp(6, 6)
}
/** صفحة: خلفية فاتحة + بطاقة بيضاء مرتفعة (ظل حقيقي) تحوي المحتوى */
private fun Context.page(body: LinearLayout.() -> Unit): ScrollView {
    val col = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = GradientDrawable().apply {
            setColor(Color.WHITE); cornerRadius = dp(20).toFloat(); setStroke(dp(1), Color.parseColor("#DCE6E0"))
        }
        elevation = dp(6).toFloat()
        body()
    }
    val holder = FrameLayout(this).apply {
        setPadding(dp(14), dp(14), dp(14), dp(22))
        addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    return ScrollView(this).apply {
        setBackgroundColor(Brand.bg); clipToPadding = false; clipChildren = false
        addView(holder)
    }
}
private fun fmt(d: Double) = if (d % 1.0 == 0.0) d.toLong().toString() else String.format(Locale.US, "%.1f", d)
/** مدة بالساعات والدقائق (مثال: 2 س 15 د) */
private fun dur(ms: Long): String {
    val m = maxOf(0L, ms) / 60000
    val h = m / 60
    return if (h > 0) "$h س ${m % 60} د" else "$m د"
}
private fun timeStr(t: Long) = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US).format(Date(t))
private fun Fragment.store() = (requireActivity() as MainActivity).store

/** شريط حالة الإرسال: يُحدَّث تلقائياً ويعرض ما هو معلّق في الطابور */
private fun Context.syncBanner(st: Store): TextView {
    val tv = TextView(this).apply { textSize = 13f; setPadding(dp(12), dp(8), dp(12), dp(8)); layoutParams = lp(0, 10) }
    fun tint(c: String) { tv.background = GradientDrawable().apply { setColor(Color.parseColor(c)); cornerRadius = dp(12).toFloat() } }
    fun refresh() {
        if (!st.configured()) { tv.visibility = View.GONE; return }
        tv.visibility = View.VISIBLE
        if (Syncer.disabled) {
            tv.text = "⚠ الحساب معطّل — بياناتك محفوظة على الهاتف، راجع المدير"
            tv.setTextColor(Color.parseColor("#C62828")); tint("#FDECEA")
        } else if (st.dirty) {
            val inv = st.pendingInvoices(); val del = st.pendingDeletions()
            tv.text = "⏳ ${st.pendingChanges()} تعديل بانتظار الإرسال" +
                (if (inv > 0) " — $inv فاتورة" else "") + (if (del > 0) " — $del تقرير حذف" else "") +
                "\nمحفوظة على الهاتف وتُرسل تلقائياً عند توفر الإنترنت"
            tv.setTextColor(Color.parseColor("#E65100")); tint("#FFF3E0")
        } else {
            tv.text = "✓ كل البيانات مرسلة" + (if (st.lastSync > 0) " — آخر مزامنة ${timeStr(st.lastSync)}" else "")
            tv.setTextColor(Color.parseColor("#2E7D32")); tint("#E6F5EC")
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

/** تأكيد قراءة العداد قبل الحفظ: النقطة لا تُحذف ولا تُعدَّل بعد ذلك */
private fun confirmSave(c: Context, odo: Double, prev: Double?, ok: () -> Unit) {
    val diff = if (prev != null) "\nالمسافة منذ آخر قراءة: ${fmt(odo - prev)} كم" else ""
    AlertDialog.Builder(c).setTitle("تأكيد قراءة العداد")
        .setMessage("القراءة: ${fmt(odo)} كم$diff\n\nلا يمكن حذف النقطة. إن أخطأت يمكنك تصحيح العداد لاحقاً بسبب يصل للمدير، فتأكد من الرقم.")
        .setPositiveButton("حفظ") { _, _ -> ok() }
        .setNegativeButton("مراجعة", null).show()
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

        /** تصحيح قراءة العداد فقط (لا حذف): ضمن حدود النقطة السابقة واللاحقة، مع سبب إجباري ويُسجَّل للمدير */
        fun editOdo(p: JSONObject) {
            val pts = allPts()
            val idx = pts.indexOfFirst { it.getLong("id") == p.getLong("id") }
            val lo: Double? = pts.getOrNull(idx - 1)?.getDouble("odo")
            val hi: Double? = pts.getOrNull(idx + 1)?.getDouble("odo")
            val oldV = p.getDouble("odo")
            val et = ctx.input("قراءة العداد الصحيحة", true).apply { setText(fmt(oldV)) }
            val rs = ctx.input("سبب التصحيح (إجباري)").apply { minLines = 2 }
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL; setPadding(ctx.dp(20), ctx.dp(8), ctx.dp(20), 0)
                addView(et); addView(rs)
            }
            val range = "من ${if (lo != null) fmt(lo) else "أي قيمة"} إلى ${if (hi != null) fmt(hi) else "أي قيمة"}"
            val d = AlertDialog.Builder(ctx).setTitle("تصحيح العداد: ${p.getString("name")}")
                .setMessage("القراءة الحالية: ${fmt(oldV)}\nالمسموح: $range\nيُرسل التصحيح للمدير مع السبب.")
                .setView(box)
                .setPositiveButton("حفظ التصحيح", null)
                .setNegativeButton("إلغاء", null).create()
            d.setOnShowListener {
                d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val v = et.text.toString().toDoubleOrNull()
                    val r = rs.text.toString().trim()
                    if (v == null) { et.error = "أدخل رقماً"; return@setOnClickListener }
                    if ((lo != null && v < lo) || (hi != null && v > hi)) { et.error = "يجب أن تكون $range"; return@setOnClickListener }
                    if (v == oldV) { d.dismiss(); return@setOnClickListener }
                    if (r.length < 3) { rs.error = "اكتب سبب التصحيح"; return@setOnClickListener }
                    val log = p.optJSONArray("editLog") ?: JSONArray()
                    log.put(JSONObject().put("from", oldV).put("to", v).put("time", System.currentTimeMillis()).put("reason", r))
                    p.put("editLog", log).put("odo", v)
                    // نقطة بداية/نهاية الرحلة: حدّث الرحلة أيضاً
                    for (k in 0 until st.trips.length()) {
                        val t = st.trips.getJSONObject(k)
                        if (t.getLong("id") == p.optLong("tripId")) {
                            when (p.optString("kind")) { "start" -> t.put("startOdo", v); "end" -> t.put("endOdo", v) }
                        }
                    }
                    d.dismiss(); after()
                }
            }
            d.show()
        }

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
            val ed = if (p.has("editLog")) " ✏" else ""
            return ctx.label("${idx + 1}. $icon — ${p.getString("name")} — ${fmt(p.getDouble("odo"))}$ed$extra\n${timeStr(p.getLong("time"))}$d", 15f)
                .apply { setOnClickListener { editOdo(p) } }
        }

        render = {
            body.removeAllViews()
            val all = allPts()
            val trip = st.activeTrip()
            val name = ctx.input("اسم النقطة")
            val odo = ctx.input("قراءة العداد (كم)", true)
            val now = System.currentTimeMillis()
            // آخر قراءة عداد مسجّلة (من كل الرحلات): لا يُقبل رقم أقل منها
            val lastOdoAll: Double? = all.lastOrNull()?.getDouble("odo")
            if (lastOdoAll != null) odo.hint = "قراءة العداد (لا تقل عن ${fmt(lastOdoAll)})"
            fun valid(o: Double?): Boolean {
                if (o == null) { Toast.makeText(ctx, "أدخل قراءة العداد", Toast.LENGTH_SHORT).show(); return false }
                if (lastOdoAll != null && o < lastOdoAll) {
                    Toast.makeText(ctx, "القراءة (${fmt(o)}) أقل من آخر قراءة مسجّلة (${fmt(lastOdoAll)})", Toast.LENGTH_LONG).show()
                    return false
                }
                return true
            }

            if (trip == null) {
                body.addView(ctx.label("لا توجد رحلة جارية", 20f, true))
                name.hint = "نقطة الانطلاق"
                body.addView(name); body.addView(odo)
                body.addView(ctx.btn("▶ بدء رحلة") {
                    val n = name.text.toString().trim(); val o = odo.text.toString().toDoubleOrNull()
                    if (n.isEmpty() || o == null) {
                        Toast.makeText(ctx, "أدخل نقطة الانطلاق وقراءة العداد", Toast.LENGTH_SHORT).show()
                    } else if (valid(o)) {
                        confirmSave(ctx, o, lastOdoAll) {
                            val t = System.currentTimeMillis()
                            st.trips.put(JSONObject().put("id", t).put("startTime", t).put("startOdo", o).put("endTime", 0L).put("endOdo", 0.0))
                            st.points.put(mk(t, n, o, "start", t)); after()
                        }
                    }
                })
            } else {
                val tid = trip.getLong("id")
                val tp = all.filter { it.optLong("tripId") == tid }
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
                    if (!valid(o)) return null
                    return Pair(name.text.toString().trim(), o!!)
                }
                body.addView(ctx.btn("📍 تسجيل نقطة توقف") {
                    val r = check() ?: return@btn
                    if (r.first.isEmpty()) { Toast.makeText(ctx, "أدخل اسم النقطة", Toast.LENGTH_SHORT).show(); return@btn }
                    confirmSave(ctx, r.second, lastOdoAll) {
                        val t = System.currentTimeMillis()
                        open?.put("leave", t)  // مغادرة النقطة السابقة تلقائياً
                        st.points.put(mk(t + 1, r.first, r.second, "stop", tid)); after()
                    }
                })
                body.addView(ctx.btn("🏁 إنهاء الرحلة") {
                    val r = check() ?: return@btn
                    confirmSave(ctx, r.second, lastOdoAll) {
                        val t = System.currentTimeMillis()
                        open?.put("leave", t)
                        st.points.put(mk(t + 1, r.first.ifEmpty { "نهاية الرحلة" }, r.second, "end", tid))
                        trip.put("endTime", t).put("endOdo", r.second); after()
                    }
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
            body.addView(ctx.label("النقاط لا تُحذف. لتصحيح خطأ في العداد اضغط على النقطة (✏ = نقطة مصحّحة، والتصحيح يصل للمدير مع السبب). العداد لا يقل عن القراءة السابقة.", 12f))
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
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL; setPadding(ctx.dp(10), ctx.dp(10), ctx.dp(10), ctx.dp(10))
                    background = GradientDrawable().apply { setColor(Color.parseColor("#F4F9F6")); cornerRadius = ctx.dp(14).toFloat(); setStroke(ctx.dp(1), Color.parseColor("#DCE6E0")) }
                    layoutParams = ctx.lp(4, 6)
                }
                val img = ImageView(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(ctx.dp(80), ctx.dp(80))
                    scaleType = ImageView.ScaleType.CENTER_CROP; roundClip(context.dp(14))
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

/** تصغير الصورة إلى مربع 256 بكسل وحفظها كصورة للسائق (تُرفع عند المزامنة) */
private fun saveAvatar(st: Store, src: File): Boolean {
    val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(src.path, b)
    var s = 1
    while (minOf(b.outWidth, b.outHeight) / (s * 2) >= 256) s *= 2
    var bm = BitmapFactory.decodeFile(src.path, BitmapFactory.Options().apply { inSampleSize = s }) ?: return false
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
    return true
}

// ---------- بيانات السائق والمركبة (إجبارية عند أول دخول) ----------
class ProfileFragment : Fragment() {
    private var tmp: File? = null
    private var preview: ImageView? = null
    private val cam = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) tmp?.let { got(it) } }
    private val gal = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val f = File(store().invDir(), "avatar_tmp.jpg")
            requireContext().contentResolver.openInputStream(uri)?.use { inp -> f.outputStream().use { inp.copyTo(it) } }
            got(f)
        }
    }

    private fun got(f: File) {
        val st = store()
        if (saveAvatar(st, f)) preview?.apply {
            setImageBitmap(BitmapFactory.decodeFile(st.avatarFile().path)); visibility = View.VISIBLE
        }
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val ctx = requireContext(); val st = store()
        val ed = LinkedHashMap<String, EditText>()
        fun field(key: String, hint: String, phone: Boolean = false) {
            ed[key] = ctx.input(hint).apply {
                inputType = if (phone) InputType.TYPE_CLASS_PHONE else InputType.TYPE_CLASS_TEXT
                setText(st.prof(key).ifEmpty { if (key == "name") st.driverName else "" })
            }
        }
        field("name", "الاسم الكامل")
        field("phone", "رقم الهاتف", true)
        field("carType", "نوع السيارة (مثال: تويوتا هايلكس)")
        field("model", "الموديل (سنة الصنع)")
        field("color", "لون السيارة")
        field("plate", "رقم المركبة (اللوحة)")
        field("vin", "رقم الشاصي")
        preview = ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ctx.dp(120), ctx.dp(120))
            scaleType = ImageView.ScaleType.CENTER_CROP; roundClip(context.dp(14))
            if (st.avatarFile().exists()) setImageBitmap(BitmapFactory.decodeFile(st.avatarFile().path)) else visibility = View.GONE
        }
        return ctx.page {
            addView(ctx.label("بيانات السائق والمركبة", 20f, true))
            if (!st.profileDone()) addView(ctx.label("أكمل البيانات التالية للمتابعة (إجباري)", 14f))
            ed.values.forEach { addView(it) }
            addView(ctx.label("الصورة الشخصية", 16f, true))
            addView(preview)
            addView(ctx.btn("📷 التقاط صورتي") {
                val f = File(st.invDir(), "avatar_tmp.jpg"); tmp = f
                cam.launch(FileProvider.getUriForFile(ctx, "com.fleet.tracker.provider", f))
            })
            addView(ctx.btn("🖼 اختيار صورتي من المعرض") { gal.launch("image/*") })
            addView(ctx.btn("حفظ ومتابعة") {
                for ((_, e) in ed) if (e.text.isBlank()) { e.error = "مطلوب"; e.requestFocus(); return@btn }
                if (!st.avatarFile().exists()) {
                    Toast.makeText(ctx, "التقط صورتك الشخصية أو اخترها", Toast.LENGTH_LONG).show(); return@btn
                }
                ed.forEach { (k, e) -> st.setProf(k, e.text.toString().trim()) }
                Syncer.auto(ctx, st)
                Toast.makeText(ctx, "تم حفظ البيانات ✓", Toast.LENGTH_SHORT).show()
                (requireActivity() as MainActivity).afterProfile()
            })
        }
    }
}

// ---------- تبويب الحساب والمزامنة ----------
/** تسجيل دخول السائق (يُستدعى من خيط خلفي). يعيد نص الخطأ، أو null عند النجاح */
private fun performLogin(st: Store, cfgCode: String, user: String, pw: String): String? {
    try {
        var api = st.builtApi; var db = st.builtDb.trimEnd('/')
        if (api.isEmpty() || db.isEmpty()) {
            try {
                val j = JSONObject(String(Base64.decode(cfgCode.trim().removePrefix("FLEET:"), Base64.DEFAULT)))
                api = j.getString("a"); db = j.getString("d").trimEnd('/')
            } catch (e: Exception) { throw Exception("BADCODE") }
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
        // استرجاع بيانات السائق وصورته إن كان قد أدخلها سابقاً (مثلاً بعد تسجيل الخروج)
        try {
            val tok = r.getString("idToken")
            val pr = Cloud.call("GET", "$db/data/$uid/profile.json?auth=$tok", null).trim()
            if (pr != "null") { val j = JSONObject(pr); st.profileKeys.forEach { k -> st.setProf(k, j.optString(k)) } }
            val avr = Cloud.call("GET", "$db/avatars/$uid.json?auth=$tok", null).trim()
            if (avr != "null" && !st.avatarFile().exists()) {
                st.avatarFile().writeBytes(Base64.decode(org.json.JSONTokener(avr).nextValue() as String, Base64.DEFAULT))
                st.photoUp = true; st.photoTime = System.currentTimeMillis()
            }
        } catch (_: Exception) { }
        return null
    } catch (e: Exception) {
        val m = e.message ?: ""
        return when {
            e is java.net.UnknownHostException || e is java.net.SocketTimeoutException || e is java.net.ConnectException ->
                "تعذّر الاتصال بالإنترنت، تحقق من الشبكة"
            m.contains("BADCODE") -> "كود الإعداد غير صحيح"
            m.contains("INVALID") || m.contains("EMAIL_NOT_FOUND") -> "اسم المستخدم أو كلمة المرور غير صحيحة"
            m.contains("INACTIVE") -> "الحساب غير مفعّل، راجع المدير"
            m.contains("TOO_MANY") -> "محاولات كثيرة، حاول بعد قليل"
            else -> "تعذّر الدخول: $m"
        }
    }
}

/** شاشة دخول السائق: تظهر مباشرة عند فتح التطبيق قبل تسجيل الدخول */
class LoginFragment : Fragment() {
    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val ctx = requireContext(); val st = store()
        val built = st.builtApi.isNotEmpty() && st.builtDb.isNotEmpty()
        val cfg = ctx.input("كود الإعداد (يعطيك إياه المدير)")
        val user = ctx.input("اسم المستخدم").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_NEXT
        }
        val pw = ctx.input("كلمة المرور").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        val status = ctx.label("", 14f).apply {
            setTextColor(Color.parseColor("#C62828")); gravity = Gravity.CENTER
        }
        val show = CheckBox(ctx).apply {
            text = "إظهار كلمة المرور"; textSize = 14f; setTextColor(Brand.ink)
            setOnCheckedChangeListener { _, on ->
                pw.transformationMethod = if (on) null else PasswordTransformationMethod.getInstance()
                pw.setSelection(pw.text.length)
            }
        }
        lateinit var go: Button
        fun submit() {
            if ((!built && cfg.text.isBlank()) || user.text.isBlank() || pw.text.isEmpty()) {
                status.text = "أكمل كل الحقول"
                return
            }
            status.text = ""
            go.isEnabled = false; go.text = "جارٍ الدخول..."
            val code = cfg.text.toString(); val u = user.text.toString(); val p = pw.text.toString()
            Thread {
                val err = performLogin(st, code, u, p)
                activity?.runOnUiThread {
                    if (err == null) {
                        Syncer.run(st)
                        (activity as? MainActivity)?.afterLogin()
                    } else {
                        status.text = err; go.isEnabled = true; go.text = "تسجيل الدخول"
                    }
                }
            }.start()
        }
        go = ctx.btn("تسجيل الدخول") { submit() }
        pw.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { submit(); true } else false
        }

        val logo = FrameLayout(ctx).apply {
            background = ctx.getDrawable(R.drawable.logo_badge)
            setPadding(ctx.dp(8), ctx.dp(8), ctx.dp(8), ctx.dp(8))
            elevation = ctx.dp(8).toFloat()
            layoutParams = LinearLayout.LayoutParams(ctx.dp(104), ctx.dp(104))
            addView(ImageView(ctx).apply {
                setImageResource(R.drawable.logo); scaleType = ImageView.ScaleType.FIT_CENTER
            })
        }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(20), ctx.dp(20), ctx.dp(20), ctx.dp(16))
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = ctx.dp(22).toFloat() }
            elevation = ctx.dp(10).toFloat()
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(ctx.label("تسجيل دخول السائق", 19f, true).apply { gravity = Gravity.CENTER })
            addView(ctx.label("أدخل البيانات التي أعطاك إياها المدير", 13f).apply { gravity = Gravity.CENTER })
            if (!built) addView(cfg)
            addView(user); addView(pw); addView(show); addView(status); addView(go)
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(ctx.dp(20), ctx.dp(24), ctx.dp(20), ctx.dp(20))
            addView(logo)
            addView(TextView(ctx).apply {
                text = "Agrimatco - Fleet"; textSize = 26f; setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER
                textDirection = View.TEXT_DIRECTION_LTR
                setShadowLayer(4f, 0f, 2f, Color.parseColor("#80000000"))
                layoutParams = ctx.lp(14, 2)
            })
            addView(TextView(ctx).apply {
                text = "متابعة المركبة والسائق"; textSize = 13f; setTextColor(Color.parseColor("#CFF0DC"))
                gravity = Gravity.CENTER; layoutParams = ctx.lp(0, 18)
            })
            addView(card)
            addView(TextView(ctx).apply {
                text = "© Agrimatco"; textSize = 12f; setTextColor(Color.parseColor("#B3FFFFFF"))
                gravity = Gravity.CENTER; layoutParams = ctx.lp(18, 0)
            })
        }
        return ScrollView(ctx).apply {
            isFillViewport = true
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#052619"), Color.parseColor("#0A3D24"), Color.parseColor("#0A8F4B"))
            )
            addView(col)
        }
    }
}

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

    private fun setAvatar(src: File) {
        val st = store()
        if (!saveAvatar(st, src)) return
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
            val err = performLogin(st, cfgCode, user, pw)
            activity?.runOnUiThread {
                if (err == null) {
                    Syncer.run(st)
                    if (st.profileDone()) reload() else (activity as? MainActivity)?.show(ProfileFragment())
                } else status.text = err
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
                scaleType = ImageView.ScaleType.CENTER_CROP; roundClip(context.dp(14))
                setImageBitmap(BitmapFactory.decodeFile(af.path))
            })
            addView(ctx.btn("📷 التقاط صورتي") {
                val f = File(st.invDir(), "avatar_tmp.jpg"); tmp = f
                cam.launch(FileProvider.getUriForFile(ctx, "com.fleet.tracker.provider", f))
            })
            addView(ctx.btn("🖼 اختيار صورتي من المعرض") { gal.launch("image/*") })
            addView(ctx.btn("✏ بيانات السائق والمركبة") { (activity as? MainActivity)?.show(ProfileFragment()) })
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
                                if (m.contains("✓")) { st.logout(); (activity as? MainActivity)?.showLogin() } else status.text = "لم يتم الخروج: $m"
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
