package com.pesabook.config

import spock.lang.Specification

/**
 * Managed providers hand a database over as a postgres:// URI, which the JDBC
 * driver rejects. These specs pin the conversion, because getting it wrong
 * produces a startup failure whose message does not mention the URL.
 */
class DatabaseUrlEnvironmentPostProcessorSpec extends Specification {

    def "converts a Neon style URI into a JDBC url with credentials split out"() {
        when:
        def result = DatabaseUrlEnvironmentPostProcessor.convert(
                'postgresql://neondb_owner:s3cret@ep-cool-shadow-pooler.c-5.us-east-2.aws.neon.tech/neondb?sslmode=require')

        then:
        result['spring.datasource.url'] ==
                'jdbc:postgresql://ep-cool-shadow-pooler.c-5.us-east-2.aws.neon.tech:5432/neondb?sslmode=require'
        result['spring.datasource.username'] == 'neondb_owner'
        result['spring.datasource.password'] == 's3cret'
    }

    def "drops channel_binding, which is a libpq parameter JDBC does not know"() {
        when: "the exact shape Neon hands out"
        def result = DatabaseUrlEnvironmentPostProcessor.convert(
                'postgresql://u:p@host/db?sslmode=require&channel_binding=require')

        then: "keeping it risks an error that points at authentication instead of the URL"
        result['spring.datasource.url'] == 'jdbc:postgresql://host:5432/db?sslmode=require'
        !result['spring.datasource.url'].contains('channel_binding')
    }

    def "falls back to requiring TLS when the URI carries no query at all"() {
        when:
        def result = DatabaseUrlEnvironmentPostProcessor.convert('postgres://u:p@host:5432/db')

        then:
        result['spring.datasource.url'].endsWith('?sslmode=require')
    }

    def "requires TLS when channel_binding was the only parameter present"() {
        when:
        def result = DatabaseUrlEnvironmentPostProcessor.convert(
                'postgres://u:p@host/db?channel_binding=require')

        then: "stripping the only parameter must not leave a dangling question mark"
        result['spring.datasource.url'] == 'jdbc:postgresql://host:5432/db?sslmode=require'
    }

    def "accepts both the postgres and postgresql schemes"() {
        expect:
        DatabaseUrlEnvironmentPostProcessor.convert(uri)['spring.datasource.url']
                .startsWith('jdbc:postgresql://host:5432/db')

        where:
        uri << ['postgres://u:p@host:5432/db', 'postgresql://u:p@host:5432/db']
    }

    def "defaults to the standard port when the URI omits it"() {
        expect:
        DatabaseUrlEnvironmentPostProcessor.convert('postgres://u:p@host/db')['spring.datasource.url']
                .contains('host:5432/db')
    }

    def "handles a password containing a colon"() {
        when:
        def result = DatabaseUrlEnvironmentPostProcessor.convert('postgres://user:pa:ss@host/db')

        then: "only the first colon separates the two"
        result['spring.datasource.username'] == 'user'
        result['spring.datasource.password'] == 'pa:ss'
    }

    def "returns nothing for input it should not touch"() {
        expect:
        DatabaseUrlEnvironmentPostProcessor.convert(candidate).isEmpty()

        where:
        candidate << [
                'jdbc:postgresql://localhost:5432/pesabook',
                'mysql://user:pass@host/db',
                'not a uri at all ::::',
                ''
        ]
    }
}
