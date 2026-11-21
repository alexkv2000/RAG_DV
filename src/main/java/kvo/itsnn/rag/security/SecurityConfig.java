package kvo.itsnn.rag.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.ldap.DefaultSpringSecurityContextSource;
import org.springframework.security.ldap.authentication.BindAuthenticator;
import org.springframework.security.ldap.authentication.LdapAuthenticationProvider;
import org.springframework.security.ldap.search.FilterBasedLdapUserSearch;
import org.springframework.security.ldap.userdetails.UserDetailsContextMapper;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

import java.util.Collection;
import java.util.List;

/**
 * Авторизация как в ProtocolMenagment: пароль — LDAP bind к AD (gaz.ru),
 * права — из локальной БД (rag_user → rag_user_group → rag_group_permission).
 *
 * Отличия для RAG: /api/** без сессии отвечает 401 (fetch во фронте не ломается
 * о HTML страницы логина), а правила URL расписаны под эндпоинты RAG.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /* ---------- права RAG (dbo.rag_permission.name; засев — sql/rag_security.sql).
       Одно место правды для hasAnyAuthority ниже и @PreAuthorize в контроллерах. */
    private static final String USE_CHAT = "use:chat";
    private static final String USE_EXTRACT = "use:extract";
    private static final String READ_ADMIN = "read:admin";
    private static final String EDIT_ADMIN = "edit:admin";
    private static final String EDIT_PROMPTS = "edit:prompts";
    private static final String RUN_EVAL = "run:eval";
    private static final String EDIT_USERS = "edit:users";
    /** Право-«мастер-ключ»: присутствует во всех списках прав выше по цепочке правил. */
    private static final String SUPER_ADMIN = "superAdmin";

    /* ---------- URL-шаблоны, встречающиеся в правилах больше одного раза ---------- */
    private static final String URL_ADMIN = "/api/rag/admin/**";
    private static final String URL_ADMIN_PROMPTS = "/api/rag/admin/prompts/**";
    private static final String URL_ADMIN_EVAL = "/api/rag/admin/eval/**";
    private static final String URL_EXTRACT = "/api/rag/extract/**";
    private static final String URL_API = "/api/rag/**";
    /** Страница управления доступом (/useradmin — алиас, forward на admin-users.html). */
    private static final String URL_USERADMIN = "/useradmin";
    private static final String URL_USERADMIN_PAGE = "/admin-users.html";

    @Value("${spring.ldap.urls}")
    private String ldapUrl;
    @Value("${ldap.admin.username}")
    private String ldapUsername;
    @Value("${ldap.admin.password}")
    private String ldapPassword;
    @Value("${ldap.base-dn:dc=gaz,dc=ru}")
    private String ldapBaseDn;
    @Value("${ldap.user-search-filter:(sAMAccountName={0})}")
    private String ldapUserSearchFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) {
        // API-запросы без сессии → 401 (JSON-фронт сам перебросит на /login.html)
        PathPatternRequestMatcher api = PathPatternRequestMatcher.withDefaults().matcher("/api/**");
        http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        // статика и логин — без аутентификации
                        .requestMatchers("/login", "/login.html", "/error", "/favicon.ico",
                                "/css/**", "/js/**", "/lib/**").permitAll()
                        // страница управления доступом — edit:users/superAdmin
                        .requestMatchers(URL_USERADMIN, URL_USERADMIN_PAGE)
                        .hasAnyAuthority(EDIT_USERS, SUPER_ADMIN)
                        // управление пользователями/группами
                        .requestMatchers("/api/rag/admin/security/**")
                        .hasAnyAuthority(EDIT_USERS, SUPER_ADMIN)
                        // промпты: чтение — просмотр админки, запись — edit:prompts
                        .requestMatchers(HttpMethod.GET, URL_ADMIN_PROMPTS)
                        .hasAnyAuthority(READ_ADMIN, EDIT_PROMPTS, SUPER_ADMIN)
                        .requestMatchers(URL_ADMIN_PROMPTS)
                        .hasAnyAuthority(EDIT_PROMPTS, SUPER_ADMIN)
                        // eval: чтение истории/вопросов — read:admin, прогоны — run:eval
                        .requestMatchers(HttpMethod.GET, URL_ADMIN_EVAL)
                        .hasAnyAuthority(READ_ADMIN, RUN_EVAL, SUPER_ADMIN)
                        .requestMatchers(URL_ADMIN_EVAL)
                        .hasAnyAuthority(RUN_EVAL, SUPER_ADMIN)
                        // остальная админка: GET — read:admin, управление — edit:admin
                        .requestMatchers(HttpMethod.GET, URL_ADMIN)
                        .hasAnyAuthority(READ_ADMIN, SUPER_ADMIN)
                        .requestMatchers(URL_ADMIN)
                        .hasAnyAuthority(EDIT_ADMIN, SUPER_ADMIN)
                        // интеграция «файл → JSON»
                        .requestMatchers(URL_EXTRACT)
                        .hasAnyAuthority(USE_EXTRACT, SUPER_ADMIN)
                        // «кто я» — любому аутентифицированному (UI рисует вкладки по правам)
                        .requestMatchers("/api/rag/me").authenticated()
                        // чаты, поиск, классификаторы, direct-chat
                        .requestMatchers(URL_API)
                        .hasAnyAuthority(USE_CHAT, USE_EXTRACT, SUPER_ADMIN)
                        // главная страница чата
                        .requestMatchers("/", "/index.html")
                        .hasAnyAuthority(USE_CHAT, SUPER_ADMIN)
                        .anyRequest().authenticated()
                )
                .formLogin(form -> form
                        .loginPage("/login.html")          // статическая форма (static/login.html)
                        .loginProcessingUrl("/login")      // POST из формы
                        .defaultSuccessUrl("/", true)      // после успеха — в чат
                        .failureUrl("/login.html?error")
                        .permitAll()
                )
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login.html?logout")
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID")
                        .permitAll()
                )
                .sessionManagement(s -> s.invalidSessionUrl("/login.html?expired"))
                .exceptionHandling(ex -> ex
                        .defaultAuthenticationEntryPointFor(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED), api)
                        .defaultAccessDeniedHandlerFor((req, res, e) -> {
                            res.setStatus(HttpStatus.FORBIDDEN.value());
                            res.setContentType("application/json;charset=UTF-8");
                            res.getWriter().write("{\"error\":\"нет прав для этой операции\"}");
                        }, api)
                );
        return http.build();
    }

    /** LDAP-провайдер AD: bind по sAMAccountName; после успешного пароля права берём из БД RAG. */
    @Bean
    public LdapAuthenticationProvider ldapAuthenticationProvider(RagUserDetailsService userDetailsService) {
        DefaultSpringSecurityContextSource contextSource =
                new DefaultSpringSecurityContextSource(ldapUrl + "/" + ldapBaseDn);
        contextSource.setReferral("ignore");
        contextSource.setUserDn(ldapUsername);
        contextSource.setPassword(ldapPassword);
        try {
            contextSource.afterPropertiesSet();
        } catch (Exception e) {
            throw new IllegalStateException("Не инициализирован LDAP Context Source", e);
        }

        FilterBasedLdapUserSearch userSearch =
                new FilterBasedLdapUserSearch("", ldapUserSearchFilter, contextSource);
        userSearch.setSearchSubtree(true);

        BindAuthenticator bindAuthenticator = new BindAuthenticator(contextSource);
        bindAuthenticator.setUserSearch(userSearch);

        LdapAuthenticationProvider provider = new LdapAuthenticationProvider(bindAuthenticator);
        // как в ProtocolMenagment: AD проверил пароль → права ищем в dbo.rag_*
        provider.setUserDetailsContextMapper(new UserDetailsContextMapper() {
            @Override
            public UserDetails mapUserFromContext(org.springframework.ldap.core.DirContextOperations ctx,
                                                  String username,
                                                  Collection<? extends GrantedAuthority> authorities) {
                return userDetailsService.loadUserByUsername(username);
            }

            @Override
            public void mapUserToContext(UserDetails user, org.springframework.ldap.core.DirContextAdapter ctx) {
                // запись в AD не нужна
            }
        });
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(LdapAuthenticationProvider ldapAuthenticationProvider) {
        return new ProviderManager(List.of(ldapAuthenticationProvider));
    }
}