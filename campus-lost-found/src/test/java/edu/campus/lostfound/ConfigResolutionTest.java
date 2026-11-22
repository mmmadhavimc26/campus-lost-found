package edu.campus.lostfound;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.ResourcePropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigResolutionTest {

    @Test
    void resolvesRenderStyleEnvironmentVariables() throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new ResourcePropertySource(new ClassPathResource("application-prod.properties")));
        env.getPropertySources().addFirst(new MapPropertySource("render-env", Map.of(
            "DB_HOST", "render-db-host",
            "DB_PORT", "5432",
            "DB_NAME", "campus",
            "DB_USER", "campus_user",
            "DB_PASSWORD", "secret"
        )));

        String url = env.resolvePlaceholders(env.getProperty("spring.datasource.url"));
        String user = env.resolvePlaceholders(env.getProperty("spring.datasource.username"));
        String pass = env.resolvePlaceholders(env.getProperty("spring.datasource.password"));

        assertThat(url).isEqualTo("jdbc:postgresql://render-db-host:5432/campus");
        assertThat(user).isEqualTo("campus_user");
        assertThat(pass).isEqualTo("secret");
    }

    @Test
    void resolvesSupabaseStyleEnvironmentVariables() throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new ResourcePropertySource(new ClassPathResource("application-prod.properties")));
        env.getPropertySources().addFirst(new MapPropertySource("supabase-env", Map.of(
            "DB_HOST", "aws-0-ap-southeast-2.pooler.supabase.com",
            "DB_PORT", "5432",
            "DB_NAME", "postgres",
            "DB_USER", "postgres.tbdnzwdridzxqfvkrtwj",
            "DB_PASSWORD", "supabase-secret",
            "DB_PARAMS", "?sslmode=require"
        )));

        String url = env.resolvePlaceholders(env.getProperty("spring.datasource.url"));
        String user = env.resolvePlaceholders(env.getProperty("spring.datasource.username"));
        String pass = env.resolvePlaceholders(env.getProperty("spring.datasource.password"));

        assertThat(url).isEqualTo("jdbc:postgresql://aws-0-ap-southeast-2.pooler.supabase.com:5432/postgres?sslmode=require");
        assertThat(user).isEqualTo("postgres.tbdnzwdridzxqfvkrtwj");
        assertThat(pass).isEqualTo("supabase-secret");
    }

    @Test
    void resolvesOptionalFullDbUrlConfiguration() throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new ResourcePropertySource(new ClassPathResource("application-prod.properties")));
        env.getPropertySources().addFirst(new MapPropertySource("db-url-env", Map.of(
            "DB_URL", "jdbc:postgresql://aws-0-ap-southeast-2.pooler.supabase.com:5432/postgres?sslmode=require",
            "DB_USER", "postgres.tbdnzwdridzxqfvkrtwj",
            "DB_PASSWORD", "supabase-secret"
        )));

        String url = env.resolvePlaceholders(env.getProperty("spring.datasource.url"));
        String user = env.resolvePlaceholders(env.getProperty("spring.datasource.username"));
        String pass = env.resolvePlaceholders(env.getProperty("spring.datasource.password"));

        assertThat(url).isEqualTo("jdbc:postgresql://aws-0-ap-southeast-2.pooler.supabase.com:5432/postgres?sslmode=require");
        assertThat(user).isEqualTo("postgres.tbdnzwdridzxqfvkrtwj");
        assertThat(pass).isEqualTo("supabase-secret");
    }

    @Test
    void failsWhenUsernameOrPasswordMissing() throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new ResourcePropertySource(new ClassPathResource("application-prod.properties")));
        env.getPropertySources().addFirst(new MapPropertySource("incomplete-env", Map.of(
            "DB_HOST", "aws-0-ap-southeast-2.pooler.supabase.com",
            "DB_PORT", "5432",
            "DB_NAME", "postgres"
        )));

        assertThatThrownBy(() -> env.resolveRequiredPlaceholders(env.getProperty("spring.datasource.username")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Could not resolve placeholder 'DB_USER'");

        assertThatThrownBy(() -> env.resolveRequiredPlaceholders(env.getProperty("spring.datasource.password")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Could not resolve placeholder 'DB_PASSWORD'");
    }
}
