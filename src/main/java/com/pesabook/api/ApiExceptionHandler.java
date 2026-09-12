package com.pesabook.api;

import com.pesabook.idempotency.IdempotencyConflictException;
import com.pesabook.idempotency.IdempotencyInProgressException;
import com.pesabook.ledger.LedgerException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@RestControllerAdvice
public class ApiExceptionHandler {

    /**
     * A key reused with a different body. 422 rather than 409, matching what
     * Stripe returns, because the request is understood and refused rather than
     * in conflict with the current state.
     */
    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ApiError> onConflict(IdempotencyConflictException e) {
        return ResponseEntity.unprocessableEntity()
                .body(ApiError.of("idempotency_key_reused", e.getMessage()));
    }

    /**
     * A request holding this key is still running. 409 with Retry-After, so a
     * well behaved client waits rather than hammering.
     */
    @ExceptionHandler(IdempotencyInProgressException.class)
    public ResponseEntity<ApiError> onInProgress(IdempotencyInProgressException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header("Retry-After", "1")
                .body(ApiError.of("idempotency_key_in_progress", e.getMessage()));
    }

    @ExceptionHandler(LedgerException.class)
    public ResponseEntity<ApiError> onLedger(LedgerException e) {
        return ResponseEntity.unprocessableEntity()
                .body(ApiError.of("ledger_rejected", e.getMessage()));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiError> onMissingHeader(MissingRequestHeaderException e) {
        return ResponseEntity.badRequest()
                .body(ApiError.of("missing_header", e.getHeaderName() + " is required"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> onInvalid(MethodArgumentNotValidException e) {
        List<String> details = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .toList();
        return ResponseEntity.badRequest()
                .body(ApiError.of("invalid_request", "The request failed validation", details));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> onIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
                .body(ApiError.of("invalid_request", e.getMessage()));
    }

    /**
     * A safety net for a constraint the application also checks in code. If one
     * is reached anyway it means two callers raced, and the right answer is that
     * the state conflicts rather than that the server broke.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> onConstraint(DataIntegrityViolationException e) {
        return ResponseEntity.unprocessableEntity()
                .body(ApiError.of("constraint_violated",
                        "The request conflicts with something already recorded"));
    }
}
