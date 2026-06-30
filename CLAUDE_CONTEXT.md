# Context for Claude: Atrum Chat Development

## Project Overview
**Atrum Chat** is a secure, decentralized messaging application built with a focus on privacy and cryptographic memory safety. It uses a transport-agnostic architecture (Nostr, Bluetooth, etc.) and a custom V3 invite format.

## Current Status
- **UI Compliance:** Completed replacement of forbidden Unicode symbols (★, ✕, ↪) with vector drawables (`ic_sparkle`, `ic_close`, `msg_preview_reply_format`).
- **Memory Safety Foundation:** `InviteCodec.kt` has been refactored to use `CharArray` and manual wiping (`.fill(0)`) for sensitive data (PINs, passwords).
- **Core Activities:** `ChatActivity` and `JoinChatActivity` are partially updated to handle non-string password buffers.

## Tasks to be Completed (TODO)

### 1. Memory Safety Propagation
- [ ] **ChatActivity.kt & JoinChatActivity.kt:** Complete the refactoring to use `CharArray` for all password-related operations to avoid `String` pool persistence.
- [ ] **BleManager.kt:** Convert `rxBuffer` from `StringBuilder` to a wipeable byte-based or char-based buffer. Implement `finally { buffer.fill(0) }` blocks for all transmission frames.
- [ ] **NostrTransport.kt:** Refactor the transport layer to avoid storing `chatPassword` as a long-lived immutable `String`.

### 2. Cryptographic Audit
- [ ] Verify that Argon2id and AES-256-GCM implementations in `CryptoHelper.kt` do not leak intermediate keys into the `String` pool.
- [ ] Ensure `EncryptedSharedPreferences` is used correctly for all long-term transport secrets.

### 3. Messaging Features
- [ ] Finalize the "Favorites" chat logic (self-chat).
- [ ] Debug the identity verification flow (`verifyPartnerIdentity`) in `ChatActivity.kt`.

### 4. Technical Debt
- [ ] Standardize error handling in `doSyncProfilesOnce` to prevent silent failures during handshake.
- [ ] Clean up redundant visibility toggles in `applyPartnerToHeader`.

## Architectural Notes
- **Transport:** The app uses `transportToken` and `chatPassword` for relay authentication and message decryption.
- **Identity:** Uses Ed25519 for identity and X25519 for ephemeral session keys (handshake).
- **Storage:** Room DB for messages/chats, `EncryptedSharedPreferences` for user metadata.
