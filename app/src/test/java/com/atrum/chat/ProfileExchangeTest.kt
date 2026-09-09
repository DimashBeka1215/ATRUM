package com.atrum.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты ЧИСТОЙ логики ответной стороны обмена профилями (см. ProfileExchange).
 *
 * Проверяются ровно те две функции, от которых зависит, состоится ли обмен: «покрывает ли мой
 * слот тех, кого я вижу» и «несёт ли слот мой актуальный профиль». Сеть, Android и Argon2 сюда
 * не входят — именно поэтому эти условия и вынесены отдельными функциями.
 */
class ProfileExchangeTest {

    private fun p(
        uid: String,
        name: String,
        avatar: String? = null,
        updatedAt: Long = 1_000L,
        deleted: Boolean = false,
        left: Boolean = false
    ) = Profile(
        userId = uid,
        name = name,
        avatarBase64 = avatar,
        updatedAt = updatedAt,
        deleted = deleted,
        left = left
    )

    // ── mySlotCoversPeers ────────────────────────────────────────────────────

    @Test
    fun `пустой список участников подтверждать нечего`() {
        assertTrue(ProfileExchange.mySlotCoversPeers(null, emptyMap()))
    }

    @Test
    fun `моего слота нет на реле — не покрывает`() {
        val peers = mapOf("b" to p("b", "Боб"))
        assertFalse(ProfileExchange.mySlotCoversPeers(null, peers))
    }

    @Test
    fun `собеседника нет в моём слоте — не покрывает`() {
        val mine = mapOf("a" to p("a", "Аня"))
        val peers = mapOf("b" to p("b", "Боб"))
        assertFalse(ProfileExchange.mySlotCoversPeers(mine, peers))
    }

    @Test
    fun `актуальная копия собеседника — покрывает`() {
        val mine = mapOf("a" to p("a", "Аня"), "b" to p("b", "Боб", "AV"))
        val peers = mapOf("b" to p("b", "Боб", "AV"))
        assertTrue(ProfileExchange.mySlotCoversPeers(mine, peers))
    }

    @Test
    fun `устаревший ник в моей копии — не покрывает`() {
        val mine = mapOf("b" to p("b", "Боб"))
        val peers = mapOf("b" to p("b", "Роберт"))
        assertFalse(ProfileExchange.mySlotCoversPeers(mine, peers))
    }

    @Test
    fun `собеседник сменил аватар — не покрывает`() {
        val mine = mapOf("b" to p("b", "Боб", "OLD"))
        val peers = mapOf("b" to p("b", "Боб", "NEW"))
        assertFalse(ProfileExchange.mySlotCoversPeers(mine, peers))
    }

    @Test
    fun `у собеседника появился аватар там где его не было — не покрывает`() {
        val mine = mapOf("b" to p("b", "Боб", null))
        val peers = mapOf("b" to p("b", "Боб", "AV"))
        assertFalse(ProfileExchange.mySlotCoversPeers(mine, peers))
    }

    @Test
    fun `моя копия старее по updatedAt — не покрывает`() {
        val mine = mapOf("b" to p("b", "Боб", "AV", updatedAt = 100L))
        val peers = mapOf("b" to p("b", "Боб", "AV", updatedAt = 500L))
        assertFalse(ProfileExchange.mySlotCoversPeers(mine, peers))
    }

    @Test
    fun `моя копия свежее по updatedAt при тех же полях — покрывает`() {
        val mine = mapOf("b" to p("b", "Боб", "AV", updatedAt = 900L))
        val peers = mapOf("b" to p("b", "Боб", "AV", updatedAt = 500L))
        assertTrue(ProfileExchange.mySlotCoversPeers(mine, peers))
    }

    @Test
    fun `в беседе непокрытым может быть один из нескольких`() {
        val mine = mapOf("b" to p("b", "Боб"), "c" to p("c", "Вика"))
        val peers = mapOf("b" to p("b", "Боб"), "c" to p("c", "Вика"), "d" to p("d", "Гена"))
        assertFalse(ProfileExchange.mySlotCoversPeers(mine, peers))
    }

    // ── slotCarriesMyProfile ─────────────────────────────────────────────────

    @Test
    fun `меня нет в слоте — профиль не подтверждён`() {
        val slot = mapOf("b" to p("b", "Боб"))
        assertFalse(ProfileExchange.slotCarriesMyProfile(slot, "a", "Аня", null))
    }

    @Test
    fun `мой актуальный профиль в слоте — подтверждён`() {
        val slot = mapOf("a" to p("a", "Аня", "AV"), "b" to p("b", "Боб"))
        assertTrue(ProfileExchange.slotCarriesMyProfile(slot, "a", "Аня", "AV"))
    }

    @Test
    fun `в слоте мой старый ник — не подтверждён`() {
        val slot = mapOf("a" to p("a", "Анна"))
        assertFalse(ProfileExchange.slotCarriesMyProfile(slot, "a", "Аня", null))
    }

    @Test
    fun `в слоте я без аватара а он у меня есть — не подтверждён`() {
        val slot = mapOf("a" to p("a", "Аня", null))
        assertFalse(ProfileExchange.slotCarriesMyProfile(slot, "a", "Аня", "AV"))
    }

    @Test
    fun `пустое имя не сравнивается`() {
        val slot = mapOf("a" to p("a", "что угодно"))
        assertTrue(ProfileExchange.slotCarriesMyProfile(slot, "a", "", null))
    }

    @Test
    fun `пустая строка и null аватара — одно и то же`() {
        val slot = mapOf("a" to p("a", "Аня", ""))
        assertTrue(ProfileExchange.slotCarriesMyProfile(slot, "a", "Аня", null))
    }
}
