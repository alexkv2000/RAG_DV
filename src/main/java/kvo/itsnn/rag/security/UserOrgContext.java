package kvo.itsnn.rag.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * ЮЛ текущего пользователя из SecurityContext.
 *
 * Возвращает:
 *   null  — фильтр по ЮЛ выключен:
 *           нет аутентификации (eval, планировщик), superAdmin;
 *   Set<> — множество доступных ЮЛ (может быть пустым — только общие документы).
 *
 * Пользователь числится в нескольких ЮЛ одновременно: список = объединение
 * всех org_le всех групп пользователя (rag_user → rag_user_group → rag_group_org).
 *
 * Инжектится в сервисы (AskService, CompareService, DocSummaryService, SearchController) —
 * сигнатуры контроллеров и цепочек вызовов не меняются.
 */
@Component
public class UserOrgContext {

    private static final Logger log = LoggerFactory.getLogger(UserOrgContext.class);
    private static final String SUPER_ADMIN = "superAdmin";

    /**
     * Множество ЮЛ, доступных текущему пользователю.
     * null → без фильтра (superAdmin / нет аутентификации / внутренний вызов).
     */
    public Set<String> currentUserOrgs() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;    // планировщик, eval без HTTP-контекста
        }

        Object principal = auth.getPrincipal();
        if (!(principal instanceof CustomUserDetails cud)) {
            return null;    // anonymousUser или другой тип
        }

        // superAdmin видит все ЮЛ — фильтр не нужен
        boolean isSuperAdmin = cud.getAuthorities().stream()
                .anyMatch(a -> SUPER_ADMIN.equals(a.getAuthority()));
        if (isSuperAdmin) {
            return null;
        }

        Set<String> orgs = cud.getOrgLe();
        if (log.isDebugEnabled()) {
            log.debug("UserOrgContext: user='{}', orgs={}", cud.getUsername(), orgs);
        }
        return orgs != null ? orgs : Set.of();
    }
}