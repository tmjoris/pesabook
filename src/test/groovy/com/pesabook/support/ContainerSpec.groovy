package com.pesabook.support

import com.redis.testcontainers.RedisContainer
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import spock.lang.Specification

/**
 * Shared containers for the integration specs.
 *
 * The containers are started once and reused across specs rather than per spec,
 * because starting PostgreSQL for every class turns a fast suite into a slow
 * one. Flyway rebuilds the schema for each context, so specs do not see each
 * other's rows.
 */
abstract class ContainerSpec extends Specification {

    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse('postgres:16-alpine'))

    static final RedisContainer REDIS =
            new RedisContainer(DockerImageName.parse('redis:7-alpine'))

    static {
        POSTGRES.start()
        REDIS.start()
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add('spring.datasource.url', POSTGRES::getJdbcUrl)
        registry.add('spring.datasource.username', POSTGRES::getUsername)
        registry.add('spring.datasource.password', POSTGRES::getPassword)
        registry.add('spring.data.redis.host', REDIS::getRedisHost)
        registry.add('spring.data.redis.port', REDIS::getRedisPort)
        registry.add('spring.flyway.clean-disabled', { 'false' })
    }
}
