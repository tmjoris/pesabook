package com.pesabook.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;

/**
 * Converts a platform supplied database URI into the form JDBC accepts.
 *
 * Neon, Render, Heroku and most managed providers hand a database over as a
 * single variable shaped like postgres://user:password@host:port/database. The
 * PostgreSQL JDBC driver rejects that outright with "URL must start with
 * jdbc", which is an error that says nothing about where the offending value
 * came from.
 *
 * Runs before the DataSource is built. Anything already beginning with jdbc: is
 * left alone, so local configuration is unaffected.
 */
public class DatabaseUrlEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String PROPERTY_SOURCE_NAME = "platformDatabaseUrl";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment,
                                       SpringApplication application) {

        String raw = environment.getProperty("DATABASE_URL");

        if (raw == null || raw.isBlank() || raw.startsWith("jdbc:")) {
            return;
        }

        Map<String, Object> resolved = convert(raw);

        if (!resolved.isEmpty()) {
            environment.getPropertySources()
                    .addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, resolved));
        }
    }

    static Map<String, Object> convert(String raw) {
        Map<String, Object> properties = new HashMap<>();

        URI uri;
        try {
            uri = new URI(raw);
        } catch (URISyntaxException e) {
            return properties;
        }

        String scheme = uri.getScheme();
        if (scheme == null || !scheme.startsWith("postgres")) {
            return properties;
        }

        int port = uri.getPort() == -1 ? 5432 : uri.getPort();
        String database = uri.getPath() == null ? "" : uri.getPath();

        StringBuilder jdbcUrl = new StringBuilder("jdbc:postgresql://")
                .append(uri.getHost())
                .append(':')
                .append(port)
                .append(database);

        String query = stripLibpqOnlyParameters(uri.getQuery());
        if (query == null || query.isBlank()) {
            // Managed instances generally require TLS and the driver does not
            // assume it.
            jdbcUrl.append("?sslmode=require");
        } else {
            jdbcUrl.append('?').append(query);
        }

        properties.put("spring.datasource.url", jdbcUrl.toString());

        String userInfo = uri.getUserInfo();
        if (userInfo != null && !userInfo.isBlank()) {
            int separator = userInfo.indexOf(':');
            if (separator == -1) {
                properties.put("spring.datasource.username", userInfo);
            } else {
                properties.put("spring.datasource.username", userInfo.substring(0, separator));
                properties.put("spring.datasource.password", userInfo.substring(separator + 1));
            }
        }

        return properties;
    }

    /**
     * Removes parameters that belong to libpq and mean nothing to JDBC.
     *
     * Neon's connection string carries channel_binding=require, which the
     * PostgreSQL JDBC driver does not recognise. Passing an unknown parameter
     * through risks it being forwarded to the server as a startup option, and
     * the resulting error points at authentication rather than at the URL.
     */
    private static String stripLibpqOnlyParameters(String query) {
        if (query == null || query.isBlank()) {
            return query;
        }
        String kept = java.util.Arrays.stream(query.split("&"))
                .filter(param -> !param.startsWith("channel_binding"))
                .reduce((a, b) -> a + "&" + b)
                .orElse("");
        return kept.isBlank() ? null : kept;
    }
}
