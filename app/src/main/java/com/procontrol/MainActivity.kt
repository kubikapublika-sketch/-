package com.procontrol

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.graphics.Typeface
import android.view.Gravity
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
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

    data class Entry(val date: String, val clean: Double, val scrap: Double, val note: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cleanRate = prefs.getString("clean_rate", "80")?.toDoubleOrNull() ?: 80.0
        scrapRate = prefs.getString("scrap_rate", "75")?.toDoubleOrNull() ?: 75.0
        periodStart = prefs.getString("period_start", null) ?: dateFormat.format(Date()).also {
            prefs.edit().putString("period_start", it).apply()
        }
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
        summary.addView(label("Чистая продукция: " + qty(cleanTotal) + " т", 14f, 0xFF26364D.toInt()))
        summary.addView(label("Брак: " + qty(scrapTotal) + " т", 14f, 0xFF26364D.toInt()))
        summary.addView(label("Прошло дней: " + days + " · Смен внесено: " + entries.size, 12f, 0xFF68758A.toInt()))
        summary.addView(label("Период: " + formatDate(periodStart) + " - " + formatDate(dateFormat.format(Date())), 12f, 0xFF68758A.toInt()))
        root.addView(summary)

        val addTitle = label("Добавить смену", 19f, 0xFF17243A.toInt(), true)
        addTitle.setPadding(0, dp(18), 0, dp(8))
        root.addView(addTitle)
        val form = card()
        val dateInput = EditText(this).apply {
            hint = "ГГГГ-ММ-ДД"; setSingleLine(); setText(dateFormat.format(Date())); textSize = 15f
        }
        val cleanInput = numberInput("Тонны чистой продукции")
        val scrapInput = numberInput("Тонны брака")
        val noteInput = EditText(this).apply { hint = "Комментарий (необязательно)"; textSize = 15f }
        form.addView(fieldCaption("Дата")); form.addView(dateInput, matchWrap())
        form.addView(fieldCaption("Чистая продукция, т")); form.addView(cleanInput, matchWrap())
        form.addView(fieldCaption("Брак, т")); form.addView(scrapInput, matchWrap())
        form.addView(fieldCaption("Комментарий")); form.addView(noteInput, matchWrap())
        form.addView(Button(this).apply {
            text = "Сохранить день"
            setOnClickListener {
                val date = dateInput.text.toString().trim()
                val clean = cleanInput.text.toString().replace(',', '.').toDoubleOrNull()
                val scrap = scrapInput.text.toString().replace(',', '.').toDoubleOrNull()
                if (!validDate(date)) { toast("Проверь дату: ГГГГ-ММ-ДД"); return@setOnClickListener }
                if (clean == null || scrap == null || clean < 0 || scrap < 0) {
                    toast("Введи оба значения. Ноль тоже можно."); return@setOnClickListener
                }
                if (entries.any { it.date == date }) { toast("За эту дату запись уже существует"); return@setOnClickListener }
                if (date < periodStart) { toast("Эта дата относится к закрытому периоду. Включи редактирование архива в настройках."); return@setOnClickListener }
                entries.add(Entry(date, clean, scrap, noteInput.text.toString().trim()))
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
            row.addView(label("Чистая: " + qty(entry.clean) + " т · Брак: " + qty(entry.scrap) + " т", 13f, 0xFF34445B.toInt()))
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
        val cleanInput = numberInput("Чистая продукция, т").apply { setText(entry.clean.toString()) }
        val scrapInput = numberInput("Брак, т").apply { setText(entry.scrap.toString()) }
        val noteInput = EditText(this).apply { setText(entry.note); hint = "Комментарий" }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(fieldCaption("Дата")); addView(dateInput)
            addView(fieldCaption("Чистая продукция, т")); addView(cleanInput)
            addView(fieldCaption("Брак, т")); addView(scrapInput)
            addView(fieldCaption("Комментарий")); addView(noteInput)
        }
        AlertDialog.Builder(this).setTitle("Изменить запись").setView(box)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить") { _, _ ->
                val date = dateInput.text.toString().trim()
                val clean = cleanInput.text.toString().replace(',', '.').toDoubleOrNull()
                val scrap = scrapInput.text.toString().replace(',', '.').toDoubleOrNull()
                if (!validDate(date) || clean == null || scrap == null || clean < 0 || scrap < 0) {
                    toast("Проверь дату и значения"); return@setPositiveButton
                }
                if (entries.any { it.date == date && it.date != entry.date }) {
                    toast("За эту дату запись уже существует"); return@setPositiveButton
                }
                entries.remove(entry)
                entries.add(Entry(date, clean, scrap, noteInput.text.toString().trim()))
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
                "\nЧистая продукция: " + qty(clean) + " т\nБрак: " + qty(scrap) + " т" +
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
                archive.put(JSONObject().put("start", periodStart).put("end", end)
                    .put("clean", clean).put("scrap", scrap).put("records", recordArray))
                val nextStart = nextCalendarDate(end)
                prefs.edit().putString("archive", archive.toString()).putString("entries", "[]")
                    .putString("period_start", nextStart).apply()
                periodStart = nextStart
                entries.clear()
                toast("Период закрыт и сохранён в архив")
                render()
            }.show()
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
        if (archive.length() == 0) {
            box.addView(label("Архив пока пуст. Закрой расчётный период, и его результат появится здесь.", 14f, 0xFF68758A.toInt()))
        } else {
            for (i in archive.length() - 1 downTo 0) {
                val item = archive.getJSONObject(i)
                val start = item.optString("start")
                val end = item.optString("end")
                val days = item.optInt("days")
                val pay = item.optDouble("pay")
                val average = item.optDouble("averagePerDay")
                val clean = item.optDouble("clean")
                val scrap = item.optDouble("scrap")
                val record = card()
                record.addView(label(formatDate(start) + " - " + formatDate(end), 15f, 0xFF17243A.toInt(), true))
                record.addView(label("Прошло дней: " + days, 13f, 0xFF68758A.toInt()))
                record.addView(label("Заработано: " + money(pay) + " сом", 19f, 0xFF087B58.toInt(), true))
                record.addView(label("В среднем: " + money(average) + " сом/день", 15f, 0xFF1557A6.toInt(), true))
                record.addView(label("Чистая продукция: " + qty(clean) + " т · Брак: " + qty(scrap) + " т", 12f, 0xFF68758A.toInt()))
                box.addView(record)
            }
        }
        val scroll = ScrollView(this).apply { addView(box) }
        AlertDialog.Builder(this).setTitle("История расчётных периодов").setView(scroll)
            .setPositiveButton("Закрыть", null).show()
    }

    private fun showRatesDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val clean = numberInput("Сом за тонну").apply { setText(cleanRate.toString()) }
        val scrap = numberInput("Сом за тонну").apply { setText(scrapRate.toString()) }
        box.addView(fieldCaption("Тариф чистой продукции, сом/т")); box.addView(clean, matchWrap())
        box.addView(fieldCaption("Тариф брака, сом/т")); box.addView(scrap, matchWrap())
        AlertDialog.Builder(this).setTitle("Настройка тарифов").setView(box)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить") { _, _ ->
                val c = clean.text.toString().replace(',', '.').toDoubleOrNull()
                val s = scrap.text.toString().replace(',', '.').toDoubleOrNull()
                if (c == null || s == null || c < 0 || s < 0) toast("Некорректный тариф")
                else {
                    cleanRate = c; scrapRate = s
                    prefs.edit().putString("clean_rate", c.toString()).putString("scrap_rate", s.toString()).apply()
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

    private fun calendarDays(): Int {
        val start = dateFormat.parse(periodStart)?.time ?: return 0
        val today = dateFormat.parse(dateFormat.format(Date()))?.time ?: return 0
        return (((today - start) / 86400000L).toInt() + 1).coerceAtLeast(0)
    }
    private fun validDate(value: String): Boolean = try {
        dateFormat.isLenient = false
        dateFormat.parse(value) != null
    } catch (_: Exception) { false }
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
    private fun qty(value: Double) = String.format(Locale("ru"), "%.2f", value)
    private fun money(value: Double) = String.format(Locale("ru"), "%.2f", value)
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}