package kvo.itsnn.rag.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Управление списком пользователей с ПОЛНЫМ доступом к документам всех ЮЛ
 * (dbo.rag_full_access_users): видят всё, минуя привязку групп к ЮЛ.
 *
 * URL /api/rag/admin/security/** уже закрыт правом edit:users/superAdmin
 * в SecurityConfig — этот контроллер попадает под правило автоматически.
 *
 * granted_by всегда = логин того, кто нажал кнопку (с фронта не принимается —
 * не подделаешь); логин хранится как sAMAccountName в нижнем регистре
 * (без домена) — так его сравнивает fn_rag_has_full_access.
 */
@RestController
@RequestMapping("/api/rag/admin/security/full-access")
@PreAuthorize("hasAuthority('edit:users') or hasAuthority('superAdmin')")
public class FullAccessAdminController {

    private final JdbcTemplate jdbc;

    public FullAccessAdminController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /* ---------- строки выдачи ---------- */

    /** Строка списка: запись таблицы + ФИО из rag_user (если пользователь заведён). */
    public record FullAccessRow(String domainLogin, String displayName, String ragFullName,
                                String grantedBy, String grantedAt, boolean isActive) {}

    public record FullAccessRequest(String domainLogin, String displayName, Boolean isActive) {}

    /* ---------- CRUD ---------- */

    @GetMapping
    public List<FullAccessRow> list() {
        return jdbc.query("""
                SELECT f.domain_login,
                       f.display_name,
                       u.full_name,
                       f.granted_by,
                       f.granted_at,
                       f.is_active
                FROM dbo.rag_full_access_users f
                LEFT JOIN dbo.rag_user u ON LOWER(u.account_name) = f.domain_login
                ORDER BY f.is_active DESC, f.domain_login
                """, (rs, i) -> new FullAccessRow(
                rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), String.valueOf(rs.getTimestamp(5)), rs.getBoolean(6)));
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody FullAccessRequest req) {
        String login = norm(req.domainLogin());
        if (login == null)
            throw new IllegalArgumentException("логин пустой — укажите доменный логин из AD (sAMAccountName, без домена)");
        if (login.contains("\\"))
            throw new IllegalArgumentException("логин указывайте БЕЗ домена: '" + login
                    + "' → '" + login.substring(login.indexOf('\\') + 1) + "' (sAMAccountName из AD)");
        if (exists(login))
            throw new IllegalArgumentException("логин '" + login + "' уже есть в списке");
        jdbc.update("""
                INSERT INTO dbo.rag_full_access_users (domain_login, display_name, granted_by, is_active)
                VALUES (?, ?, ?, ?)
                """, login, nz(req.displayName()), currentLogin(),
                req.isActive() == null || req.isActive());
        return Map.of("result", "created", "domainLogin", login, "grantedBy", currentLogin());
    }

    /** Редактирование: только подпись и активность (логин и granted_by не трогаем). */
    @PutMapping("/{login}")
    public Map<String, Object> update(@PathVariable String login, @RequestBody FullAccessRequest req) {
        String key = norm(login);
        int n = jdbc.update("""
                UPDATE dbo.rag_full_access_users
                   SET display_name = ?, is_active = ?
                 WHERE domain_login = ?
                """, nz(req.displayName()), req.isActive() == null || req.isActive(), key);
        if (n == 0) throw new IllegalArgumentException("записи с логином '" + login + "' нет");
        return Map.of("result", "updated", "domainLogin", key);
    }

    /** Полное удаление записи. Чтобы только временно закрыть доступ — снимите «активен». */
    @DeleteMapping("/{login}")
    public Map<String, Object> delete(@PathVariable String login) {
        jdbc.update("DELETE FROM dbo.rag_full_access_users WHERE domain_login = ?", norm(login));
        return Map.of("result", "deleted");
    }

    /* ---------- расширенный просмотр: что этот пользователь видит в RAG ---------- */

    /**
     * Просмотр от лица пользователя: статус доступа, счётчики документов
     * (всего / общие / своё ЮЛ / чужие ЮЛ — видны только из-за полного доступа),
     * распределение по всем ЮЛ и список видимых документов с поиском и фильтром.
     *
     * @param login     логин из списка (или любой — увидите «доступа нет»)
     * @param orgLe     ЮЛ пользователя (необязательно; пусто → «своих» 0)
     * @param search    поиск по имени/номеру документа
     * @param filterOrg фильтр списка по конкретному ЮЛ
     */
    @GetMapping("/preview")
    public Map<String, Object> preview(@RequestParam String login,
                                       @RequestParam(required = false) String orgLe,
                                       @RequestParam(required = false) String search,
                                       @RequestParam(required = false) String filterOrg) {
        String key = norm(login);
        boolean full = hasFullAccess(key);

        // распределение всех документов по ЮЛ ('' — общие)
        List<Map<String, Object>> byOrg = jdbc.query("""
                SELECT COALESCE(org_le, N'') AS org_le, COUNT(*) AS docs
                FROM dbo.rag_document_ref
                WHERE is_deleted = 0
                GROUP BY COALESCE(org_le, N'')
                ORDER BY COUNT(*) DESC
                """, (rs, i) -> Map.of("orgLe", rs.getString(1), "docs", rs.getInt(2)));

        int total = byOrg.stream().mapToInt(m -> (int) m.get("docs")).sum();
        int shared = byOrg.stream().filter(m -> ((String) m.get("orgLe")).isBlank())
                .mapToInt(m -> (int) m.get("docs")).sum();
        String myOrg = nz(orgLe);
        int own = myOrg.isEmpty() ? 0 : byOrg.stream()
                .filter(m -> myOrg.equals(m.get("orgLe"))).mapToInt(m -> (int) m.get("docs")).sum();
        int crossLe = full ? total - shared - own : 0;   // видно ТОЛЬКО благодаря полному доступу

        // список видимых документов — то же условие, что в rag_get_visible_documents
        List<Map<String, Object>> docs = jdbc.query("""
                SELECT TOP (100)
                       r.document_guid, r.doc_name, r.doc_number,
                       COALESCE(r.org_le, N'') AS org_le,
                       r.classifier, r.status, r.indexed_at,
                       CASE WHEN ? = 1 AND COALESCE(r.org_le, N'') <> N''
                                 AND COALESCE(r.org_le, N'') <> ?
                            THEN 1 ELSE 0 END AS is_cross_le
                FROM dbo.rag_document_ref r
                WHERE r.is_deleted = 0
                  AND (? = 1 OR COALESCE(r.org_le, N'') = N'' OR COALESCE(r.org_le, N'') = ?)
                  AND (? IS NULL OR r.doc_name LIKE '%' + ? + '%' OR r.doc_number LIKE '%' + ? + '%')
                  AND (? IS NULL OR COALESCE(r.org_le, N'') = ?)
                ORDER BY r.src_modified_at DESC
                """, (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("guid", rs.getString(1));
                    m.put("name", rs.getString(2));
                    m.put("number", rs.getString(3));
                    m.put("orgLe", rs.getString(4));
                    m.put("classifier", rs.getString(5));
                    m.put("status", rs.getString(6));
                    m.put("indexedAt", String.valueOf(rs.getTimestamp(7)));
                    m.put("crossLe", rs.getInt(8) == 1);
                    return m;
                }, full, myOrg, full, myOrg, nz(search), nz(search), nz(search),
                nz(filterOrg), nz(filterOrg));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("login", key);
        out.put("fullAccess", full);
        out.put("orgLe", myOrg);
        out.put("counts", Map.of("total", total, "shared", shared,
                "own", own, "crossLe", crossLe));
        out.put("byOrg", byOrg);
        out.put("documents", docs);
        return out;
    }

    /* ---------- внутреннее ---------- */

    private boolean hasFullAccess(String login) {
        Boolean v = jdbc.queryForObject(
                "SELECT dbo.fn_rag_has_full_access(?)", Boolean.class, login);
        return v != null && v;
    }

    private boolean exists(String login) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dbo.rag_full_access_users WHERE domain_login = ?",
                Integer.class, login);
        return n != null && n > 0;
    }

    /** Логин текущего администратора — идёт в granted_by. */
    private static String currentLogin() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a == null ? "system" : a.getName();
    }

    /** Нормализация: trim + нижний регистр (домен отрезаем с ошибкой — пусть правят осознанно). */
    private static String norm(String s) {
        return s == null ? null : s.strip().toLowerCase(Locale.ROOT);
    }

    private static String nz(String s) {
        return s == null ? "" : s.strip();
    }
}