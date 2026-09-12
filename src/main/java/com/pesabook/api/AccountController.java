package com.pesabook.api;

import com.pesabook.api.dto.AccountResponse;
import com.pesabook.api.dto.OpenAccountRequest;
import com.pesabook.ledger.Account;
import com.pesabook.ledger.LedgerService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/v1/accounts")
public class AccountController {

    private final LedgerService ledger;

    public AccountController(LedgerService ledger) {
        this.ledger = ledger;
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
}
