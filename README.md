# Atrum Chat

Зашифрованный мессенджер для двоих через GitHub Gist. Сообщения шифруются AES-256-GCM прямо на устройстве — в Gist уходит только зашифрованный текст. Никаких серверов, никаких номеров телефона.

## Как это работает

Каждый чат — это приватный GitHub Gist. Сообщения шифруются на устройстве до отправки и расшифровываются только у получателя. Приложение использует GitHub как транспорт, не как хранилище данных.

- Шифрование: AES-256-GCM + forward secrecy (сессионный ключ только в памяти)
- Авторизация: GitHub OAuth без пароля
- Без серверов, без регистрации, без номера телефона
- Светлая и тёмная тема

## Скачать

Актуальный APK — в разделе [Releases](https://github.com/DimashBeka1215/ATRUM/releases).

---

# Atrum Chat (English)

An encrypted two-person messenger built on GitHub Gist. Messages are encrypted with AES-256-GCM on-device — only ciphertext reaches Gist. No servers, no phone numbers.

## How it works

Each chat is a private GitHub Gist. Messages are encrypted before sending and decrypted only by the recipient. GitHub is used purely as a transport, not as a data store.

- Encryption: AES-256-GCM + forward secrecy (session key lives in memory only)
- Auth: GitHub OAuth, no password required
- No servers, no sign-up, no phone number
- Light and dark theme

## Download

Latest APK is in the [Releases](https://github.com/DimashBeka1215/ATRUM/releases) section.
