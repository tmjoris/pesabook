package com.pesabook.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pesabook.api.dto.TransferRequest;
import com.pesabook.api.dto.TransferResponse;
import com.pesabook.idempotency.IdempotencyService;
import com.pesabook.idempotency.IdempotentOutcome;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/v1/transfers")
public class TransferController {

    private final PaymentService payments;
    private final IdempotencyService idempotency;
    private final ObjectMapper objectMapper;

    public TransferController(PaymentService payments,
                              IdempotencyService idempotency,
                              ObjectMapper objectMapper) {
        this.payments = payments;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
    }

    /**
     * Creates a transfer, at most once per idempotency key.
     *
     * The header is required rather than optional. Making it optional would mean
     * the unsafe path is the one a caller gets by forgetting something, and on
     * an endpoint that moves money the default should be the safe one.
     */
    @PostMapping
    public ResponseEntity<String> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {

        String canonicalBody = serialise(request);

        IdempotentOutcome outcome = idempotency.execute(idempotencyKey, canonicalBody, () -> {
            TransferResponse response = payments.transfer(request);
            return new IdempotencyService.Work(201, serialise(response), response.id());
        });

        return ResponseEntity.status(outcome.httpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                // Lets a caller tell a fresh result from a stored one, which is
                // useful when reconciling a client that retried.
                .header("Idempotent-Replay", Boolean.toString(outcome.replayed()))
                .body(outcome.body());
    }

    @PostMapping("/{id}/reversals")
    public ResponseEntity<String> reverse(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @PathVariable UUID id) {

        IdempotentOutcome outcome = idempotency.execute(idempotencyKey, id.toString(), () -> {
            TransferResponse response = payments.reverse(id);
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
