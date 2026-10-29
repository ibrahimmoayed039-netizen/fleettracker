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
