package kvo.itsnn.rag.util;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
/**
 * Кэш сессий чата (ChatService, DirectChatService): неактивные дольше ttlMinutes минут
 * удаляются — раньше ConcurrentHashMap рос бесконечно (свободный чат держал до
 * 3 файлов × direct-max-file-chars ≈ 2,4 МБ на каждую брошенную сессию — риск OOM).
 * ttlMinutes <= 0 — без TTL (как раньше). maximumSize — потолок живых сессий:
 * защита от скрипта, плодящего новые sessionId каждые полсекунды.
 */
public final class SessionCaches {
    /** Живых сессий одного вида чата; при переполнении вытесняются наименее используемые. */
    private static final long MAX_SESSIONS = 10_000;
    private SessionCaches() {
    }
    public static <K, V> Cache<K, V> withTtl(int ttlMinutes) {
        Caffeine<Object, Object> b = Caffeine.newBuilder().maximumSize(MAX_SESSIONS);
        if (ttlMinutes > 0) {
            b.expireAfterAccess(Duration.ofMinutes(ttlMinutes));
        }
        return b.build();
    }
}