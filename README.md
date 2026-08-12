# Atrum Chat

[![Telegram: канал](https://img.shields.io/badge/Telegram-канал-26A5E4?logo=telegram&logoColor=white)](https://t.me/Atrum_Chat)
[![Telegram: чат сообщества](https://img.shields.io/badge/Telegram-чат%20сообщества-26A5E4?logo=telegram&logoColor=white)](https://t.me/+4hhc8PwwNf03ZmMy)
[![GitHub Releases](https://img.shields.io/badge/GitHub-Releases-181717?logo=github&logoColor=white)](https://github.com/DimashBeka1215/ATRUM/releases)
[![Поддержать](https://img.shields.io/badge/❤-Поддержать-9D4EDD)](#поддержать--support)

Сквозно-зашифрованный мессенджер для двоих и для групп, который не использует
собственные серверы. Сообщения ходят через **публичные Nostr-реле** и шифруются
**прямо на устройстве** — в сеть уходит только зашифрованный текст.
Нет аккаунтов, нет номеров телефона, нет центрального сервера, который можно изъять
или заставить выдать переписку.

> ⚠️ **Статус:** активная разработка. Приложение экспериментальное, протокол и формат
> хранения ещё меняются. Текущую версию сборки смотрите в приложении
> (Настройки → О приложении) или в разделе [Releases](https://github.com/DimashBeka1215/ATRUM/releases).
> Не полагайтесь на него там, где цена ошибки — жизнь или свобода.

> ⛔ **Скачивайте Atrum только из официальных источников** — [Telegram-канал](https://t.me/Atrum_Chat)
> или [GitHub Releases](https://github.com/DimashBeka1215/ATRUM/releases) этого репозитория.
> Код проекта открыт: это плюс для проверки шифрования, но это же значит, что кто угодно
> может собрать модифицированную версию и вырезать из неё любую защиту (шифрование,
> проверку подписи реле) — внешне такая сборка не будет отличаться от настоящей. Мы не можем
> гарантировать безопасность сборок из сторонних источников.

---

## Ссылки

| | |
|---|---|
| 💻 [Исходный код и релизы](https://github.com/DimashBeka1215/ATRUM) | Этот репозиторий: код, история изменений, [Releases](https://github.com/DimashBeka1215/ATRUM/releases) с готовыми APK |
| 📣 [Telegram-канал](https://t.me/Atrum_Chat) | Новости и обновления — анонсы новых версий |
| 💬 [Чат сообщества в Telegram](https://t.me/+4hhc8PwwNf03ZmMy) | Общение, вопросы и поддержка от сообщества |
| ❤️ [Поддержать проект](#поддержать--support) | DonationAlerts, Boosty, Buy Me a Coffee |

Спасибо, что пользуетесь Atrum — следите за обновлениями и делитесь фидбеком!

---

## Идея

Обычный мессенджер хранит переписку на сервере компании. Atrum серверов не имеет вовсе.
Каждый чат (1:1 или группа) — это секрет, известный только его участникам. Зашифрованные
сообщения публикуются как события в распределённую сеть Nostr и забираются остальными
участниками. Реле видят лишь поток шифртекста; содержимое не может прочитать ни реле, ни
провайдер, ни сам Atrum.

## Что такое реле (Nostr relays)?

Atrum не хранит переписку сам и не арендует для этого сервер. Вместо этого зашифрованные
сообщения публикуются как события в открытый протокол **Nostr** — а «реле» (relay) — это
обычный публичный сервер, который просто принимает такие события и отдаёт их обратно
по запросу, как почтовый ящик общего пользования. Реле не подчиняются Atrum, не
принадлежат нам, и таких серверов в мире много — независимых, чужих, разных владельцев.

Ключевые свойства:

- **Реле не видит содержимое.** Оно получает и хранит только шифртекст — расшифровать
  его может лишь тот, у кого есть секрет конкретного чата.
- **Реле легко заменить.** Чат не привязан к одному серверу: сообщение публикуется сразу
  на несколько реле (fan-out) и читается объединением со всех (union read). Если одно
  реле упадёт, забанит вас или пропадёт — чат продолжит работать через остальные.
- **Список реле подписан и обновляем.** Встроенный набор реле можно дополнять новым,
  подписанным издателем списком (`RelayListStore`, BIP-340 Schnorr) — но только
  дополнять, никогда не заменять исподтишка (защита от отката/подмены).

Иными словами: реле — это просто «труба» для доставки шифртекста, а не хранилище данных
в смысле облака компании. Заменить любой сервер Nostr — не проблема; заменить
шифрование — невозможно, потому что оно никак не зависит от того, какое конкретно реле
использовано.

## Как это работает

```
Ваш телефон  ──шифр──►  Nostr-реле  ──►  телефон собеседника  ──дешифр──►
```

1. **Создание чата.** Генерируется приглашение (`InviteCodec`) — короткий код или QR.
   Собеседник вводит код / сканирует QR и попадает в тот же чат (или группу). Никакой
   регистрации.
2. **Шифрование.** Каждое сообщение шифруется на устройстве (**AES-256-GCM**, ключ
   выводится через **Argon2**, для активных чатов — сессионный ключ с forward secrecy).
   Ключ привязан к секрету чата, которого нет ни на одном сервере.
3. **Транспорт.** Шифртекст публикуется как **Nostr-событие (NIP-01)** сразу на несколько
   реле (fan-out), читается объединением со всех (union read). Чем больше реле — тем
   устойчивее доставка к блокировкам и отвалам.
4. **Доставка.** Остальные участники опрашивают реле (единый цикл `SyncEngine`), забирают
   новые события, расшифровывают и показывают. Локальный стор (`NostrMessageStore`)
   хранит историю на устройстве.

## Что умеет

- Личные чаты 1:1 и **групповые беседы** — децентрализованный список участников
  (собирается из самоопубликованных профилей, а не с одного «сервера правды»)
- Текст, фото (с альбомами/коллажами и просмотрщиком), **голосовые** с шумоподавлением
- Анимированные WebM-стикеры
- Реакции, ответы (свайп-to-reply), редактирование и удаление сообщений
- Статусы «онлайн», «печатает», галочки прочтения
- Обои чата + «glass»-оформление поверх фото
- Блокировка приложения по PIN / биометрии
- Светлая и тёмная тема, русский и английский языки
- Push-уведомления о новых сообщениях (фоновый сервис)
- Оффлайн-обмен по Bluetooth LE (экспериментально)

## Модель безопасности

- **Сквозное шифрование:** AES-256-GCM, ключ через Argon2. Реле получают только шифртекст.
- **Forward secrecy:** сессионный эфемерный ключ живёт только в памяти и стирается при
  закрытии чата — компрометация устройства позже не раскрывает прошлую переписку.
- **Защита от тайминг-анализа:** рандомные задержки (jitter) при отправке и требование
  кворума подтверждений от нескольких реле (3/5+).
- **Подписанный список реле:** обновляемый перечень реле (`RelayListStore`) подписан
  ключом издателя по **BIP-340 Schnorr** и проверяется офлайн. Список только **дополняет**
  встроенный набор (additive) и защищён от отката версии — нельзя подсунуть «свои» реле.
- **Локальное хранилище:** пароли чатов и ключи — в `EncryptedSharedPreferences`.
- **Открытый исходный код:** любой может проверить, что шифрование реализовано так, как
  описано выше — но см. предупреждение выше про сборки из неофициальных источников.

Чего Atrum **не** скрывает: на уровне самого Nostr видно, что какое-то событие
опубликовано, — это видят реле, но без открытого содержимого.

## Технологии

- **Язык/платформа:** Kotlin, Android (minSdk 24, targetSdk 34, compileSdk 35)
- **Сеть:** Nostr (NIP-01) + OkHttp WebSocket
- **Крипто:** AES-GCM, Argon2 и BIP-340 Schnorr (BouncyCastle)
- **Хранилище:** Room DB + EncryptedSharedPreferences
- **UI:** Android Views (XML), Material, CameraX, Lottie, ZXing (QR), uCrop (аватары)

## Структура проекта

```
app/src/main/java/com/atrum/chat/
├── SyncEngine.kt              единый цикл синхронизации
├── transport/                 ChatTransport (интерфейс) + NostrTransport / LocalTransport
├── nostr/                     NostrEvent, NostrRelayPool, Schnorr (подписи)
├── RelayListStore.kt          подписанный обновляемый список реле
├── CryptoHelper.kt            шифрование/дешифрование
├── ProfileSync.kt             синхронизация профилей
├── ChatActivity.kt            экран чата
└── ...                        медиа, стикеры, голос, блокировка, настройки
```

Подробные правила архитектуры, синхронизации и оформления — в [`CLAUDE.md`](CLAUDE.md)
и [`DESIGN.md`](DESIGN.md).

## Сборка

```bash
./gradlew assembleRelease    # APK в app/build/outputs/apk/release/
```

Нужен Android SDK (compileSdk 35) и JDK 17.

## Скачать

Только из официальных источников:

- [Telegram-канал](https://t.me/Atrum_Chat) — анонсы новых версий
- [GitHub Releases](https://github.com/DimashBeka1215/ATRUM/releases) — готовый APK

## Поддержать / Support

Проект бесплатный, без рекламы и без сбора данных. Если хотите поддержать разработку:

- [DonationAlerts](https://www.donationalerts.com/r/dimash_beka1215)
- [Boosty](https://boosty.to/sky_pill)
- [Buy Me a Coffee](https://buymeacoffee.com/atrum)

---

# Atrum Chat (English)

An end-to-end encrypted messenger for private and group chats that runs **without any
servers of its own**. Messages travel over **public Nostr relays** and are encrypted
**on-device** — only ciphertext ever leaves your phone. No accounts, no
phone numbers, no central server that can be seized or compelled to hand over your chats.

> ⚠️ **Status:** under active development. Experimental — the protocol and storage format
> are still changing. Check the exact build version in the app (Settings → About) or in
> [Releases](https://github.com/DimashBeka1215/ATRUM/releases). Don't rely on it where a
> mistake costs your life or freedom.

> ⛔ **Only download Atrum from official sources** — the [Telegram channel](https://t.me/Atrum_Chat)
> or [GitHub Releases](https://github.com/DimashBeka1215/ATRUM/releases) of this repository.
> The source code is open, which is great for verifying the crypto — but it also means
> anyone can rebuild a modified version and strip out any protection (encryption,
> relay-list signature checks). Such a build would look identical from the outside. We
> cannot vouch for the safety of builds from third-party sources.

## Links

| | |
|---|---|
| 💻 [Source code & releases](https://github.com/DimashBeka1215/ATRUM) | This repository: code, history, [Releases](https://github.com/DimashBeka1215/ATRUM/releases) with ready-to-install APKs |
| 📣 [Telegram channel](https://t.me/Atrum_Chat) | News and updates — new version announcements |
| 💬 [Community chat on Telegram](https://t.me/+4hhc8PwwNf03ZmMy) | Discussion, questions and community support |
| ❤️ [Support the project](#support) | DonationAlerts, Boosty, Buy Me a Coffee |

## The idea

Conventional messengers keep your history on a company server. Atrum has none. Each chat
(1:1 or group) is a secret known only to its participants. Encrypted messages are
published as events to the decentralized Nostr network and pulled by the rest of the
participants. Relays only see a stream of ciphertext — neither the relay, the ISP, nor
Atrum itself can read it.

## What are relays?

Atrum doesn't store your chats itself and doesn't rent a server to do it. Instead,
encrypted messages are published as events on the open **Nostr** protocol — a "relay" is
just an ordinary public server that accepts these events and hands them back on request,
like a shared mailbox. Relays aren't operated by Atrum, aren't owned by us, and there are
many independent ones run by different people worldwide.

Key properties:

- **A relay never sees the content.** It only receives and stores ciphertext — only
  someone holding that specific chat's secret can decrypt it.
- **A relay is easy to replace.** A chat isn't tied to one server: every message is
  published to several relays at once (fan-out) and read back as a union of all of them.
  If one relay goes down, bans you, or disappears, the chat keeps working through the
  rest.
- **The relay list is signed and updatable.** The built-in relay set can be extended with
  a new, publisher-signed list (`RelayListStore`, BIP-340 Schnorr) — but only extended,
  never silently replaced (protection against downgrade/substitution).

In short: a relay is just a "pipe" for delivering ciphertext, not a company-cloud style
data store. Swapping out any Nostr server is trivial; breaking the encryption is not,
because it doesn't depend in any way on which specific relay is used.

## How it works

1. **Create a chat** — an invite code or QR is generated (`InviteCodec`); the other
   person enters/scans it and joins the same chat or group. No sign-up.
2. **Encrypt** — every message is encrypted on-device (**AES-256-GCM**, key derived via
   **Argon2**; active chats additionally use a forward-secret session key) against a
   chat secret that exists on no server.
3. **Transport** — ciphertext is published as a **Nostr event (NIP-01)** to several relays
   at once (fan-out) and read back as a union of all of them.
4. **Deliver** — participants poll the relays (single `SyncEngine` loop), decrypt, and
   render; history is kept locally (`NostrMessageStore`).

## Features

Private 1:1 chats and **group conversations** with a decentralized member roster (built
from self-published profiles, not a single "source of truth" server), text, photos
(albums/collages + viewer), **voice messages** with noise reduction, animated WebM
stickers, reactions, swipe-to-reply, edit & delete, online / typing / read indicators,
chat wallpapers with a "glass" look, app lock (PIN / biometrics), light & dark themes,
Russian & English, background push notifications, experimental Bluetooth LE offline
exchange.

## Security model

- **E2E encryption:** AES-256-GCM with an Argon2-derived key; relays see ciphertext only.
- **Forward secrecy:** the ephemeral session key lives in memory only and is wiped when
  the chat closes — a later device compromise doesn't reveal past messages.
- **Anti-timing analysis:** random network jitter (50-300ms) and confirmation quorum
  (3/5+ relays) to obfuscate traffic patterns.
- **Signed relay list:** the updatable relay list (`RelayListStore`) is signed with the
  publisher key (**BIP-340 Schnorr**), verified offline, **additive only** over the
  built-in set, and rollback-protected — no one can swap in their own relays.
- **Local storage:** chat passwords and keys live in `EncryptedSharedPreferences`.
- **Open source:** anyone can verify the encryption is implemented as described above —
  but see the download warning above about unofficial builds.

## Tech stack

Kotlin / Android (minSdk 24, targetSdk 34, compileSdk 35) · Nostr (NIP-01) + OkHttp
WebSocket · AES-GCM / Argon2 / BIP-340 Schnorr
(BouncyCastle) · Room + EncryptedSharedPreferences · Android Views, Material, CameraX,
Lottie, ZXing, uCrop.

## Build

```bash
./gradlew assembleRelease    # APK in app/build/outputs/apk/release/
```

Requires the Android SDK (compileSdk 35) and JDK 17.

## Download

Official sources only:

- [Telegram channel](https://t.me/Atrum_Chat) — release announcements
- [GitHub Releases](https://github.com/DimashBeka1215/ATRUM/releases) — the APK itself

## Support

Atrum is free, ad-free, and doesn't collect your data. If you'd like to support development:

- [DonationAlerts](https://www.donationalerts.com/r/dimash_beka1215)
- [Boosty](https://boosty.to/sky_pill)
- [Buy Me a Coffee](https://buymeacoffee.com/atrum)
