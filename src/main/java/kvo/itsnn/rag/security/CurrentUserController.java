package kvo.itsnn.rag.security;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/** «Кто я»: логин и права текущего пользователя — фронт по ним рисует вкладки. */
@RestController
public class CurrentUserController {

    public record MeView(String accountName, String fullName, String email, Set<String> permissions) {}

    @GetMapping("/api/rag/me")
    public MeView me(@AuthenticationPrincipal CustomUserDetails user) {
        Set<String> perms = new java.util.LinkedHashSet<>();
        user.getAuthorities().forEach(a -> perms.add(a.getAuthority()));
        return new MeView(user.getUsername(), user.getUser().fullName(),
                user.getUser().email(), perms);
    }
}