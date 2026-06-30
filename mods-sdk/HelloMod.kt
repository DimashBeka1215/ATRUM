package com.example.atrummod

import com.atrum.chat.mods.AtrumMod
import com.atrum.chat.mods.ModHost

/**
 * Пример мода Atrum.
 *
 * Компилируется против ИНТЕРФЕЙСОВ из app/.../mods/ModApi.kt (только compile-only:
 * в рантайме реальные классы даёт хост-приложение через родительский classloader).
 * Затем компилируется в .dex (d8), подписывается издательским ключом и попадает в каталог.
 *
 * ВАЖНО: мод видит ТОЛЬКО ModHost. Доступа к ключам/паролям/транспорту чата нет.
 */
class HelloMod : AtrumMod {

    override val id = "hello"

    override fun onLoad(host: ModHost) {
        host.log("HelloMod loaded, appVersion=${host.appVersionCode}")
        host.toast("Привет из мода!")
        host.registerSettingsItem(
            title = "Hello mod",
            summary = "Тестовый мод-пример"
        ) {
            host.toast("Пункт мода нажат")
        }
    }

    override fun onUnload() {
        // освободить ресурсы, если были
    }
}
