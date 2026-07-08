package com.atrum.chat

import com.atrum.chat.transport.NostrMessageStore
import java.util.Calendar

/**
 * Общая логика для экранов статистики активности (GroupStatsActivity/UserStatsActivity).
 * Всё здесь — ТОЛЬКО чтение уже локально имеющейся истории (см. NostrMessageStore —
 * "долговечный локальный стор, накапливает всё когда-либо увиденное"). Никакого нового
 * сетевого пути и никакого отдельного "канала отчётности" профилей — участник и так уже
 * шлёт каждое сообщение с userId/timestamp всем, включая админа (обычный chat.txt),
 * этого достаточно для точной статистики без изменений протокола (см. CLAUDE.md §1).
 */
object StatsUtil {

    /** Порог "мусора" при расшифровке V1 (без аутентификации) — тот же, что в ChatActivity.decodeLines. */
    private const val GARBAGE_PERCENT_THRESHOLD = 25

    /** Декодирует ВСЮ историю чата (chat.txt) в список Message — тем же путём, что ChatActivity. */
    fun decodeAll(chatContent: String, password: String, chatId: String, myUserId: String, myName: String): List<Message> {
        val lines = chatContent.split("\n").filter { it.isNotEmpty() }
        return lines.mapNotNull { rawLine ->
            val line = rawLine.trim()
            CryptoHelper.decrypt(line, password, chatId)?.let { decrypted ->
                val garbage = decrypted.count { c ->
                    c.code in 0x80..0x9F ||
                        (c.code < 0x20 && c != '\n' && c != '\r' && c != '\t' &&
                            c.code !in setOf(0x01, 0x02, 0x11, 0x1E, 0x1F))
                }
                if (decrypted.length > 8 && garbage * 100 / decrypted.length > GARBAGE_PERCENT_THRESHOLD) null
                else Message.fromDecrypted(decrypted, myUserId, myName, emptySet(), raw = line)
            }
        }
    }

    /** Пара (расшифрованное сообщение, метаданные удаления) для раздела «Удалённые сообщения». */
    data class DeletedRow(val message: Message, val deletedAtMs: Long, val deleterPubkey: String)

    fun decodeDeleted(
        deleted: List<NostrMessageStore.DeletedMessage>,
        password: String, chatId: String, myUserId: String, myName: String
    ): List<DeletedRow> = deleted.mapNotNull { d ->
        val decrypted = CryptoHelper.decrypt(d.encryptedContent, password, chatId) ?: return@mapNotNull null
        val msg = Message.fromDecrypted(decrypted, myUserId, myName, emptySet(), raw = d.encryptedContent)
        DeletedRow(msg, d.deletedAtMs, d.deleterPubkey)
    }

    enum class Period { DAY, WEEK, MONTH, YEAR }

    private val MONTH_NAMES_RU = arrayOf("Янв", "Фев", "Мар", "Апр", "Май", "Июн", "Июл", "Авг", "Сен", "Окт", "Ноя", "Дек")

    private fun keyFor(cal: Calendar, period: Period): Long = when (period) {
        Period.DAY -> cal.get(Calendar.YEAR) * 1000L + cal.get(Calendar.DAY_OF_YEAR)
        Period.WEEK -> cal.get(Calendar.YEAR) * 100L + cal.get(Calendar.WEEK_OF_YEAR)
        Period.MONTH -> cal.get(Calendar.YEAR) * 100L + cal.get(Calendar.MONTH)
        Period.YEAR -> cal.get(Calendar.YEAR).toLong()
    }

    private fun labelFor(cal: Calendar, period: Period): String = when (period) {
        Period.DAY -> cal.get(Calendar.DAY_OF_MONTH).toString()
        Period.WEEK -> "#" + cal.get(Calendar.WEEK_OF_YEAR)
        Period.MONTH -> MONTH_NAMES_RU[cal.get(Calendar.MONTH)]
        Period.YEAR -> cal.get(Calendar.YEAR).toString()
    }

    private fun step(cal: Calendar, period: Period) {
        when (period) {
            Period.DAY -> cal.add(Calendar.DAY_OF_YEAR, -1)
            Period.WEEK -> cal.add(Calendar.WEEK_OF_YEAR, -1)
            Period.MONTH -> cal.add(Calendar.MONTH, -1)
            Period.YEAR -> cal.add(Calendar.YEAR, -1)
        }
    }

    /**
     * Реальные бакеты активности пользователя за последние [count] периодов (день/неделя/
     * месяц/год), заканчивая "сегодня". Бакеты без сообщений — с нулём (не пропускаются),
     * чтобы график не искажал равномерность активности. Порядок — от старых к новым.
     */
    fun buckets(messages: List<Message>, period: Period, count: Int): List<Pair<String, Int>> {
        val counter = HashMap<Long, Int>()
        val cal = Calendar.getInstance()
        for (m in messages) {
            cal.timeInMillis = m.timestampMs
            val k = keyFor(cal, period)
            counter[k] = (counter[k] ?: 0) + 1
        }
        val walker = Calendar.getInstance()
        val ordered = ArrayList<Pair<Long, String>>(count)
        repeat(count) {
            ordered.add(keyFor(walker, period) to labelFor(walker, period))
            step(walker, period)
        }
        return ordered.asReversed().map { (k, label) -> label to (counter[k] ?: 0) }
    }

    /** Число сообщений на каждый час суток (0..23), по ВСЕЙ истории — не зависит от периода. */
    fun hourHistogram(messages: List<Message>): IntArray {
        val out = IntArray(24)
        val cal = Calendar.getInstance()
        for (m in messages) {
            cal.timeInMillis = m.timestampMs
            out[cal.get(Calendar.HOUR_OF_DAY)]++
        }
        return out
    }

    /** Число различных календарных дней, в которые было хотя бы одно сообщение. */
    fun activeDaysCount(messages: List<Message>): Int {
        val cal = Calendar.getInstance()
        val days = HashSet<Long>()
        for (m in messages) {
            cal.timeInMillis = m.timestampMs
            days.add(cal.get(Calendar.YEAR) * 1000L + cal.get(Calendar.DAY_OF_YEAR))
        }
        return days.size
    }

    private val locale = java.util.Locale("ru")
    private val timeFmt = java.text.SimpleDateFormat("HH:mm", locale)
    private val dayMonthFmt = java.text.SimpleDateFormat("d MMM", locale)
    private val fullDateFmt = java.text.SimpleDateFormat("dd.MM.yy", locale)

    /** "Сегодня, HH:mm" / "Вчера, HH:mm" / "3 июл, HH:mm" / "03.07.24, HH:mm" — см. MessageAdapter.formatTime (та же схема границ). */
    fun formatMessageTime(context: android.content.Context, ms: Long): String {
        val now = Calendar.getInstance()
        val mc = Calendar.getInstance().apply { timeInMillis = ms }
        val time = timeFmt.format(java.util.Date(ms))
        val sameDay = now.get(Calendar.YEAR) == mc.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == mc.get(Calendar.DAY_OF_YEAR)
        if (sameDay) return context.getString(R.string.stats_time_today, time)
        val y = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
        val wasYesterday = y.get(Calendar.YEAR) == mc.get(Calendar.YEAR) && y.get(Calendar.DAY_OF_YEAR) == mc.get(Calendar.DAY_OF_YEAR)
        if (wasYesterday) return context.getString(R.string.stats_time_yesterday, time)
        return if (now.get(Calendar.YEAR) == mc.get(Calendar.YEAR))
            context.getString(R.string.stats_time_this_year, dayMonthFmt.format(java.util.Date(ms)), time)
        else
            context.getString(R.string.stats_time_other, fullDateFmt.format(java.util.Date(ms)), time)
    }
}
