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
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        // API-запросы без сессии → 401 (JSON-фронт сам перебросит на /login.html)
        PathPatternRequestMatcher api = PathPatternRequestMatcher.withDefaults().matcher("/api/**");
        http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        // статика и логин — без аутентификации
                        .requestMatchers("/login", "/login.html", "/error", "/favicon.ico",
                                "/css/**", "/js/**", "/lib/**").permitAll()
                        .requestMatchers("/useradmin", "/admin-users.html")
                        .hasAnyAuthority("edit:users", "superAdmin")
                        // управление пользователями/группами
                        .requestMatchers("/api/rag/admin/security/**")
                        .hasAnyAuthority("edit:users", "superAdmin")
                        // промпты: чтение — просмотр админки, запись — edit:prompts
                        .requestMatchers(HttpMethod.GET, "/api/rag/admin/prompts/**")
                        .hasAnyAuthority("read:admin", "edit:prompts", "superAdmin")
                        .requestMatchers("/api/rag/admin/prompts/**")
                        .hasAnyAuthority("edit:prompts", "superAdmin")
                        // eval: чтение истории/вопросов — read:admin, прогоны — run:eval
                        .requestMatchers(HttpMethod.GET, "/api/rag/admin/eval/**")
                        .hasAnyAuthority("read:admin", "run:eval", "superAdmin")
                        .requestMatchers("/api/rag/admin/eval/**")
                        .hasAnyAuthority("run:eval", "superAdmin")
                        // остальная админка: GET — read:admin, управление — edit:admin
                        .requestMatchers(HttpMethod.GET, "/api/rag/admin/**")
                        .hasAnyAuthority("read:admin", "superAdmin")
                        .requestMatchers("/api/rag/admin/**")
                        .hasAnyAuthority("edit:admin", "superAdmin")
                        // интеграция «файл → JSON»
                        .requestMatchers("/api/rag/extract/**")
                        .hasAnyAuthority("use:extract", "superAdmin")
                        // «кто я» — любому аутентифицированному (UI рисует вкладки по правам)
                        .requestMatchers("/api/rag/me").authenticated()
                        // чаты, поиск, классификаторы, direct-chat
                        .requestMatchers("/api/rag/**")
                        .hasAnyAuthority("use:chat", "use:extract", "superAdmin")
                        // главная страница чата
                        .requestMatchers("/", "/index.html")
                        .hasAnyAuthority("use:chat", "superAdmin")
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