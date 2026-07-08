package com.atrum.chat

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Сторож синхронизации Tor-чата: от момента нажатия «Подключиться» (JoinChatActivity) или
 * открытия/возврата в Tor-чат (ChatActivity) до ПЕРВОГО реального подтверждения, что
 * синхронизация действительно идёт (партнёр получен через profiles.txt ИЛИ сообщение реально
 * опубликовано на реле). Если за [SYNC_TIMEOUT_MS] ни одно подтверждение не пришло — ИЛИ
 * раньше происходит любое отклонение от сценария (необработанное исключение в pullProfiles/
 * pushMyProfile/appendLine, исчерпание ретраев, таймаут bootstrap Tor) — сразу показывает
 * подробный отчёт через [CrashHandler.report] (полноэкранный CrashActivity), а не просто
 * пишет в logcat.
 *
 * Запрошено пользователем: «сделай подробный лог и вылет для тор чатов если синхрон не
 * прошел после момента нажатия на кнопку подключиться. триггерить его должно всё что угодно
 * что не по сценарию».
 *
 * Отличие от [TorManager.logTorAndroidError] / [PluggableTransports.logPtError]: те — про
 * ДВИЖОК Tor (bootstrap/circuit/PT), уже репортят СВОИ ошибки сами (не дублируем здесь, кроме
 * одного специального случая — молчаливого таймаута bootstrap, см. [reportEngineFailure] и
 * TorManager.armTorAndroidWatchdog/startKmpTorEngineLocked). Этот класс — про РЕЗУЛЬТАТ
 * поверх уже поднятого (или ещё поднимающегося) Tor: реально ли синхронизировались данные
 * ИМЕННО ЭТОГО чата.
 *
 * НЕ спамит: максимум ОДИН отчёт за один цикл [arm]/[disarm] — это ограниченное окно ОДНОЙ
 * попытки подключения к конкретному чату, а не бесконечный фоновый цикл (в отличие от
 * штатных ретраев Snowflake, которые мы намеренно НЕ репортим — см. PluggableTransports.kt).
 * Активен только для Tor-чатов — вызывающий код сам решает, когда звать arm() (см.
 * JoinChatActivity.runConnect и ChatActivity.isTorChat()).
 */
object TorSyncWatchdog {

    /**
     * Таймаут окна наблюдения. С запасом над худшим сценарием ретраев одного сообщения
     * через Tor: publishToAnyRelay — до 20 сек/попытка при useTor=true, MessageSendManager —
     * до 5 попыток с бэкоффом (800+1500+3000+5000+8000 мс) ⇒ реалистичный потолок ~100-120 сек.
     * 150 сек оставляет запас, чтобы не путать «ещё не успело» с «реально сломано».
     */
    private const val SYNC_TIMEOUT_MS = 150_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Состояние одного окна наблюдения (arm..disarm/report). */
    private class Session(val chatId: String) {
        val armedAtMs = System.currentTimeMillis()
        val events = ConcurrentLinkedQueue<String>()
        val reported = AtomicBoolean(false)
        var timeoutJob: Job? = null
    }

    @Volatile private var current: Session? = null

    /**
     * Взводит наблюдение для [chatId]. Идемпотентно: если сессия для ТОГО ЖЕ chatId уже
     * активна (ещё не disarm()/report()) — не сбрасывает таймер заново (важно, т.к. одно и то
     * же «подключение» проходит через несколько экранов подряд: JoinChatActivity.runConnect →
     * ChatActivity.setupUi() — второй arm() не должен обнулять уже идущий отсчёт времени).
     */
    fun arm(context: Context, chatId: String) {
        val existing = current
        if (existing != null && existing.chatId == chatId && !existing.reported.get()) return
        val appCtx = context.applicationContext
        val session = Session(chatId)
        current = session
        record(chatId, "ARM", "watchdog взведён — ждём подтверждённую синхронизацию (партнёр или доставленное сообщение)")
        session.timeoutJob = scope.launch {
            delay(SYNC_TIMEOUT_MS)
            if (current === session && !session.reported.get()) {
                fail(
                    appCtx, session, "ТАЙМАУТ СИНХРОНИЗАЦИИ",
                    IllegalStateException(
                        "Синхронизация НЕ подтверждена за ${SYNC_TIMEOUT_MS / 1000} сек после нажатия " +
                            "«Подключиться»/открытия Tor-чата (chatId=${chatId.take(8)}…). Ни партнёр " +
                            "(profiles.txt), ни доставка сообщения ни разу не подтвердились за это окно."
                    )
                )
            }
        }
    }

    /** Вызывать при ПЕРВОМ реальном подтверждении синхронизации — сценарий штатный, отбой. */
    fun disarm(chatId: String, reason: String) {
        val session = current ?: return
        if (session.chatId != chatId || session.reported.get()) return
        record(chatId, "OK", "синхронизация подтверждена: $reason — сценарий штатный, снимаем с наблюдения")
        session.timeoutJob?.cancel()
        if (current === session) current = null
    }

    /** Копит диагностическое событие в текущей сессии (не триггерит отчёт сама по себе). */
    fun record(chatId: String, tag: String, message: String) {
        val session = current ?: return
        if (session.chatId != chatId) return
        val line = "[+${System.currentTimeMillis() - session.armedAtMs}мс] $tag: $message"
        println("ATRUM_TOR_SYNC: $line")
        session.events.add(line)
    }

    /**
     * Немедленный триггер для конкретного chatId — «что угодно не по сценарию»:
     * необработанное исключение в pullProfiles/pushMyProfile/appendLine, исчерпание ретраев
     * profile-sync, окончательный провал отправки сообщения (onSendFailed) и т.п. Не ждёт
     * [SYNC_TIMEOUT_MS] — показывает CrashActivity сразу, по требованию пользователя.
     */
    fun reportDeviation(context: Context, chatId: String, step: String, t: Throwable) {
        val session = current ?: return
        if (session.chatId != chatId || session.reported.get()) return
        fail(context.applicationContext, session, step, t)
    }

    /**
     * То же самое, но для сбоев уровня ДВИЖКА Tor, которые сейчас НИКАК не репортятся
     * (например, молчаливый таймаут bootstrap — просто выставляет FAILED без CrashHandler).
     * Движок один на процесс — репортит в ЛЮБУЮ активную сессию, не привязываясь к chatId.
     * Вызывать ТОЛЬКО там, где сбой иначе прошёл бы полностью не замеченным (не дублировать
     * туда, где TorManager/PluggableTransports уже сами зовут CrashHandler.report — иначе
     * пользователь увидит два краш-экрана подряд за одну и ту же причину).
     */
    fun reportEngineFailure(context: Context, step: String, t: Throwable) {
        val session = current ?: return
        if (session.reported.get()) return
        fail(context.applicationContext, session, step, t)
    }

    private fun fail(context: Context, session: Session, step: String, t: Throwable) {
        if (!session.reported.compareAndSet(false, true)) return // один отчёт на сессию
        val full = buildString {
            appendLine("ATRUM_TOR_SYNC СБОЙ на шаге: $step")
            appendLine("  chatId: ${session.chatId.take(8)}…")
            appendLine("  прошло с arm(): ${System.currentTimeMillis() - session.armedAtMs} мс")
            appendLine("  класс исключения: ${t::class.qualifiedName}")
            appendLine("  сообщение: ${t.message}")
            var cause = t.cause
            var depth = 0
            while (cause != null && depth < 8) {
                appendLine("  вызвано [$depth]: ${cause::class.qualifiedName}: ${cause.message}")
                cause = cause.cause
                depth++
            }
            appendLine("  TorManager.status: ${TorManager.status.value}")
            appendLine("  snowflakePort=${PluggableTransports.snowflakePort} obfs4Port=${PluggableTransports.obfs4Port}")
            appendLine("  журнал событий этой сессии:")
            session.events.forEach { appendLine("    $it") }
            appendLine("  полный стектрейс:")
            append(t.stackTraceToString())
        }
        println(full)
        runCatching { CrashHandler.report(context, "Tor-чат: синхронизация не по сценарию ($step)", t) }
        session.timeoutJob?.cancel()
        if (current === session) current = null
    }
}
