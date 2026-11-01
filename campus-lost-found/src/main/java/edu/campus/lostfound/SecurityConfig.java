package edu.campus.lostfound;

import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
    @Bean UserDetailsService userDetailsService(Users users) {
        return email -> users.findByEmail(email).map(u -> User.withUsername(u.email)
            .password(u.passwordHash).roles("STUDENT").disabled(!u.enabled).build())
            .orElseThrow(() -> new UsernameNotFoundException("Invalid login"));
    }
    @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {

        http.authorizeHttpRequests(a -> a
            .requestMatchers("/", "/index.html", "/styles.css", "/app.js", "/favicon.svg", "/error", "/api/health", "/api/csrf", "/api/auth/register").permitAll()
            .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/reports", "/api/options").permitAll()
            .anyRequest().authenticated())
            .formLogin(f -> f.loginProcessingUrl("/api/auth/login")
                .successHandler((q,s,a) -> { s.setContentType("application/json"); s.getWriter().write("{\"message\":\"Signed in\"}"); })
                .failureHandler((q,s,e) -> { s.setStatus(401); s.setContentType("application/json"); s.getWriter().write("{\"message\":\"Email or password is incorrect.\"}"); }))
            .logout(l -> l.logoutUrl("/api/auth/logout").logoutSuccessHandler((q,s,a) -> s.setStatus(204)))
            .exceptionHandling(e -> e
                .authenticationEntryPoint((q,s,x) -> { s.setStatus(401); s.setContentType("application/json"); s.getWriter().write("{\"message\":\"Please sign in to continue.\"}"); })
                .accessDeniedHandler((q,s,x) -> { s.setStatus(403); s.setContentType("application/json"); s.getWriter().write("{\"message\":\"Request expired or not allowed. Refresh and try again.\"}"); }))
            .headers(h -> h.contentSecurityPolicy(c -> c.policyDirectives("default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; base-uri 'self'; frame-ancestors 'none'; form-action 'self'")));
        return http.build();
    }
}
