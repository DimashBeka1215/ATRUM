# Atrum Chat

Сквозно-зашифрованный мессенджер для двоих, который не использует собственные серверы.
Сообщения ходят через **публичные Nostr-реле поверх встроенного Tor** и шифруются
**прямо на устройстве** — в сеть уходит только зашифрованный текст. Нет аккаунтов,
нет номеров телефона, нет центрального сервера, который можно изъять или заставить
выдать переписку.

> ⚠️ **Статус:** активная разработка (текущая версия `3.20.9-beta169`). Приложение
> экспериментальное, протокол и формат хранения ещё меняются. Не полагайтесь на него
> там, где цена ошибки — жизнь или свобода.

---

## Идея

Обычный мессенджер хранит переписку на сервере компании. Atrum серверов не имеет вовсе.
Каждый чат — это пара ключей, известная только двум собеседникам. Зашифрованные
сообщения публикуются как события в распределённую сеть Nostr и забираются второй
стороной. Реле видят лишь поток шифртекста; содержимое не может прочитать ни реле, ни
провайдер, ни сам Atrum.

Весь трафик в этом режиме идёт СТРОГО через **встроенный Tor** (никаких отдельных приложений
ставить не надо), что скрывает IP и факт использования Nostr. Политика «Tor or nothing»
исключает утечку IP-адреса даже при нестабильном соединении.

## Как это работает

```
Ваш телефон  ──шифр──►  Nostr-реле (через Tor)  ──►  телефон собеседника  ──дешифр──►
```

1. **Создание чата.** Генерируется приглашение (`InviteCodec`) — короткий код или QR.
   Собеседник вводит код / сканирует QR и попадает в тот же чат. Никакой регистрации.
2. **Шифрование.** Каждое сообщение шифруется на устройстве (**AES-256-GCM**, ключ
   выводится через **Argon2**). Ключ привязан к секрету чата, которого нет ни на одном
   сервере.
3. **Транспорт.** Шифртекст публикуется как **Nostr-событие (NIP-01)** сразу на несколько
   реле (fan-out), читается объединением со всех (union read). Чем больше реле — тем
   устойчивее доставка к блокировкам и отвалам.
4. **Доставка.** Второй телефон опрашивает реле (единый цикл `SyncEngine`), забирает
   новые события, расшифровывает и показывает. Локальный стор (`NostrMessageStore`)
   хранит историю на устройстве.

## Что умеет

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
- **Анонимность транспорта:** весь трафик через встроенный Tor (kmp-tor). СТРОГОЕ
  использование Tor для защищённых чатов («Tor or nothing») предотвращает утечки IP.
  Поддержка мостов / pluggable transports (IPtProxy) для обхода блокировок Tor.
- **Защита от тайминг-анализа:** рандомные задержки (jitter) при отправке и требование
  кворума подтверждений от нескольких реле (3/5+).
- **Подписанный список реле:** обновляемый перечень реле (`RelayListStore`) подписан
  ключом издателя по **BIP-340 Schnorr** и проверяется офлайн. Список только **дополняет**
  встроенный набор (additive) и защищён от отката версии — нельзя подсунуть «свои» реле.
- **Локальное хранилище:** пароли чатов и ключи — в `EncryptedSharedPreferences`.

Чего Atrum **не** скрывает: на уровне самого Nostr видно, что какое-то событие
опубликовано, — это видят реле, но без связи с вашим IP (Tor) и без открытого содержимого.

## Технологии

- **Язык/платформа:** Kotlin, Android (minSdk 24, targetSdk 34, compileSdk 35)
- **Сеть:** Nostr (NIP-01) + OkHttp WebSocket, встроенный Tor (`io.matthewnelson.kmp-tor`),
  IPtProxy для мостов
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
├── TorManager.kt              встроенный Tor
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

Готовый APK — в разделе [Releases](https://source.atrum.chat/mods/releases).

---

# Atrum Chat (English)

An end-to-end encrypted messenger for two people that runs **without any servers of its own**.
Messages travel over **public Nostr relays through embedded Tor** and are encrypted
**on-device** — only ciphertext ever leaves your phone. No accounts, no phone numbers,
no central server that can be seized or compelled to hand over your chats.

> ⚠️ **Status:** under active development (`3.20.9-beta169`). Experimental — the protocol
> and storage format are still changing. Don't rely on it where a mistake costs your
> life or freedom.

## The idea

Conventional messengers keep your history on a company server. Atrum has none. Each chat is
a key pair known only to the two participants. Encrypted messages are published as events to
the decentralized Nostr network and pulled by the other side. Relays only see a stream of
ciphertext — neither the relay, the ISP, nor Atrum itself can read it. All traffic in this mode goes STRICTLY through **built-in Tor** (no separate app needed),
hiding your IP; the "Tor or nothing" policy prevents IP leaks even on unstable networks.

## How it works

1. **Create a chat** — an invite code or QR is generated (`InviteCodec`); the other person
   enters/scans it and joins the same chat. No sign-up.
2. **Encrypt** — every message is encrypted on-device (**AES-256-GCM**, key derived via
   **Argon2**) against a chat secret that exists on no server.
3. **Transport** — ciphertext is published as a **Nostr event (NIP-01)** to several relays
   at once (fan-out) and read back as a union of all of them.
4. **Deliver** — the peer polls the relays (single `SyncEngine` loop), decrypts, and renders;
   history is kept locally (`NostrMessageStore`).

## Features

Text, photos (albums/collages + viewer), **voice messages** with noise reduction, animated
WebM stickers, reactions, swipe-to-reply, edit & delete, online / typing / read indicators,
chat wallpapers with a "glass" look, app lock (PIN / biometrics), light & dark themes,
Russian & English, background push notifications, experimental Bluetooth LE offline exchange.

## Security model

- **E2E encryption:** AES-256-GCM with an Argon2-derived key; relays see ciphertext only.
- **Forward secrecy:** the ephemeral session key lives in memory only and is wiped when the
  chat closes — a later device compromise doesn't reveal past messages.
- **Transport anonymity:** all traffic via embedded Tor (kmp-tor), "Tor or nothing"
  policy for protected chats to prevent IP leaks, bridges / pluggable transports (IPtProxy)
  when Tor is blocked.
- **Anti-timing analysis:** random network jitter (50-300ms) and confirmation quorum (3/5+ relays)
  to obfuscate traffic patterns.
- **Signed relay list:** the updatable relay list (`RelayListStore`) is signed with the
  publisher key (**BIP-340 Schnorr**), verified offline, **additive only** over the built-in
  set, and rollback-protected — no one can swap in their own relays.
- **Local storage:** chat passwords and keys live in `EncryptedSharedPreferences`.

## Tech stack

Kotlin / Android (minSdk 24, targetSdk 34, compileSdk 35) · Nostr (NIP-01) + OkHttp
WebSocket · embedded Tor (`kmp-tor`) + IPtProxy · AES-GCM / Argon2 / BIP-340 Schnorr
(BouncyCastle) · Room + EncryptedSharedPreferences · Android Views, Material, CameraX,
Lottie, ZXing, uCrop.

## Build

```bash
./gradlew assembleRelease    # APK in app/build/outputs/apk/release/
```

Requires the Android SDK (compileSdk 35) and JDK 17.

## Download

Grab the latest APK from [Releases](https://source.atrum.chat/mods/releases).
