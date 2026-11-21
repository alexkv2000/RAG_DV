package kvo.itsnn.rag.security;

import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * Доступ к dbo.rag_user / rag_group / rag_permission (JdbcTemplate — в RAG нет JPA).
 * Аналог UserRepository + UserGroupRepository из ProtocolMenagment, объединён в один класс.
 */
@Repository
public class RagUserRepository {

    private final JdbcTemplate jdbc;

    public RagUserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /* ---------- строки выдачи ---------- */

    public record GroupRow(long id, String name, String description) {
    }

    public record PermissionRow(long id, String name, String controllerName,
                                String httpMethod, String urlPattern) {
    }

    /* ---------- пользователи ---------- */

    public List<RagUser> findAll() {
        return jdbc.query("""
                SELECT id, full_name, account_name, email, is_active
                FROM dbo.rag_user ORDER BY account_name
                """, (rs, i) -> userRow(rs));
    }

    public Optional<RagUser> findByAccountName(String accountName) {
        return jdbc.query("""
                SELECT id, full_name, account_name, email, is_active
                FROM dbo.rag_user WHERE account_name = ?
                """, (rs, i) -> userRow(rs), accountName).stream().findFirst();
    }

    /**
     * Права пользователя через группы — то же соединение, что в ProtocolMenagment.
     */
    public Set<String> findPermissionsByUsername(String accountName) {
        return new LinkedHashSet<>(jdbc.queryForList("""
                SELECT DISTINCT p.name
                FROM dbo.rag_user u
                JOIN dbo.rag_user_group ug        ON ug.user_id = u.id
                JOIN dbo.rag_group_permission gp  ON gp.group_id = ug.group_id
                JOIN dbo.rag_permission p         ON p.id = gp.permission_id
                WHERE u.account_name = ? AND u.is_active = 1
                """, String.class, accountName));
    }

    public void userMustExist(long id) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM dbo.rag_user WHERE id = ?",
                Integer.class, id);
        if (n == null || n == 0) throw new IllegalArgumentException("пользователь с id=" + id + " не найден");
    }

    @Transactional
    public long createUser(String fullName, String accountName, String email,
                           boolean isActive, List<Long> groupIds) {
        Long id = jdbc.queryForObject("""
                INSERT INTO dbo.rag_user (full_name, account_name, email, is_active)
                OUTPUT INSERTED.id VALUES (?, ?, ?, ?)
                """, Long.class, nz(fullName), accountName, nz(email), isActive);
        long userId = Objects.requireNonNull(id, "INSERT dbo.rag_user не вернул id");
        setUserGroups(userId, groupIds);
        return userId;
    }

    @Transactional
    public void updateUser(long id, String fullName, String accountName, String email,
                           boolean isActive, List<Long> groupIds) {
        jdbc.update("""
                UPDATE dbo.rag_user SET full_name = ?, account_name = ?, email = ?, is_active = ?
                WHERE id = ?
                """, nz(fullName), accountName, nz(email), isActive, id);
        setUserGroups(id, groupIds);
    }

    @Transactional
    public void deleteUser(long id) {
        jdbc.update("DELETE FROM dbo.rag_user_group WHERE user_id = ?", id);
        jdbc.update("DELETE FROM dbo.rag_user WHERE id = ?", id);
    }

    /**
     * user_id → group_id одним запросом (список пользователей в админке).
     */
    public Map<Long, List<Long>> groupIdsByUser() {
        return pairsToMap("SELECT user_id, group_id FROM dbo.rag_user_group ORDER BY user_id, group_id");
    }

    private void setUserGroups(long userId, List<Long> groupIds) {
        List<Long> ids = groupIds == null ? List.of() : groupIds;
        jdbc.update("DELETE FROM dbo.rag_user_group WHERE user_id = ?", userId);
        if (ids.isEmpty()) return;
        jdbc.batchUpdate("INSERT INTO dbo.rag_user_group (user_id, group_id) VALUES (?, ?)",
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        ps.setLong(1, userId);
                        ps.setLong(2, ids.get(i));
                    }

                    @Override
                    public int getBatchSize() {
                        return ids.size();
                    }
                });
    }

    /* ---------- группы ---------- */

    public List<GroupRow> findGroups() {
        return jdbc.query("SELECT id, name, description FROM dbo.rag_group ORDER BY name",
                (rs, i) -> new GroupRow(rs.getLong(1), rs.getString(2), rs.getString(3)));
    }

    public Map<Long, List<Long>> permissionIdsByGroup() {
        return pairsToMap("""
                SELECT group_id, permission_id FROM dbo.rag_group_permission
                ORDER BY group_id, permission_id
                """);
    }

    @Transactional
    public long createGroup(String name, String description, List<Long> permissionIds) {
        Long id = jdbc.queryForObject("""
                INSERT INTO dbo.rag_group (name, description) OUTPUT INSERTED.id VALUES (?, ?)
                """, Long.class, name, nz(description));
        long groupId = Objects.requireNonNull(id, "INSERT dbo.rag_group не вернул id");
        setGroupPermissions(groupId, permissionIds);
        return groupId;
    }

    @Transactional
    public void updateGroup(long id, String name, String description, List<Long> permissionIds) {
        jdbc.update("UPDATE dbo.rag_group SET name = ?, description = ? WHERE id = ?",
                name, nz(description), id);
        setGroupPermissions(id, permissionIds);
    }

    @Transactional
    public void deleteGroup(long id) {
        jdbc.update("DELETE FROM dbo.rag_user_group WHERE group_id = ?", id);
        jdbc.update("DELETE FROM dbo.rag_group_permission WHERE group_id = ?", id);
        jdbc.update("DELETE FROM dbo.rag_group WHERE id = ?", id);
    }

    private void setGroupPermissions(long groupId, List<Long> permissionIds) {
        List<Long> ids = permissionIds == null ? List.of() : permissionIds;
        jdbc.update("DELETE FROM dbo.rag_group_permission WHERE group_id = ?", groupId);
        if (ids.isEmpty()) return;
        jdbc.batchUpdate("INSERT INTO dbo.rag_group_permission (group_id, permission_id) VALUES (?, ?)",
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        ps.setLong(1, groupId);
                        ps.setLong(2, ids.get(i));
                    }

                    @Override
                    public int getBatchSize() {
                        return ids.size();
                    }
                });
    }

    /* ---------- права ---------- */

    public List<PermissionRow> findPermissions() {
        return jdbc.query("""
                SELECT id, name, controller_name, http_method, url_pattern
                FROM dbo.rag_permission ORDER BY name
                """, (rs, i) -> new PermissionRow(rs.getLong(1), rs.getString(2),
                rs.getString(3), rs.getString(4), rs.getString(5)));
    }

    /* ---------- внутреннее ---------- */

    /**
     * Парная связь «id → список id» одним запросом: user→groups или group→permissions.
     * Каст (RowCallbackHandler) обязателен: expression-лямбда с boolean-результатом
     * иначе одинаково подходит и под ResultSetExtractor<Boolean> (ambiguous method call).
     */
    private Map<Long, List<Long>> pairsToMap(String sql) {
        Map<Long, List<Long>> m = new LinkedHashMap<>();
        jdbc.query(sql, (RowCallbackHandler) rs ->
                m.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(rs.getLong(2)));
        return m;
    }

    private static RagUser userRow(ResultSet rs) throws SQLException {
        return new RagUser(rs.getLong("id"), rs.getString("full_name"),
                rs.getString("account_name"), rs.getString("email"), rs.getBoolean("is_active"));
    }

    private static String nz(String s) {
        return s == null ? "" : s.strip();
    }
}