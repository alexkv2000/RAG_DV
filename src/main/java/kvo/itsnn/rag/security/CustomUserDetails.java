package kvo.itsnn.rag.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.Set;

/**
 * UserDetails с правами и ЮЛ из БД RAG.
 * orgLe — множество юридических лиц, доступных пользователю
 * (объединение всех org_le всех групп пользователя).
 * Пароль — заглушка: проверку делает LDAP (BindAuthenticator).
 */
public final class CustomUserDetails implements UserDetails {

    private final RagUser user;
    private final Set<String> permissions;
    private final Set<String> orgLe;

    public CustomUserDetails(RagUser user, Set<String> permissions, Set<String> orgLe) {
        this.user = user;
        this.permissions = Set.copyOf(permissions);
        this.orgLe = orgLe == null ? Set.of() : Set.copyOf(orgLe);
    }

    public RagUser getUser() {
        return user;
    }

    /** Доступные ЮЛ (может быть пустым — только общие документы). */
    public Set<String> getOrgLe() {
        return orgLe;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return permissions.stream()
                .map(SimpleGrantedAuthority::new)
                .toList();
    }

    @Override
    public String getPassword() {
        return "{noop}password";
    }

    @Override
    public String getUsername() {
        return user.accountName();
    }

    @Override public boolean isAccountNonExpired()     { return true; }
    @Override public boolean isAccountNonLocked()      { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled()               { return user.isActive(); }
}