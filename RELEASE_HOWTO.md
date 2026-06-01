# Как собирать Release APK

Делается один раз: создаёшь keystore, прописываешь пароли в `keystore.properties`.
Потом любой `▶ Run` в режиме `release` или сборка APK через меню — даёт готовый
подписанный APK, который можно раздавать кому угодно.

---

## ПЕРВЫЙ РАЗ: создание keystore (5 минут)

### Шаг 1. Создай keystore через Android Studio

1. Меню → **Build → Generate Signed Bundle / APK…**
2. Выбери **APK** → **Next**
3. Под полем "Key store path" нажми **Create new…**
4. Заполни:
   - **Key store path:** `C:\Users\Dimash\Desktop\GithubChat\release.keystore`
     (или любой удобный путь — главное запомни!)
   - **Password / Confirm:** придумай пароль (запиши его, потерять = всё пропало)
   - **Alias:** `githubchat`
   - **Password / Confirm (для алиаса):** можно тот же пароль что и у keystore
   - **Validity:** `25` лет (или больше)
   - **First and Last Name:** твоё имя
   - **Organization Unit / Organization / City / State / Country Code:** что угодно
5. Нажми **OK** — keystore создан, путь подставлен в форму
6. На следующем экране выбери **release** в Build Variants → **Finish**

После этого Studio соберёт первый подписанный APK в
`app/release/GithubChat-release-1.0.apk` — можно его тестово установить, должен встать.

### Шаг 2. Создай keystore.properties

В корне проекта (`C:\Users\Dimash\Desktop\GithubChat`) **создай файл** `keystore.properties`
(можно скопировать из `keystore.properties.example`) и впиши:

```
storeFile=release.keystore
storePassword=ТВОЙ_ПАРОЛЬ
keyAlias=githubchat
keyPassword=ТВОЙ_ПАРОЛЬ
```

Если ты сохранил keystore в другую папку — укажи полный путь, например:
```
storeFile=C:/Users/Dimash/Desktop/GithubChat/release.keystore
```
(Слэши прямые `/`, не обратные `\`.)

### Шаг 3. Скажи Android Studio собирать release вариант

1. Меню → **Build → Select Build Variant…** (или View → Tool Windows → **Build Variants**)
2. Откроется панель Build Variants — в строке `:app` смени **debug** → **release**
3. Теперь когда жмёшь ▶ Run, ставится release-сборка с твоей подписью

---

## ПОВСЕДНЕВНАЯ РАБОТА (после правок кода)

### Чтобы поставить на свой телефон / эмулятор:
1. ▶ **Run** (Shift+F10)
2. Studio соберёт `GithubChat-release-1.0.apk`, подпишет твоим keystore, установит, запустит

### Чтобы получить APK для раздачи:
1. Меню → **Build → Build Bundle(s) / APK(s) → Build APK(s)**
2. Внизу появится уведомление "APK(s) generated successfully" → **locate**
3. Откроется папка `app/release/` с файлом `GithubChat-release-1.0.apk`
4. Этот файл можно слать в Telegram/Drive — у всех установится без проблем

### Перед каждой раздачей увеличивай версию

В `app/build.gradle.kts` найди:
```kotlin
versionCode = 1
versionName = "1.0"
```
И увеличивай: `versionCode = 2, versionName = "1.1"` и т.д. Иначе Android при
повторной установке у того же человека скажет "уже установлено" или вылетит конфликт.

---

## ВАЖНО: что нельзя терять

| Файл | Что будет если потерять |
|---|---|
| `release.keystore` | Не сможешь выпустить **обновление** существующего приложения (нужен тот же ключ). Придётся всем переустанавливать с нуля. |
| Пароли от keystore | То же самое. |

**Сделай резервную копию `release.keystore` в облако (Drive/iCloud) и запиши пароли
в менеджер паролей или текстовый файл в безопасном месте.**

---

## Если что-то сломалось

**"keystore.properties не найден":** проверь что файл лежит в корне проекта
(где `settings.gradle.kts`), а не в папке `app/`.

**"Failed to read key from store":** проверь пароль и alias в `keystore.properties`.
Alias должен совпадать с тем, что ты указал при создании (по умолчанию `githubchat`).

**"INSTALL_FAILED_UPDATE_INCOMPATIBLE":** на телефоне стоит старая версия
подписанная debug-ключом (или другим keystore). Удали приложение с телефона,
поставь заново.

**"Cannot find symbol ... signingConfigs":** Sync Gradle: File → Sync Project with Gradle Files.
