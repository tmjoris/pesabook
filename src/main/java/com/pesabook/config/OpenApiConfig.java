package com.pesabook.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI pesabookOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("pesabook")
                        .version("0.1.0")
                        .description("""
                                A payments service that stays correct when clients retry.

                                Every POST that moves money requires an `Idempotency-Key` header.
                                Send the same key twice with the same body and the second call
                                returns the stored response rather than moving money again, with
                                `Idempotent-Replay: true` on the response. Send the same key with a
                                different body and the request is refused, because that is a client
                                bug rather than a retry.

                                To try it: open an account, open a second one, fund the first
                                through POST /v1/accounts/{id}/funding, then send a transfer. Send
                                the transfer a second time with the same key and watch the balance
                                stay where it was.

                                Balances are summed from an append only ledger rather than stored,
                                so GET /v1/accounts/{id}/statement is the audit trail rather than a
                                summary of one.
                                """)
                        .license(new License().name("MIT")));
    }
}
