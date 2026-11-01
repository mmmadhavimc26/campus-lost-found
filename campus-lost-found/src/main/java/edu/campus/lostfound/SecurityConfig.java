package edu.campus.lostfound;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;

@Configuration
class SecurityConfig {
    @Bean SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean CsrfTokenRepository csrfTokenRepository() {
        return new HttpSessionCsrfTokenRepository();
    }

    @Bean AuthenticationManager authenticationManager() {
        return authentication -> { throw new BadCredentialsException("Password sign-in is not available."); };
    }

    @Bean SecurityFilterChain security(HttpSecurity http, SecurityContextRepository contexts,
                                      CsrfTokenRepository csrfTokens) throws Exception {
        http.authorizeHttpRequests(access -> access
            .requestMatchers("/", "/index.html", "/styles.css", "/app.js", "/favicon.svg", "/error",
                "/api/health", "/api/csrf", "/api/auth/demo", "/api/auth/demo-accounts").permitAll()
            .requestMatchers("/api/auth/register", "/api/auth/login").denyAll()
            .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/reports", "/api/options").permitAll()
            .anyRequest().authenticated())
            .securityContext(context -> context.securityContextRepository(contexts))
            .csrf(csrf -> csrf.csrfTokenRepository(csrfTokens))
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .logout(logout -> logout.logoutUrl("/api/auth/logout")
                .logoutSuccessHandler((request, response, authentication) -> response.setStatus(204)))
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, exception) -> {
                    response.setStatus(401);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"message\":\"Choose a demo student to continue.\"}");
                })
                .accessDeniedHandler((request, response, exception) -> {
                    response.setStatus(403);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"message\":\"Request expired or not allowed. Refresh and try again.\"}");
                }))
            .headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(
                "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; base-uri 'self'; frame-ancestors 'none'; form-action 'self'")));
        return http.build();
    }
}
