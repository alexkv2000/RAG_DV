package kvo.itsnn.rag.security;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * LDAP проверил пароль в AD; здесь достаём пользователя, его права и ЮЛ
 * из локальной БД RAG (dbo.rag_user + rag_group + rag_group_org).
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
                .orElseThrow(() -> new UsernameNotFoundException(
                        "Пользователь не найден в RAG: " + username));
        Set<String> permissions = users.findPermissionsByUsername(username);
        Set<String> orgLe = users.findOrgsByUsername(username);
        return new CustomUserDetails(user, permissions, orgLe);
    }
}