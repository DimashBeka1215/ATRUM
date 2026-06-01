package com.atrum.chat

/**
 * Выбрасывается когда GitHub API вернул 401 Unauthorized или 403 Forbidden.
 *
 * Типичные причины:
 *  — токен был отозван на github.com/settings/tokens
 *  — токен истёк (fine-grained tokens имеют срок действия)
 *  — у токена нет разрешения на gist (нужен scope: gist)
 *
 * Обрабатывается в ChatActivity отдельно от сетевых ошибок:
 * пользователю показывается мягкое предупреждение с рекомендацией.
 */
class TokenExpiredException : RuntimeException("GitHub token is invalid or expired (401/403)")
