package edu.campus.lostfound;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
class DemoLogin {
    record LoginInput(@NotBlank String account) {}
    record AccountView(String key, String name) {}

    private final DemoAccounts accounts;
    private final SecurityContextRepository contexts;
    private final CsrfTokenRepository csrfTokens;

    DemoLogin(DemoAccounts accounts, SecurityContextRepository contexts, CsrfTokenRepository csrfTokens) {
        this.accounts = accounts;
        this.contexts = contexts;
        this.csrfTokens = csrfTokens;
    }

    @GetMapping("/demo-accounts")
    List<AccountView> list() {
        return DemoAccounts.PROFILES.stream().map(profile -> new AccountView(profile.key(), profile.name())).toList();
    }

    @PostMapping("/demo")
    Api.UserView login(@Valid @RequestBody LoginInput input, HttpServletRequest request, HttpServletResponse response) {
        CampusUser user = accounts.account(input.account());
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
            user.email, null, List.of(new SimpleGrantedAuthority("ROLE_STUDENT")));
        new ChangeSessionIdAuthenticationStrategy().onAuthentication(authentication, request, response);
        new CsrfAuthenticationStrategy(csrfTokens).onAuthentication(authentication, request, response);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        return new Api.UserView(user.id, user.name, "STUDENT");
    }
}
