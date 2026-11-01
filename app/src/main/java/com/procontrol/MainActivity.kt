package com.procontrol

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.os.Bundle
import android.graphics.Typeface
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Color
import android.view.ScaleGestureDetector
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import java.util.zip.CRC32
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("procontrol", MODE_PRIVATE) }
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val displayDate = SimpleDateFormat("dd.MM.yyyy", Locale("ru"))
    private var cleanRate = 80.0
    private var scrapRate = 75.0
    private var entries = mutableListOf<Entry>()
    private var periodStart = ""
    private var archiveEditing = false
    private var selectedArchiveIndex = -1

    data class Entry(val date: String, val clean: Double, val scrap: Double, val note: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cleanRate = prefs.getString("clean_rate", "80")?.toDoubleOrNull() ?: 80.0
        scrapRate = prefs.getString("scrap_rate", "75")?.toDoubleOrNull() ?: 75.0
        periodStart = prefs.getString("period_start", null) ?: dateFormat.format(Date()).also {
            prefs.edit().putString("period_start", it).apply()
        }
        archiveEditing = prefs.getBoolean("archive_editing", false)
        loadEntries()
        render()
    }

    private fun loadEntries() {
        entries.clear()
        try {
            val array = JSONArray(prefs.getString("entries", "[]") ?: "[]")
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                entries.add(Entry(o.getString("date"), o.optDouble("clean"), o.optDouble("scrap"), o.optString("note")))
            }
        } catch (_: Exception) { }
        entries.sortByDescending { it.date }
    }

    private fun saveEntries() {
        val array = JSONArray()
        entries.forEach { e ->
            array.put(JSONObject().put("date", e.date).put("clean", e.clean).put("scrap", e.scrap).put("note", e.note))
        }
        prefs.edit().putString("entries", array.toString()).apply()
    }

    private fun render() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(12))
            setBackgroundColor(0xFFF3F5F8.toInt())
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(label("ProControl", 27f, 0xFF17243A.toInt(), true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(Button(this).apply { text = "Графики"; setOnClickListener { showCharts() } })
        header.addView(Button(this).apply { text = "Архив"; setOnClickListener { showArchive() } })
        header.addView(Button(this).apply { text = "Настройки"; setOnClickListener { showRatesDialog() } })
        root.addView(header)
        root.addView(label("Учёт тоннажа и расчёт заработка", 14f, 0xFF68758A.toInt()))

        val cleanTotal = entries.sumOf { it.clean }
        val scrapTotal = entries.sumOf { it.scrap }
        val earnings = cleanTotal * cleanRate + scrapTotal * scrapRate
        val days = calendarDays()
        val averagePerDay = if (days > 0) earnings / days else 0.0
        val summary = card()
        summary.addView(label("ТЕКУЩИЙ РАСЧЁТНЫЙ ПЕРИОД", 12f, 0xFF68758A.toInt(), true))
        summary.addView(label(formatDate(periodStart) + " · открыт", 14f, 0xFF17243A.toInt(), true))
        summary.addView(label("Начислено всего", 13f, 0xFF68758A.toInt()))
        summary.addView(label(money(earnings) + " сом", 30f, 0xFF087B58.toInt(), true))
        summary.addView(label("Средний заработок в день", 13f, 0xFF68758A.toInt()))
        summary.addView(label(money(averagePerDay) + " сом/день", 22f, 0xFF1557A6.toInt(), true))
        summary.addView(label("Чистая продукция: " + qty(cleanTotal) + " кг", 14f, 0xFF26364D.toInt()))
        summary.addView(label("Брак: " + qty(scrapTotal) + " кг", 14f, 0xFF26364D.toInt()))
        summary.addView(label("Прошло дней: " + days + " · Смен внесено: " + entries.size, 12f, 0xFF68758A.toInt()))
        summary.addView(label("Период: " + formatDate(periodStart) + " - " + formatDate(dateFormat.format(Date())), 12f, 0xFF68758A.toInt()))
        root.addView(summary)

        val addTitle = label("Добавить смену", 19f, 0xFF17243A.toInt(), true)
        addTitle.setPadding(0, dp(18), 0, dp(8))
        root.addView(addTitle)
        val form = card()
        var selectedDate = dateFormat.format(Date())
        val dateButton = Button(this).apply {
            text = formatDate(selectedDate)
            setOnClickListener { showDatePicker(selectedDate) { picked -> selectedDate = picked; text = formatDate(picked) } }
        }
        val cleanInput = numberInput("Килограммы чистой продукции").apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val scrapInput = numberInput("Килограммы брака").apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val noteInput = EditText(this).apply { hint = "Комментарий (необязательно)"; textSize = 15f }
        form.addView(fieldCaption("Дата смены")); form.addView(dateButton, matchWrap())
        form.addView(fieldCaption("Чистая продукция, кг")); form.addView(cleanInput, matchWrap())
        form.addView(fieldCaption("Брак, кг")); form.addView(scrapInput, matchWrap())
        form.addView(fieldCaption("Комментарий")); form.addView(noteInput, matchWrap())
        form.addView(Button(this).apply {
            text = "Сохранить день"
            setOnClickListener {
                val date = selectedDate
                val cleanKg = cleanInput.text.toString().toLongOrNull()
                val scrapKg = scrapInput.text.toString().toLongOrNull()
                val clean = (cleanKg ?: -1L) / 1000.0
                val scrap = (scrapKg ?: -1L) / 1000.0
                if (cleanKg == null || scrapKg == null || clean < 0 || scrap < 0) {
                    toast("Введи оба значения. Ноль тоже можно."); return@setOnClickListener
                }
                if (date < periodStart || date > dateFormat.format(Date())) { toast("Выбери дату внутри открытого периода"); return@setOnClickListener }
                if (allKnownDates().contains(date)) { toast("За эту дату запись уже существует"); return@setOnClickListener }
                val newEntry = Entry(date, clean, scrap, noteInput.text.toString().trim())
                entries.add(newEntry)
                entries.sortByDescending { it.date }
                saveEntries()
                toast("Запись сохранена")
                render()
            }
        }, matchWrap())
        root.addView(form)

        val listHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(18), 0, dp(8))
        }
        listHeader.addView(label("Записи периода", 19f, 0xFF17243A.toInt(), true), LinearLayout.LayoutParams(0, -2, 1f))
        listHeader.addView(Button(this).apply { text = "Закрыть период"; setOnClickListener { closePeriod() } })
        root.addView(listHeader)

        if (entries.isEmpty()) {
            root.addView(label("Пока нет записей. Добавь первую смену выше.", 14f, 0xFF68758A.toInt()))
        } else entries.forEach { entry ->
            val row = card()
            row.addView(label(formatDate(entry.date), 15f, 0xFF17243A.toInt(), true))
            row.addView(label("Чистая: " + qty(entry.clean) + " кг · Брак: " + qty(entry.scrap) + " кг", 13f, 0xFF34445B.toInt()))
            row.addView(label(money(entry.clean * cleanRate + entry.scrap * scrapRate) + " сом", 16f, 0xFF087B58.toInt(), true))
            if (entry.note.isNotBlank()) row.addView(label(entry.note, 12f, 0xFF68758A.toInt()))
            row.addView(Button(this).apply {
                text = "⋮"
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setItems(arrayOf("Изменить запись", "Удалить запись")) { _, choice ->
                            if (choice == 0) editEntry(entry)
                            else AlertDialog.Builder(this@MainActivity)
                                .setMessage("Удалить запись за " + formatDate(entry.date) + "?")
                                .setNegativeButton("Отмена", null)
                                .setPositiveButton("Удалить") { _, _ -> entries.remove(entry); saveEntries(); render() }.show()
                        }.show()
                }
            })
            root.addView(row)
        }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun editEntry(entry: Entry) {
        val dateInput = EditText(this).apply { setText(entry.date); hint = "ГГГГ-ММ-ДД" }
        val cleanInput = numberInput("Чистая продукция, кг").apply { setText((entry.clean * 1000).toLong().toString()) }
        val scrapInput = numberInput("Брак, кг").apply { setText((entry.scrap * 1000).toLong().toString()) }
        val noteInput = EditText(this).apply { setText(entry.note); hint = "Комментарий" }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(fieldCaption("Дата")); addView(dateInput)
            addView(fieldCaption("Чистая продукция, кг")); addView(cleanInput)
            addView(fieldCaption("Брак, кг")); addView(scrapInput)
            addView(fieldCaption("Комментарий")); addView(noteInput)
        }
        AlertDialog.Builder(this).setTitle("Изменить запись").setView(box)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить") { _, _ ->
                val date = dateInput.text.toString().trim()
                val clean = cleanInput.text.toString().replace(',', '.').toDoubleOrNull()
                val scrap = scrapInput.text.toString().replace(',', '.').toDoubleOrNull()
                if (!validDate(date) || clean == null || scrap == null || clean < 0 || scrap < 0) {
                    toast("Проверь дату и значения в кг"); return@setPositiveButton
                }
                if (date < periodStart || date > dateFormat.format(Date())) {
                    toast("Дата должна входить в текущий открытый период"); return@setPositiveButton
                }
                if (allKnownDates().contains(date) && date != entry.date) {
                    toast("За эту дату запись уже существует"); return@setPositiveButton
                }
                entries.remove(entry)
                entries.add(Entry(date, clean / 1000.0, scrap / 1000.0, noteInput.text.toString().trim()))
                entries.sortByDescending { it.date }
                saveEntries()
                render()
            }.show()
    }

    private fun closePeriod() {
        val clean = entries.sumOf { it.clean }
        val scrap = entries.sumOf { it.scrap }
        val pay = clean * cleanRate + scrap * scrapRate
        val days = calendarDays()
        val average = if (days > 0) pay / days else 0.0
        val end = dateFormat.format(Date())
        AlertDialog.Builder(this)
            .setTitle("Закрыть расчётный период?")
            .setMessage("Период: " + formatDate(periodStart) + " – " + formatDate(end) +
                "\nЧистая продукция: " + qty(clean) + " кг\nБрак: " + qty(scrap) + " кг" +
                "\nНачислено: " + money(pay) + " сом\nСреднее за день: " + money(average) +
                " сом (" + days + " календарных дней)\n\nЗаписи попадут в архив, начнётся новый период.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Закрыть") { _, _ ->
                val archive = JSONArray(prefs.getString("archive", "[]") ?: "[]")
                val recordArray = JSONArray()
                entries.sortedBy { it.date }.forEach { e ->
                    recordArray.put(JSONObject().put("date", e.date).put("clean", e.clean)
                        .put("scrap", e.scrap).put("note", e.note))
                }
                val number = nextPeriodNumber(archive)
                archive.put(JSONObject().put("number", number).put("name", "Период $number")
                    .put("start", periodStart).put("end", end)
                    .put("clean", clean).put("scrap", scrap).put("records", recordArray))
                val nextStart = nextCalendarDate(end)
                prefs.edit().putString("archive", archive.toString()).putString("entries", "[]")
                    .putString("period_start", nextStart).apply()
                periodStart = nextStart
                entries.clear()
                selectedArchiveIndex = -1
                toast("Период закрыт и сохранён в архив")
                render()
            }.show()
    }

    private fun allEntries(): List<Entry> {
        val result = entries.toMutableList()
        val history = JSONArray(prefs.getString("historical_entries", "[]") ?: "[]")
        for (i in 0 until history.length()) {
            val o = history.optJSONObject(i) ?: continue
            result.add(Entry(o.optString("date"), o.optDouble("clean"), o.optDouble("scrap"), o.optString("note")))
        }
        val a = JSONArray(prefs.getString("archive", "[]") ?: "[]")
        for (i in 0 until a.length()) {
            val records = a.optJSONObject(i)?.optJSONArray("records") ?: continue
            for (j in 0 until records.length()) {
                val o = records.optJSONObject(j) ?: continue
                result.add(Entry(o.optString("date"), o.optDouble("clean"), o.optDouble("scrap"), o.optString("note")))
            }
        }
        return result.distinctBy { it.date }.sortedBy { it.date }
    }

    private fun showCharts() {
        val data = allEntries()
        if (data.isEmpty()) { toast("Пока нет данных для графиков"); return }
        val chart = ProductionChart(data)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(4)) }
        box.addView(label("Деньги · Тоннаж · Брак", 13f, 0xFF34445B.toInt(), true))
        box.addView(label("Увеличение двумя пальцами, перемещение одним пальцем, нажатие на точку показывает значения.", 11f, 0xFF68758A.toInt()))
        box.addView(chart, LinearLayout.LayoutParams(-1, dp(340)))
        box.addView(Button(this).apply { text = "Сбросить масштаб"; setOnClickListener { chart.reset() } })
        AlertDialog.Builder(this).setTitle("Графики за всё время").setView(box).setPositiveButton("Закрыть", null).show()
    }

    private inner class ProductionChart(private val data: List<Entry>) : View(this@MainActivity) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private var zoom = 1f
        private var shift = 0f
        private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                zoom = (zoom * d.scaleFactor).coerceIn(1f, 10f)
                invalidate()
                return true
            }
        })
        private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onScroll(a: MotionEvent?, b: MotionEvent, dx: Float, dy: Float): Boolean {
                if (zoom > 1f) shift = (shift - dx).coerceIn(-width * (zoom - 1f), 0f)
                invalidate()
                return true
            }
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val left = dp(38).toFloat()
                val span = calendarDaysBetween(data.first().date, data.last().date).coerceAtLeast(2) - 1
                val step = (width - dp(52)).toFloat() / span
                val index = data.indices.minByOrNull { idx ->
                    kotlin.math.abs(left + (calendarDaysBetween(data.first().date, data[idx].date) - 1) * step * zoom + shift - e.x)
                } ?: 0
                val item = data[index]
                AlertDialog.Builder(this@MainActivity).setTitle(formatDate(item.date))
                    .setMessage("Заработок: " + money(item.clean * cleanRate + item.scrap * scrapRate) +
                        " сом\nЧистая продукция: " + qty(item.clean) + " кг\nБрак: " + qty(item.scrap) + " кг")
                    .setPositiveButton("ОК", null).show()
                return true
            }
        })
        fun reset() { zoom = 1f; shift = 0f; invalidate() }
        override fun onTouchEvent(e: MotionEvent): Boolean { scaler.onTouchEvent(e); gestures.onTouchEvent(e); return true }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val l = dp(38).toFloat(); val r = width - dp(10).toFloat()
            val t = dp(18).toFloat(); val b = height - dp(34).toFloat()
            p.color = 0xFFD5DCE5.toInt(); p.strokeWidth = dp(1).toFloat()
            for (i in 0..4) { val y = t + (b - t) * i / 4f; canvas.drawLine(l, y, r, y, p) }
            val spanDays = (calendarDaysBetween(data.first().date, data.last().date) - 1).coerceAtLeast(1)
            val step = (r - l) / spanDays
            val save = canvas.save()
            canvas.clipRect(l, t, r, b)
            canvas.translate(shift, 0f); canvas.scale(zoom, 1f, l, 0f)
            fun line(value: (Entry) -> Double, color: Int) {
                val max = data.maxOf(value).coerceAtLeast(1.0)
                val path = Path()
                p.color = color; p.style = Paint.Style.STROKE; p.strokeWidth = dp(2).toFloat()
                data.forEachIndexed { i, e ->
                    val x = l + (calendarDaysBetween(data.first().date, e.date) - 1) * step
                    val y = b - (value(e) / max).toFloat() * (b - t)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                canvas.drawPath(path, p)
                p.style = Paint.Style.FILL
                data.forEachIndexed { i, e ->
                    canvas.drawCircle(l + (calendarDaysBetween(data.first().date, e.date) - 1) * step, b - (value(e) / max).toFloat() * (b - t), dp(2).toFloat(), p)
                }
            }
            line({ it.clean * cleanRate + it.scrap * scrapRate }, Color.rgb(22, 163, 74))
            line({ it.clean }, Color.rgb(37, 99, 235))
            line({ it.scrap }, Color.rgb(217, 119, 6))
            canvas.restoreToCount(save)
            p.color = Color.DKGRAY; p.textSize = dp(10).toFloat()
            canvas.drawText(formatDate(data.first().date), l, height - dp(10).toFloat(), p)
            val end = formatDate(data.last().date)
            canvas.drawText(end, r - p.measureText(end), height - dp(10).toFloat(), p)
        }
    }

    private fun showArchive() {
        val archive = try {
            JSONArray(prefs.getString("archive", "[]") ?: "[]")
        } catch (_: Exception) {
            JSONArray()
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val history = JSONArray(prefs.getString("historical_entries", "[]") ?: "[]")
        if (history.length() > 0 || archiveEditing) {
            val historicalCard = card()
            historicalCard.addView(label("Исторические смены", 15f, 0xFF17243A.toInt(), true))
            historicalCard.addView(label("Записи за даты до начала работы с программой. Они не входят в текущий расчётный период.", 12f, 0xFF68758A.toInt()))
            for (j in 0 until history.length()) {
                val e = history.optJSONObject(j) ?: continue
                val line = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
                line.addView(label(formatDate(e.optString("date")) + " · " + qty(e.optDouble("clean")) + " кг · брак " + qty(e.optDouble("scrap")) + " кг", 12f, 0xFF34445B.toInt()), LinearLayout.LayoutParams(0, -2, 1f))
                if (archiveEditing) line.addView(Button(this).apply {
                    text = "⋮"
                    setOnClickListener {
                        AlertDialog.Builder(this@MainActivity).setItems(arrayOf("Изменить запись", "Удалить запись")) { _, choice ->
                            if (choice == 0) editHistoricalRecord(j)
                            else AlertDialog.Builder(this@MainActivity).setMessage("Удалить историческую смену?")
                                .setNegativeButton("Отмена", null).setPositiveButton("Удалить") { _, _ ->
                                    val updated = JSONArray(prefs.getString("historical_entries", "[]") ?: "[]")
                                    updated.remove(j)
                                    prefs.edit().putString("historical_entries", updated.toString()).apply()
                                    showArchive()
                                }.show()
                        }.show()
                    }
                })
                historicalCard.addView(line)
            }
            if (archiveEditing) historicalCard.addView(Button(this).apply {
                text = "Добавить историческую смену"
                setOnClickListener { addHistoricalRecord() }
            })
            box.addView(historicalCard)
        }
        if (archive.length() == 0) {
            if (history.length() == 0) box.addView(label("Архив пока пуст. Закрой расчётный период, и его результат появится здесь.", 14f, 0xFF68758A.toInt()))
        } else {
            selectedArchiveIndex = if (selectedArchiveIndex < 0) archive.length() - 1 else selectedArchiveIndex.coerceIn(0, archive.length() - 1)
            val navigation = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            navigation.addView(Button(this).apply {
                text = "‹ Старее"
                isEnabled = selectedArchiveIndex > 0
                setOnClickListener { selectedArchiveIndex--; showArchive() }
            })
            navigation.addView(label("Период " + (selectedArchiveIndex + 1) + " из " + archive.length(), 14f, 0xFF17243A.toInt(), true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, -2, 1f))
            navigation.addView(Button(this).apply {
                text = "Новее ›"
                isEnabled = selectedArchiveIndex < archive.length() - 1
                setOnClickListener { selectedArchiveIndex++; showArchive() }
            })
            box.addView(navigation)
            for (i in selectedArchiveIndex..selectedArchiveIndex) {
                val item = archive.getJSONObject(i)
                val start = item.optString("start")
                val end = item.optString("end")
                val days = calendarDaysBetween(start, end)
                val clean = item.optDouble("clean")
                val scrap = item.optDouble("scrap")
                val pay = clean * cleanRate + scrap * scrapRate
                val average = if (days > 0) pay / days else 0.0
                val record = card()
                val periodNumber = item.optInt("number", i + 1)
                record.addView(label(item.optString("name", "Период $periodNumber"), 17f, 0xFF17243A.toInt(), true))
                record.addView(label(formatDate(start) + " - " + formatDate(end), 13f, 0xFF68758A.toInt()))
                record.addView(label("Прошло дней: " + days, 13f, 0xFF68758A.toInt()))
                record.addView(label("Заработано: " + money(pay) + " сом", 19f, 0xFF087B58.toInt(), true))
                record.addView(label("В среднем: " + money(average) + " сом/день", 15f, 0xFF1557A6.toInt(), true))
                record.addView(label("Чистая продукция: " + qty(clean) + " кг · Брак: " + qty(scrap) + " кг", 12f, 0xFF68758A.toInt()))
                val saved = item.optJSONArray("records") ?: JSONArray()
                if (saved.length() > 0) {
                    for (j in 0 until saved.length()) {
                        val e = saved.optJSONObject(j) ?: continue
                        val line = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
                        line.addView(label(formatDate(e.optString("date")) + " · " + qty(e.optDouble("clean")) + " кг · брак " + qty(e.optDouble("scrap")) + " кг", 12f, 0xFF34445B.toInt()), LinearLayout.LayoutParams(0, -2, 1f))
                        if (archiveEditing) line.addView(Button(this).apply {
                            text = "⋮"
                            setOnClickListener {
                                AlertDialog.Builder(this@MainActivity)
                                    .setItems(arrayOf("Изменить запись", "Удалить запись")) { _, choice ->
                                        if (choice == 0) editArchivedRecord(i, j)
                                        else AlertDialog.Builder(this@MainActivity).setMessage("Удалить эту запись?")
                                            .setNegativeButton("Отмена", null).setPositiveButton("Удалить") { _, _ ->
                                                saved.remove(j)
                                                item.put("records", saved)
                                                var csum = 0.0; var ssum = 0.0
                                                for (k in 0 until saved.length()) {
                                                    csum += saved.getJSONObject(k).optDouble("clean")
                                                    ssum += saved.getJSONObject(k).optDouble("scrap")
                                                }
                                                item.put("clean", csum).put("scrap", ssum)
                                                archive.put(i, item)
                                                prefs.edit().putString("archive", archive.toString()).apply()
                                                showArchive()
                                            }.show()
                                    }.show()
                            }
                        })
                        record.addView(line)
                    }
                }
                if (archiveEditing) record.addView(Button(this).apply {
                    text = "Добавить смену в этот период"
                    setOnClickListener { addArchivedRecord(i) }
                })
                box.addView(record)
            }
        }
        val scroll = ScrollView(this).apply { addView(box) }
        AlertDialog.Builder(this).setTitle("История расчётных периодов").setView(scroll)
            .setPositiveButton("Закрыть", null).show()
    }

    private fun addHistoricalRecord() {
        val date = EditText(this).apply { hint = "ГГГГ-ММ-ДД" }
        val clean = numberInput("Чистая продукция, кг").apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val scrap = numberInput("Брак, кг").apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val note = EditText(this).apply { hint = "Комментарий (необязательно)" }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), 0)
            addView(fieldCaption("Дата")); addView(date); addView(fieldCaption("Чистая, кг")); addView(clean)
            addView(fieldCaption("Брак, кг")); addView(scrap); addView(fieldCaption("Комментарий")); addView(note) }
        AlertDialog.Builder(this).setTitle("Добавить историческую смену").setView(box)
            .setNegativeButton("Отмена", null).setPositiveButton("Сохранить") { _, _ ->
                val d = date.text.toString().trim()
                val c = clean.text.toString().toLongOrNull()
                val b = scrap.text.toString().toLongOrNull()
                if (!validDate(d) || c == null || b == null || c < 0 || b < 0) { toast("Проверь дату и значения в кг"); return@setPositiveButton }
                if (allKnownDates().contains(d)) { toast("За эту дату запись уже существует"); return@setPositiveButton }
                val h = JSONArray(prefs.getString("historical_entries", "[]") ?: "[]")
                h.put(JSONObject().put("date", d).put("clean", c / 1000.0).put("scrap", b / 1000.0).put("note", note.text.toString().trim()))
                prefs.edit().putString("historical_entries", h.toString()).apply()
                showArchive()
            }.show()
    }

    private fun editHistoricalRecord(index: Int) {
        val h = JSONArray(prefs.getString("historical_entries", "[]") ?: "[]")
        val old = h.optJSONObject(index) ?: return
        val date = EditText(this).apply { setText(old.optString("date")) }
        val clean = numberInput("Чистая продукция, кг").apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }.apply { setText((old.optDouble("clean") * 1000).toLong().toString()) }
        val scrap = numberInput("Брак, кг").apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }.apply { setText((old.optDouble("scrap") * 1000).toLong().toString()) }
        val note = EditText(this).apply { setText(old.optString("note")) }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), 0)
            addView(fieldCaption("Дата")); addView(date); addView(fieldCaption("Чистая, кг")); addView(clean)
            addView(fieldCaption("Брак, кг")); addView(scrap); addView(fieldCaption("Комментарий")); addView(note) }
        AlertDialog.Builder(this).setTitle("Изменить историческую смену").setView(box)
            .setNegativeButton("Отмена", null).setPositiveButton("Сохранить") { _, _ ->
                val d = date.text.toString().trim()
                val c = clean.text.toString().toLongOrNull()
                val b = scrap.text.toString().toLongOrNull()
                if (!validDate(d) || c == null || b == null || c < 0 || b < 0) { toast("Проверь дату и значения в кг"); return@setPositiveButton }
                if (allStoredDatesExcept(old.optString("date")).contains(d)) { toast("За эту дату уже есть запись"); return@setPositiveButton }
                h.put(index, JSONObject().put("date", d).put("clean", c / 1000.0).put("scrap", b / 1000.0).put("note", note.text.toString().trim()))
                prefs.edit().putString("historical_entries", h.toString()).apply()
                showArchive()
            }.show()
    }

    private fun editArchivedRecord(periodIndex: Int, recordIndex: Int) {
        val a = JSONArray(prefs.getString("archive", "[]") ?: "[]")
        val period = a.optJSONObject(periodIndex) ?: return
        val records = period.optJSONArray("records") ?: JSONArray()
        val old = records.optJSONObject(recordIndex) ?: return
        val date = EditText(this).apply { setText(old.optString("date")) }
        val clean = numberInput("Чистая продукция, кг").apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }.apply { setText((old.optDouble("clean") * 1000).toLong().toString()) }
        val scrap = numberInput("Брак, кг").apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }.apply { setText((old.optDouble("scrap") * 1000).toLong().toString()) }
        val note = EditText(this).apply { setText(old.optString("note")); hint = "Комментарий" }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), 0)
            addView(fieldCaption("Дата ГГГГ-ММ-ДД")); addView(date); addView(fieldCaption("Чистая, кг")); addView(clean)
            addView(fieldCaption("Брак, кг")); addView(scrap); addView(fieldCaption("Комментарий")); addView(note) }
        AlertDialog.Builder(this).setTitle("Изменить запись архива").setView(box)
            .setNegativeButton("Отмена", null).setPositiveButton("Сохранить") { _, _ ->
                val d = date.text.toString().trim()
                val cv = clean.text.toString().toLongOrNull()
                val sv = scrap.text.toString().toLongOrNull()
                if (!validDate(d) || cv == null || sv == null || cv < 0 || sv < 0) { toast("Проверь дату и значения в кг"); return@setPositiveButton }
                if (d < period.optString("start") || d > period.optString("end")) { toast("Дата должна оставаться внутри этого периода"); return@setPositiveButton }
                if (allStoredDatesExcept(old.optString("date")).contains(d)) { toast("За эту дату уже есть запись"); return@setPositiveButton }
                records.put(recordIndex, JSONObject().put("date", d).put("clean", cv / 1000.0).put("scrap", sv / 1000.0).put("note", note.text.toString().trim()))
                period.put("records", records)
                period.put("clean", sumRecords(records, "clean")).put("scrap", sumRecords(records, "scrap"))
                a.put(periodIndex, period)
                prefs.edit().putString("archive", a.toString()).apply()
                showArchive()
            }.show()
    }

    private fun addArchivedRecord(periodIndex: Int) {
        val a = JSONArray(prefs.getString("archive", "[]") ?: "[]")
        val period = a.optJSONObject(periodIndex) ?: return
        val date = EditText(this).apply { hint = "ГГГГ-ММ-ДД" }
        val clean = numberInput("Чистая продукция, кг").apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val scrap = numberInput("Брак, кг").apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val note = EditText(this).apply { hint = "Комментарий" }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), 0)
            addView(date); addView(clean); addView(scrap); addView(note) }
        AlertDialog.Builder(this).setTitle("Добавить смену в архив").setView(box)
            .setNegativeButton("Отмена", null).setPositiveButton("Добавить") { _, _ ->
                val d = date.text.toString().trim()
                val cv = clean.text.toString().toLongOrNull()
                val sv = scrap.text.toString().toLongOrNull()
                if (!validDate(d) || d < period.optString("start") || d > period.optString("end") || cv == null || sv == null || cv < 0 || sv < 0) {
                    toast("Проверь дату и значения в кг"); return@setPositiveButton
                }
                if (allStoredDatesExcept("").contains(d)) { toast("За эту дату уже есть запись"); return@setPositiveButton }
                val records = period.optJSONArray("records") ?: JSONArray()
                records.put(JSONObject().put("date", d).put("clean", cv / 1000.0).put("scrap", sv / 1000.0).put("note", note.text.toString().trim()))
                period.put("records", records).put("clean", sumRecords(records, "clean")).put("scrap", sumRecords(records, "scrap"))
                a.put(periodIndex, period)
                prefs.edit().putString("archive", a.toString()).apply()
                showArchive()
            }.show()
    }

    private fun sumRecords(a: JSONArray, key: String): Double {
        var total = 0.0
        for (i in 0 until a.length()) total += a.optJSONObject(i)?.optDouble(key, 0.0) ?: 0.0
        return total
    }

    private fun allStoredDatesExcept(except: String): Set<String> {
        val dates = entries.map { it.date }.filter { it != except }.toMutableSet()
        val history = JSONArray(prefs.getString("historical_entries", "[]") ?: "[]")
        for (i in 0 until history.length()) {
            val d = history.optJSONObject(i)?.optString("date") ?: ""
            if (d != except) dates.add(d)
        }
        val a = JSONArray(prefs.getString("archive", "[]") ?: "[]")
        for (i in 0 until a.length()) {
            val records = a.optJSONObject(i)?.optJSONArray("records") ?: continue
            for (j in 0 until records.length()) {
                val d = records.optJSONObject(j)?.optString("date") ?: ""
                if (d != except) dates.add(d)
            }
        }
        return dates
    }

    private fun calendarDaysBetween(start: String, end: String): Int {
        return try {
            val cal = java.util.Calendar.getInstance()
            cal.time = dateFormat.parse(start) ?: return 0
            val finish = java.util.Calendar.getInstance()
            finish.time = dateFormat.parse(end) ?: return 0
            var count = 0
            while (!cal.after(finish) && count < 50000) {
                count++
                cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
            }
            count
        } catch (_: Exception) { 0 }
    }

    private fun exportTransferCode() {
        val root = JSONObject().put("v", 1).put("currentStart", periodStart)
        val current = JSONArray()
        entries.sortedBy { it.date }.forEach { e ->
            current.put(JSONObject().put("d", e.date).put("c", e.clean).put("s", e.scrap).put("n", e.note))
        }
        root.put("current", current)
        val periods = JSONArray(prefs.getString("archive", "[]") ?: "[]")
        val safePeriods = JSONArray()
        for (i in 0 until periods.length()) {
            val p = periods.optJSONObject(i) ?: continue
            val records = p.optJSONArray("records") ?: JSONArray()
            val safeRecords = JSONArray()
            for (j in 0 until records.length()) {
                val e = records.optJSONObject(j) ?: continue
                safeRecords.put(JSONObject().put("d", e.optString("date")).put("c", e.optDouble("clean"))
                    .put("s", e.optDouble("scrap")).put("n", e.optString("note")))
            }
            safePeriods.put(JSONObject().put("a", p.optString("start")).put("b", p.optString("end")).put("r", safeRecords))
        }
        root.put("periods", safePeriods)
        val safeHistory = JSONArray()
        val historical = JSONArray(prefs.getString("historical_entries", "[]") ?: "[]")
        for (i in 0 until historical.length()) {
            val e = historical.optJSONObject(i) ?: continue
            safeHistory.put(JSONObject().put("d", e.optString("date")).put("c", e.optDouble("clean"))
                .put("s", e.optDouble("scrap")).put("n", e.optString("note")))
        }
        root.put("history", safeHistory)
        try {
            val raw = root.toString().toByteArray(Charsets.UTF_8)
            val zipped = ByteArrayOutputStream().also { out -> DeflaterOutputStream(out).use { it.write(raw) } }.toByteArray()
            val crc = CRC32().apply { update(raw) }.value.toString(16)
            val code = "PC1:$crc:" + Base64.encodeToString(zipped, Base64.NO_WRAP or Base64.URL_SAFE)
            val field = EditText(this).apply { setText(code); minLines = 4; setTextIsSelectable(true) }
            AlertDialog.Builder(this).setTitle("Код переноса")
                .setMessage("Скопируй весь текст и отправь на другой телефон. Тарифы и заработок не передаются.")
                .setView(field).setPositiveButton("Копировать") { _, _ ->
                    val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("ProControl", code))
                    toast("Код скопирован")
                }.setNegativeButton("Закрыть", null).show()
        } catch (_: Exception) { toast("Не удалось создать код") }
    }

    private fun importTransferCode() {
        val field = EditText(this).apply { hint = "Вставь код PC1:..."; minLines = 4 }
        AlertDialog.Builder(this).setTitle("Импорт данных").setView(field)
            .setNegativeButton("Отмена", null).setPositiveButton("Проверить") { _, _ ->
                try {
                    val parts = field.text.toString().trim().split(':', limit = 3)
                    if (parts.size != 3 || parts[0] != "PC1") throw Exception("Неверный формат кода")
                    val zipped = Base64.decode(parts[2], Base64.NO_WRAP or Base64.URL_SAFE)
                    val raw = InflaterInputStream(ByteArrayInputStream(zipped)).use { it.readBytes() }
                    val crc = CRC32().apply { update(raw) }.value.toString(16)
                    if (!crc.equals(parts[1], true)) throw Exception("Контрольная сумма не совпала")
                    val root = JSONObject(String(raw, Charsets.UTF_8))
                    if (root.optInt("v") != 1) throw Exception("Версия кода не поддерживается")
                    val newCurrent = mutableListOf<Entry>()
                    val ca = root.optJSONArray("current") ?: JSONArray()
                    for (i in 0 until ca.length()) {
                        val e = ca.getJSONObject(i); newCurrent.add(Entry(e.getString("d"), e.optDouble("c"), e.optDouble("s"), e.optString("n")))
                    }
                    val newPeriods = root.optJSONArray("periods") ?: JSONArray()
                    val newHistory = root.optJSONArray("history") ?: JSONArray()
                    var count = 0
                    val known = allKnownDates()
                    newCurrent.forEach { if (!known.contains(it.date)) count++ }
                    for (i in 0 until newPeriods.length()) {
                        val p = newPeriods.getJSONObject(i); val rs = p.optJSONArray("r") ?: JSONArray()
                        for (j in 0 until rs.length()) if (!known.contains(rs.getJSONObject(j).optString("d"))) count++
                    }
                    for (i in 0 until newHistory.length()) if (!known.contains(newHistory.getJSONObject(i).optString("d"))) count++
                    AlertDialog.Builder(this).setTitle("Подтвердить импорт")
                        .setMessage("Новых записей: $count. Существующие даты не будут перезаписаны.")
                        .setNegativeButton("Отмена", null).setPositiveButton("Импортировать") { _, _ ->
                            val archive = JSONArray(prefs.getString("archive", "[]") ?: "[]")
                            val freshPhone = entries.isEmpty() && archive.length() == 0
                            newCurrent.forEach { if (known.add(it.date)) entries.add(it) }
                            if (freshPhone && validDate(root.optString("currentStart"))) periodStart = root.optString("currentStart")
                            for (i in 0 until newPeriods.length()) {
                                val p = newPeriods.getJSONObject(i)
                                val start = p.getString("a"); val end = p.getString("b")
                                var target: JSONObject? = null
                                for (j in 0 until archive.length()) {
                                    val existing = archive.optJSONObject(j)
                                    if (existing?.optString("start") == start && existing.optString("end") == end) target = existing
                                }
                                if (target == null) {
                                    target = JSONObject().put("start", start).put("end", end).put("records", JSONArray())
                                    archive.put(target)
                                }
                                val records = target.optJSONArray("records") ?: JSONArray()
                                val rs = p.optJSONArray("r") ?: JSONArray()
                                for (j in 0 until rs.length()) {
                                    val e = rs.getJSONObject(j); val d = e.getString("d")
                                    if (known.add(d)) records.put(JSONObject().put("date", d).put("clean", e.optDouble("c"))
                                        .put("scrap", e.optDouble("s")).put("note", e.optString("n")))
                                }
                                target.put("records", records)
                                var cleanSum = 0.0; var scrapSum = 0.0
                                for (j in 0 until records.length()) { cleanSum += records.getJSONObject(j).optDouble("clean"); scrapSum += records.getJSONObject(j).optDouble("scrap") }
                                target.put("clean", cleanSum).put("scrap", scrapSum)
                            }
                            val history = JSONArray(prefs.getString("historical_entries", "[]") ?: "[]")
                            for (i in 0 until newHistory.length()) {
                                val e = newHistory.getJSONObject(i); val d = e.getString("d")
                                if (known.add(d)) history.put(JSONObject().put("date", d).put("clean", e.optDouble("c"))
                                    .put("scrap", e.optDouble("s")).put("note", e.optString("n")))
                            }
                            prefs.edit().putString("archive", archive.toString())
                                .putString("historical_entries", history.toString()).apply()
                            entries.sortByDescending { it.date }; saveEntries()
                            toast("Импорт завершён"); render()
                        }.show()
                } catch (e: Exception) { toast("Ошибка импорта: ${e.message}") }
            }.show()
    }

    private fun allKnownDates(): MutableSet<String> {
        val dates = entries.map { it.date }.toMutableSet()
        val history = JSONArray(prefs.getString("historical_entries", "[]") ?: "[]")
        for (i in 0 until history.length()) dates.add(history.optJSONObject(i)?.optString("date") ?: "")
        val a = JSONArray(prefs.getString("archive", "[]") ?: "[]")
        for (i in 0 until a.length()) {
            val records = a.optJSONObject(i)?.optJSONArray("records") ?: continue
            for (j in 0 until records.length()) dates.add(records.optJSONObject(j)?.optString("date") ?: "")
        }
        return dates
    }

    private fun showRatesDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val clean = numberInput("Сом за тонну").apply { setText(cleanRate.toString()) }
        val scrap = numberInput("Сом за тонну").apply { setText(scrapRate.toString()) }
        val archiveSwitch = Switch(this).apply {
            text = "Разрешить редактирование закрытых периодов"
            isChecked = archiveEditing
        }
        box.addView(fieldCaption("Тариф чистой продукции, сом/т")); box.addView(clean, matchWrap())
        box.addView(fieldCaption("Тариф брака, сом/т")); box.addView(scrap, matchWrap())
        box.addView(archiveSwitch)
        box.addView(Button(this).apply { text = "Скопировать код переноса"; setOnClickListener { exportTransferCode() } })
        box.addView(Button(this).apply { text = "Вставить код переноса"; setOnClickListener { importTransferCode() } })
        AlertDialog.Builder(this).setTitle("Настройки").setView(box)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить") { _, _ ->
                val c = clean.text.toString().toLongOrNull()
                val s = scrap.text.toString().replace(',', '.').toDoubleOrNull()
                if (c == null || s == null || c < 0 || s < 0) toast("Некорректный тариф")
                else {
                    cleanRate = c; scrapRate = s
                    archiveEditing = archiveSwitch.isChecked
                    prefs.edit().putString("clean_rate", c.toString()).putString("scrap_rate", s.toString())
                        .putBoolean("archive_editing", archiveEditing).apply()
                    render()
                }
            }.show()
    }

    private fun nextCalendarDate(value: String): String {
        val calendar = java.util.Calendar.getInstance()
        calendar.time = dateFormat.parse(value) ?: Date()
        calendar.add(java.util.Calendar.DAY_OF_MONTH, 1)
        return dateFormat.format(calendar.time)
    }

    private fun calendarDays(): Int = calendarDaysBetween(periodStart, dateFormat.format(Date()))

    private fun showDatePicker(initial: String, onPicked: (String) -> Unit) {
        val cal = java.util.Calendar.getInstance()
        try { cal.time = dateFormat.parse(initial) ?: Date() } catch (_: Exception) {}
        DatePickerDialog(this, { _, year, month, day ->
            val picked = java.util.Calendar.getInstance().apply { set(year, month, day) }
            onPicked(dateFormat.format(picked.time))
        }, cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH), cal.get(java.util.Calendar.DAY_OF_MONTH)).show()
    }

    private fun nextPeriodNumber(archive: JSONArray): Int {
        var max = 0
        for (i in 0 until archive.length()) max = maxOf(max, archive.optJSONObject(i)?.optInt("number", i + 1) ?: 0)
        return max + 1
    }
    private fun validDate(value: String): Boolean {
        return try {
            if (!value.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) false
            else {
                val strict = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }
                val parsed = strict.parse(value)
                parsed != null && strict.format(parsed) == value
            }
        } catch (_: Exception) { false }
    }
    private fun formatDate(value: String): String = try { displayDate.format(dateFormat.parse(value)!!) } catch (_: Exception) { value }
    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12)); setBackgroundColor(0xFFFFFFFF.toInt())
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
    }
    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); if (bold) setTypeface(null, Typeface.BOLD); setPadding(0, dp(3), 0, dp(3))
    }
    private fun fieldCaption(value: String) = label(value, 13f, 0xFF536176.toInt(), true)
    private fun numberInput(hintValue: String) = EditText(this).apply {
        hint = hintValue; inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        setSingleLine(); textSize = 16f
    }
    private fun matchWrap() = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun qty(value: Double) = String.format(Locale("ru"), "%,.0f", value * 1000.0)
    private fun money(value: Double) = String.format(Locale("ru"), "%.2f", value)
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}