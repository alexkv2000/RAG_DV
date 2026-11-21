package kvo.itsnn.rag.security;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import java.util.*;

/**
 * Управление пользователями, группами и правами — аналог AdminUserController
 * из ProtocolMenagment (JdbcTemplate вместо JPA). Все методы — edit:users/superAdmin
 * (продублировано и в SecurityFilterChain по URL /api/rag/admin/security/**).
 */
@RestController
@RequestMapping("/api/rag/admin/security")
@PreAuthorize("hasAuthority('edit:users') or hasAuthority('superAdmin')")
public class UserAdminController {

    public record UserView(Long id, String fullName, String accountName, String email,
                           boolean isActive, List<Long> groupIds) {}

    public record UserRequest(String fullName, String accountName, String email,
                              Boolean isActive, Set<Long> groupIds) {}

    public record GroupView(Long id, String name, String description, List<Long> permissionIds) {}

    public record GroupRequest(String name, String description, Set<Long> permissionIds) {}

    public record PermissionView(Long id, String name, String controllerName,
                                 String httpMethod, String urlPattern) {}

    private final RagUserRepository repo;

    public UserAdminController(RagUserRepository repo) {
        this.repo = repo;
    }

    /* ---------- пользователи ---------- */

    @GetMapping("/users")
    public List<UserView> users() {
        Map<Long, List<Long>> groups = repo.groupIdsByUser();
        return repo.findAll().stream()
                .map(u -> new UserView(u.id(), u.fullName(), u.accountName(), u.email(),
                        u.isActive(), groups.getOrDefault(u.id(), List.of())))
                .toList();
    }

    /** Логин берётся из AD, пароль в RAG не хранится. */
    @PostMapping("/users")
    public Map<String, Object> createUser(@RequestBody UserRequest req) {
        require(req.accountName(), "accountName пустой (доменный логин из AD)");
        boolean active = req.isActive() == null || req.isActive();
        long id = repo.createUser(req.fullName(), req.accountName().strip(),
                req.email(), active, toList(req.groupIds()));
        return Map.of("result", "created", "id", id);
    }

    @PutMapping("/users/{id}")
    public Map<String, Object> updateUser(@PathVariable long id, @RequestBody UserRequest req) {
        repo.userMustExist(id);
        require(req.accountName(), "accountName пустой (доменный логин из AD)");
        repo.updateUser(id, req.fullName(), req.accountName().strip(), req.email(),
                req.isActive() == null || req.isActive(), toList(req.groupIds()));
        return Map.of("result", "updated", "id", id);
    }

    @DeleteMapping("/users/{id}")
    public Map<String, Object> deleteUser(@PathVariable long id) {
        repo.deleteUser(id);
        return Map.of("result", "deleted", "id", id);
    }

    /* ---------- группы ---------- */

    @GetMapping("/groups")
    public List<GroupView> groups() {
        Map<Long, List<Long>> perms = repo.permissionIdsByGroup();
        return repo.findGroups().stream()
                .map(g -> new GroupView(g.id(), g.name(), g.description(),
                        perms.getOrDefault(g.id(), List.of())))
                .toList();
    }

    @PostMapping("/groups")
    public Map<String, Object> createGroup(@RequestBody GroupRequest req) {
        require(req.name(), "name пустой");
        long id = repo.createGroup(req.name().strip(), req.description(), toList(req.permissionIds()));
        return Map.of("result", "created", "id", id);
    }

    @PutMapping("/groups/{id}")
    public Map<String, Object> updateGroup(@PathVariable long id, @RequestBody GroupRequest req) {
        require(req.name(), "name пустой");
        repo.updateGroup(id, req.name().strip(), req.description(), toList(req.permissionIds()));
        return Map.of("result", "updated", "id", id);
    }

    @DeleteMapping("/groups/{id}")
    public Map<String, Object> deleteGroup(@PathVariable long id) {
        repo.deleteGroup(id);
        return Map.of("result", "deleted", "id", id);
    }

    /* ---------- права ---------- */

    @GetMapping("/permissions")
    public List<PermissionView> permissions() {
        return repo.findPermissions().stream()
                .map(p -> new PermissionView(p.id(), p.name(), p.controllerName(),
                        p.httpMethod(), p.urlPattern()))
                .toList();
    }

    /* ---------- внутреннее ---------- */

    private static void require(String v, String err) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException(err);
    }

    private static List<Long> toList(Set<Long> ids) {
        return ids == null ? List.of() : List.copyOf(ids);
    }

    /**
     * Страница управления доступом: /useradmin → static/admin-users.html.
     * Копия AdminPanelController из ProtocolMenagment, но право не superAdmin,
     * а edit:users (в RAG управление пользователями выделено отдельным правом).
     * Дублируется правилом URL в SecurityConfig — не-админ получит 403 ещё до контроллера.
     */
    @Controller
    public class UserAdminPageController {
        @GetMapping("/useradmin")
        @PreAuthorize("hasAuthority('edit:users') or hasAuthority('superAdmin')")
        public String userAdminPage() {
            return "forward:/admin-users.html";   // src/main/resources/static
        }
    }

    /* ---------- юридические лица ---------- */
    /** Список всех ЮЛ группы из DocsVision (для выпадающего списка). */
    @GetMapping("/orgs")
    public List<String> orgs() {
        return repo.findAllOrgs();
    }
    /** ЮЛ по каждой группе (для отображения в таблице групп). */
    @GetMapping("/groups-orgs")
    public Map<Long, List<String>> groupsOrgs() {
        return repo.orgsByGroup();
    }
    public record GroupOrgsRequest(Set<String> orgLes) {}
    /** Обновить список ЮЛ группы. Пустой список = группа без привязки к ЮЛ
     *  (члены группы видят только общие документы). */
    @PutMapping("/groups/{id}/orgs")
    public Map<String, Object> updateGroupOrgs(@PathVariable long id,
                                               @RequestBody GroupOrgsRequest req) {
        repo.setGroupOrgs(id, req.orgLes() == null ? List.of() : List.copyOf(req.orgLes()));
        return Map.of("result", "updated", "id", id,
                "orgLes", req.orgLes() == null ? List.of() : req.orgLes());
    }
}