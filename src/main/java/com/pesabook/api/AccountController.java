package com.pesabook.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pesabook.api.dto.AccountResponse;
import com.pesabook.api.dto.FundingRequest;
import com.pesabook.api.dto.OpenAccountRequest;
import com.pesabook.api.dto.StatementResponse;
import com.pesabook.api.dto.TransferResponse;
import com.pesabook.idempotency.IdempotencyService;
import com.pesabook.idempotency.IdempotentOutcome;
import com.pesabook.ledger.Account;
import com.pesabook.ledger.LedgerService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/v1/accounts")
public class AccountController {

    private final LedgerService ledger;
    private final PaymentService payments;
    private final IdempotencyService idempotency;
    private final ObjectMapper objectMapper;

    public AccountController(LedgerService ledger,
                             PaymentService payments,
                             IdempotencyService idempotency,
                             ObjectMapper objectMapper) {
        this.ledger = ledger;
        this.payments = payments;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ResponseEntity<AccountResponse> open(@Valid @RequestBody OpenAccountRequest request) {
        Account account = ledger.openAccount(request.reference(), request.currency());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(AccountResponse.of(account, 0L));
    }

    @GetMapping("/{id}")
    public ResponseEntity<AccountResponse> get(@PathVariable UUID id) {
        Account account = ledger.requireAccount(id);
        return ResponseEntity.ok(AccountResponse.of(account, ledger.balanceOf(id)));
    }

    /**
     * The account's entries with a running balance.
     *
     * This is what makes the append only ledger useful to a reader rather than
     * only to the code. Without it the history exists but nobody can see it.
     */
    @GetMapping("/{id}/statement")
    public ResponseEntity<StatementResponse> statement(@PathVariable UUID id) {
        return ResponseEntity.ok(payments.statement(id));
    }

    /**
     * Puts money into an account so there is something to move.
     *
     * Idempotent like every other endpoint that moves money, so a retried
     * deposit does not credit the account twice.
     */
    @PostMapping("/{id}/funding")
    public ResponseEntity<String> fund(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @PathVariable UUID id,
            @Valid @RequestBody FundingRequest request) {

        String canonicalBody = id + "|" + request.amountMinor() + "|" + request.currency();

        IdempotentOutcome outcome = idempotency.execute(idempotencyKey, canonicalBody, () -> {
            TransferResponse response = payments.fund(id, request.amountMinor(), request.currency());
            return new IdempotencyService.Work(201, serialise(response), response.id());
        });

        return ResponseEntity.status(outcome.httpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotent-Replay", Boolean.toString(outcome.replayed()))
                .body(outcome.body());
    }

    private String serialise(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise " + value.getClass(), e);
        }
    }
}
