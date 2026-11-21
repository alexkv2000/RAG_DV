package kvo.itsnn.rag.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.Set;

/**
 * UserDetails с правами из БД RAG: строки прав («use:chat», «superAdmin» ...)
 * превращаются в GrantedAuthority для hasAuthority(...) в @PreAuthorize и SecurityFilterChain.
 * Пароль — заглушка: проверку пароля делает LDAP (BindAuthenticator).
 */
public final class CustomUserDetails implements UserDetails {

    private final RagUser user;
    private final Set<String> permissions;

    public CustomUserDetails(RagUser user, Set<String> permissions) {
        this.user = user;
        this.permissions = Set.copyOf(permissions);
    }

    public RagUser getUser() {
        return user;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return permissions.stream()
                .map(SimpleGrantedAuthority::new)
                .toList();
    }

    @Override
    public String getPassword() {
        return "{noop}password";   // аутентификация идёт через AD, пароль здесь не проверяется
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