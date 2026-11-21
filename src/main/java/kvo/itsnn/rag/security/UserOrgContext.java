package kvo.itsnn.rag.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/**
 * ЮЛ текущего пользователя из SecurityContext.
 *
 * Возвращает:
 *   null  — фильтр по ЮЛ выключен: нет аутентификации (eval, планировщик),
 *           superAdmin, ИЛИ пользователь в списке полного доступа
 *           (dbo.rag_full_access_users, is_active=1);
 *   Set<> — множество доступных ЮЛ (может быть пустым — только общие документы).
 */
@Component
public class UserOrgContext {

    private static final Logger log = LoggerFactory.getLogger(UserOrgContext.class);
    private static final String SUPER_ADMIN = "superAdmin";

    private final JdbcTemplate jdbc;

    /** Полный доступ проверяется SELECT'ом по PK; кэш на 60 сек — отзыв доступа
     *  действует в течение минуты, а не на каждом вопросе (LLM-вызов дороже). */
    private final Cache<String, Boolean> fullAccess = Caffeine.newBuilder()
            .maximumSize(1_000)
            .expireAfterWrite(Duration.ofSeconds(60))
            .build();

    public UserOrgContext(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Set<String> currentUserOrgs() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return null;

        Object principal = auth.getPrincipal();
        if (!(principal instanceof CustomUserDetails cud)) return null;

        if (isSuperAdmin(cud) || hasFullAccess(cud.getUsername())) {
            return null;    // фильтр снят: superAdmin или список полного доступа
        }

        Set<String> orgs = cud.getOrgLe();
        if (log.isDebugEnabled()) {
            log.debug("UserOrgContext: user='{}', orgs={}", cud.getUsername(), orgs);
        }
        return orgs != null ? orgs : Set.of();
    }

    private static boolean isSuperAdmin(CustomUserDetails cud) {
        return cud.getAuthorities().stream()
                .anyMatch(a -> SUPER_ADMIN.equals(a.getAuthority()));
    }

    private boolean hasFullAccess(String login) {
        String key = login.toLowerCase(Locale.ROOT);
        try {
            Boolean v = fullAccess.get(key, k -> {
                Boolean r = jdbc.queryForObject(
                        "SELECT dbo.fn_rag_has_full_access(?)", Boolean.class, k);
                return r != null && r;
            });
            if (Boolean.TRUE.equals(v)) {
                log.debug("UserOrgContext: '{}' — полный доступ (rag_full_access_users)", key);
            }
            return Boolean.TRUE.equals(v);
        } catch (Exception e) {           // БД моргнула — не открываем всем всё
            log.warn("full-access не проверен для '{}' ({}): работаем по группам",
                    key, e.getMessage());
            return false;
        }
    }
}