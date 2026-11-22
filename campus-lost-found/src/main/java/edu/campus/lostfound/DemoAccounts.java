package edu.campus.lostfound;

import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Component
class DemoAccounts implements ApplicationRunner {
    record Profile(String key, String name, String email) {}

    static final List<Profile> PROFILES = List.of(
        new Profile("mayank", "Mayank (demo student)", "mayank@demo.invalid"),
        new Profile("bob", "Test (demo student)", "bob@demo.invalid")
    );

    private final Users users;

    DemoAccounts(Users users) {
        this.users = users;
    }

    @Transactional
    public CampusUser account(String key) {
        Profile profile = PROFILES.stream().filter(item -> item.key().equals(key)).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a listed demo student."));
        return users.findByEmail(profile.email())
            .map(existing -> {
                if (!profile.name().equals(existing.name)) {
                    existing.name = profile.name();
                    return users.saveAndFlush(existing);
                }
                return existing;
            })
            .orElseGet(() ->
                users.saveAndFlush(new CampusUser(profile.name(), profile.email(), "UNUSED")));
    }

    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        PROFILES.forEach(profile -> account(profile.key()));
    }
}
