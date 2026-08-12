package com.atrum.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты слияния профилей (ProfileSync).
 *
 * Зачем именно здесь: слияние — самая баг-плодящая часть синхронизации. За её историю уже
 * ловились «ава пропадает у оффлайн-участника», «галочка гаснет на тик», «профиль теряется
 * в общем блобе», «на равном updatedAt побеждает устаревшая копия». Каждый раз это чинилось
 * разово и никак не закреплялось — регрессия ничем не ловилась. Эти тесты закрепляют
 * инварианты навсегда.
 *
 * Тестируются ЧИСТЫЕ функции ([ProfileSync.mergeParsedSlots], [ProfileSync.mergeReadOverKnown],
 * [ProfileSync.findPartner]) — без Android, сети и криптографии, поэтому работают в обычном
 * JVM-юните (JSONObject из android.jar в unit-тестах не работает, а Profile — чистый Kotlin).
 */
class ProfileSyncMergeTest {

    private fun profile(
        uid: String,
        name: String = "Имя-$uid",
        updatedAt: Long = 1_000L,
        avatar: String? = null,
        tag: String? = null,
        onlineTs: Long = 0L,
        typingTs: Long = 0L,
        recordingTs: Long = 0L,
        lastReadIndex: Int = 0,
        identityPubKey: String? = null,
        identitySig: String? = null,
        ephemeralPubKey: String? = null,
        ephemeralSig: String? = null,
        status: String? = null,
        deleted: Boolean = false
    ) = Profile(
        userId = uid,
        name = name,
        tag = tag,
        avatarBase64 = avatar,
        updatedAt = updatedAt,
        lastReadIndex = lastReadIndex,
        typingTs = typingTs,
        onlineTs = onlineTs,
        recordingTs = recordingTs,
        deleted = deleted,
        ephemeralPubKey = ephemeralPubKey,
        identityPubKey = identityPubKey,
        ephemeralSig = ephemeralSig,
        identitySig = identitySig,
        status = status
    )

    // ── mergeParsedSlots: тай-брейк ───────────────────────────────────────────

    /**
     * Ключевой инвариант (дефект найден при аудите синка): слоты приходят по created_at
     * УБЫВАЮЩЕ, поэтому при РАВНОМ updatedAt победить обязана запись из более НОВОГО
     * события — то есть из слота, который идёт в списке раньше. Раньше сравнение было
     * нестрогим (`>=`), и выигрывал более СТАРЫЙ слот.
     *
     * Равный updatedAt тут не экзотика: pushPresence переписывает профиль, не обновляя
     * updatedAt, поэтому мой профиль и его устаревшая копия в чужом слоте почти всегда
     * несут одно и то же значение.
     */
    @Test
    fun `на равном updatedAt побеждает запись из более свежего слота`() {
        val fresh = mapOf("A" to profile("A", updatedAt = 700, status = "на связи"))
        val stale = mapOf("A" to profile("A", updatedAt = 700, status = "старый статус"))

        val merged = ProfileSync.mergeParsedSlots(listOf(fresh, stale))  // порядок: новый → старый

        assertEquals("на связи", merged["A"]?.status)
    }

    @Test
    fun `больший updatedAt побеждает независимо от порядка слотов`() {
        val newer = mapOf("A" to profile("A", updatedAt = 900, status = "новое"))
        val older = mapOf("A" to profile("A", updatedAt = 100, status = "старое"))

        assertEquals("новое", ProfileSync.mergeParsedSlots(listOf(newer, older))["A"]?.status)
        assertEquals("новое", ProfileSync.mergeParsedSlots(listOf(older, newer))["A"]?.status)
    }

    // ── mergeParsedSlots: «липкость» полей ────────────────────────────────────

    /** Репорт «ава пропадает, если участник не в сети»: свежий слот без авы не гасит известную. */
    @Test
    fun `аватар липкий - свежая копия без авы не затирает известную`() {
        val freshNoAvatar = mapOf("X" to profile("X", updatedAt = 200, avatar = null))
        val oldWithAvatar = mapOf("X" to profile("X", updatedAt = 100, avatar = "ava"))

        val merged = ProfileSync.mergeParsedSlots(listOf(freshNoAvatar, oldWithAvatar))

        assertEquals("ava", merged["X"]?.avatarBase64)
    }

    @Test
    fun `имя липкое - пустое имя в свежей копии не затирает известное`() {
        val freshBlank = mapOf("X" to profile("X", name = "", updatedAt = 200))
        val oldNamed = mapOf("X" to profile("X", name = "Настоящее", updatedAt = 100))

        assertEquals("Настоящее", ProfileSync.mergeParsedSlots(listOf(freshBlank, oldNamed))["X"]?.name)
    }

    /** Репорт «галочка пропадает, когда я не в сети»: подписи тоже липкие. */
    @Test
    fun `identity и подписи липкие - slim-слот не гасит галочку`() {
        val slim = mapOf("X" to profile("X", updatedAt = 200))
        val full = mapOf(
            "X" to profile("X", updatedAt = 100, identityPubKey = "idk", identitySig = "isig", ephemeralPubKey = "eph", ephemeralSig = "esig")
        )

        val merged = ProfileSync.mergeParsedSlots(listOf(slim, full))["X"]!!

        assertEquals("idk", merged.identityPubKey)
        assertEquals("isig", merged.identitySig)
        assertEquals("eph", merged.ephemeralPubKey)
        assertEquals("esig", merged.ephemeralSig)
    }

    // ── mergeParsedSlots: presence и счётчики ─────────────────────────────────

    @Test
    fun `presence берётся максимумом по слотам`() {
        val a = mapOf("X" to profile("X", updatedAt = 100, onlineTs = 500, typingTs = 0, recordingTs = 700))
        val b = mapOf("X" to profile("X", updatedAt = 100, onlineTs = 100, typingTs = 900, recordingTs = 0))

        val merged = ProfileSync.mergeParsedSlots(listOf(a, b))["X"]!!

        assertEquals(500L, merged.onlineTs)
        assertEquals(900L, merged.typingTs)
        assertEquals(700L, merged.recordingTs)
    }

    /** Галочки прочтения не должны «ехать назад» — только монотонно вперёд. */
    @Test
    fun `lastReadIndex монотонен`() {
        val ahead = mapOf("X" to profile("X", updatedAt = 100, lastReadIndex = 42))
        val behind = mapOf("X" to profile("X", updatedAt = 900, lastReadIndex = 7))

        assertEquals(42, ProfileSync.mergeParsedSlots(listOf(behind, ahead))["X"]?.lastReadIndex)
    }

    // ── mergeParsedSlots: структурные случаи ──────────────────────────────────

    /**
     * Обратная совместимость (§17): старый клиент публикует ОДИН общий блоб со всеми
     * участниками — для слияния это просто слот с несколькими uid, никто не теряется.
     */
    @Test
    fun `старый общий блоб со всеми участниками читается как обычный слот`() {
        val legacyBlob = mapOf(
            "A" to profile("A", updatedAt = 100),
            "B" to profile("B", updatedAt = 100)
        )
        val mySlot = mapOf("C" to profile("C", updatedAt = 200))

        val merged = ProfileSync.mergeParsedSlots(listOf(mySlot, legacyBlob))

        assertEquals(setOf("A", "B", "C"), merged.keys)
    }

    @Test
    fun `пустой список слотов даёт пустой результат`() {
        assertTrue(ProfileSync.mergeParsedSlots(emptyList()).isEmpty())
    }

    @Test
    fun `пустые слоты не ломают слияние`() {
        val merged = ProfileSync.mergeParsedSlots(listOf(emptyMap(), mapOf("A" to profile("A")), emptyMap()))
        assertEquals(setOf("A"), merged.keys)
    }

    // ── mergeReadOverKnown ────────────────────────────────────────────────────

    /**
     * Флаки-чтение вернуло только меня — партнёр обязан остаться из кэша.
     * Иначе read-modify-write затёр бы его профиль (ровно то, ради чего кэш и заведён).
     */
    @Test
    fun `известный партнёр не теряется при чтении без него`() {
        val known = mapOf("PARTNER" to profile("PARTNER", avatar = "ava"))
        val read = mapOf("ME" to profile("ME"))

        val merged = ProfileSync.mergeReadOverKnown(known, read)

        assertEquals(setOf("PARTNER", "ME"), merged.keys)
        assertEquals("ava", merged["PARTNER"]?.avatarBase64)
    }

    /** При РАВНОМ updatedAt свежее чтение важнее кэша: presence обязан обновляться. */
    @Test
    fun `на равном updatedAt свежее чтение побеждает кэш`() {
        val known = mapOf("X" to profile("X", updatedAt = 500, status = "из кэша"))
        val read = mapOf("X" to profile("X", updatedAt = 500, status = "из сети"))

        assertEquals("из сети", ProfileSync.mergeReadOverKnown(known, read)["X"]?.status)
    }

    /** Presence всегда из свежего чтения — иначе «в сети» залипал бы из кэша. */
    @Test
    fun `presence берётся из свежего чтения даже если оно старее по updatedAt`() {
        val known = mapOf("X" to profile("X", updatedAt = 900, onlineTs = 999))
        val read = mapOf("X" to profile("X", updatedAt = 100, onlineTs = 0))

        assertEquals(0L, ProfileSync.mergeReadOverKnown(known, read)["X"]?.onlineTs)
    }

    @Test
    fun `аватар липкий и при слиянии с кэшем`() {
        val known = mapOf("X" to profile("X", updatedAt = 100, avatar = "ava"))
        val read = mapOf("X" to profile("X", updatedAt = 900, avatar = null))

        assertEquals("ava", ProfileSync.mergeReadOverKnown(known, read)["X"]?.avatarBase64)
    }

    @Test
    fun `пустой кэш - результат равен прочитанному`() {
        val read = mapOf("X" to profile("X"))
        assertEquals(setOf("X"), ProfileSync.mergeReadOverKnown(null, read).keys)
    }

    // ── findPartner ───────────────────────────────────────────────────────────

    @Test
    fun `findPartner не возвращает меня самого`() {
        val profiles = mapOf("ME" to profile("ME", name = "Я"))
        assertNull(ProfileSync.findPartner(profiles, myUserId = "ME", myName = "Я"))
    }

    /** «Клон меня» после сброса аккаунта (моё имя, чужой userId) не должен считаться партнёром. */
    @Test
    fun `findPartner отбрасывает клона с моим именем`() {
        val profiles = mapOf(
            "ME" to profile("ME", name = "Я"),
            "OLD_ME" to profile("OLD_ME", name = "Я", updatedAt = 9_000),
            "PARTNER" to profile("PARTNER", name = "Собеседник", updatedAt = 1_000)
        )

        assertEquals("PARTNER", ProfileSync.findPartner(profiles, "ME", "Я")?.userId)
    }

    /** Если КРОМЕ клонов никого нет — это легитимный тёзка, его и возвращаем. */
    @Test
    fun `findPartner возвращает тёзку если других нет`() {
        val profiles = mapOf(
            "ME" to profile("ME", name = "Я"),
            "PARTNER" to profile("PARTNER", name = "Я")
        )

        assertEquals("PARTNER", ProfileSync.findPartner(profiles, "ME", "Я")?.userId)
    }

    @Test
    fun `findPartner выбирает самого свежего из нескольких`() {
        val profiles = mapOf(
            "ME" to profile("ME", name = "Я"),
            "P1" to profile("P1", name = "Первый", updatedAt = 100),
            "P2" to profile("P2", name = "Второй", updatedAt = 900)
        )

        assertEquals("P2", ProfileSync.findPartner(profiles, "ME", "Я")?.userId)
    }
}
