package com.atrum.chat

/**
 * Выбрасывается когда GitHub API вернул 429 Too Many Requests.
 *
 * [retryAfterMs] — рекомендованная пауза из заголовка Retry-After (в миллисекундах).
 * Если заголовок отсутствует — используется дефолт 60 секунд.
 *
 * Обрабатывается отдельно от RuntimeException в polling-циклах:
 * ChatActivity делает принудительную паузу и сбрасывает адаптивный интервал.
 */
class RateLimitException(val retryAfterMs: Long = 60_000L)
    : RuntimeException("GitHub API rate limit exceeded, retry after ${retryAfterMs}ms")
