package kvo.itsnn.rag.security;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * Аналог CustomUserDetailsService из ProtocolMenagment: LDAP проверил пароль в AD,
 * здесь достаём пользователя и его права из локальной БД RAG.
 * Пользователя нет в dbo.rag_user → логин не пройдёт (добавьте его в админке/скриптом).
 */
@Service
public class RagUserDetailsService implements UserDetailsService {

    private final RagUserRepository users;

    public RagUserDetailsService(RagUserRepository users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        RagUser user = users.findByAccountName(username)
                .orElseThrow(() -> new UsernameNotFoundException("Пользователь не найден в RAG: " + username));
        Set<String> permissions = users.findPermissionsByUsername(username);
        return new CustomUserDetails(user, permissions);
    }
}