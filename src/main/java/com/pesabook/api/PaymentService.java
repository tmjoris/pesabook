package com.pesabook.api;

import com.pesabook.api.dto.DecisionRequest;
import com.pesabook.api.dto.LedgerEntryResponse;
import com.pesabook.api.dto.StatementResponse;
import com.pesabook.api.dto.TransferRequest;
import com.pesabook.api.dto.TransferResponse;
import com.pesabook.ledger.Account;
import com.pesabook.ledger.LedgerEntry;
import com.pesabook.ledger.LedgerService;
import com.pesabook.ledger.Transfer;
import com.pesabook.ledger.TransferStatus;
import com.pesabook.risk.RiskAssessment;
import com.pesabook.risk.RiskContext;
import com.pesabook.risk.RiskDecision;
import com.pesabook.risk.RiskEngine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Decides, then moves.
 *
 * The risk check runs before any entry is written, so a blocked transfer never
 * touches a balance. The attempt is still recorded, because an attempt that was
 * refused is one of the more interesting things in the history.
 */
@Service
public class PaymentService {

    private final RiskEngine riskEngine;
    private final LedgerService ledger;

    public PaymentService(RiskEngine riskEngine, LedgerService ledger) {
        this.riskEngine = riskEngine;
        this.ledger = ledger;
    }

    @Transactional
    public TransferResponse transfer(TransferRequest request) {

        RiskContext context = new RiskContext(
                request.sourceAccount(),
                request.targetAccount(),
                request.amountMinor(),
                request.currency());

        RiskAssessment assessment = riskEngine.assess(context);

        if (assessment.decision() != RiskDecision.ALLOW) {
            TransferStatus status = assessment.decision() == RiskDecision.BLOCK
                    ? TransferStatus.BLOCKED
                    : TransferStatus.HELD_FOR_REVIEW;

            Transfer held = ledger.recordWithoutPosting(
                    request.sourceAccount(), request.targetAccount(),
                    request.amountMinor(), request.currency(),
                    status, assessment.decision());

            return TransferResponse.of(held, assessment);
        }

        Transfer posted = ledger.post(
                request.sourceAccount(), request.targetAccount(),
                request.amountMinor(), request.currency(), RiskDecision.ALLOW);

        return TransferResponse.of(posted, assessment);
    }

    @Transactional
    public TransferResponse reverse(UUID transferId) {
        Transfer reversal = ledger.reverse(transferId);
        return TransferResponse.of(reversal, null);
    }

    @Transactional(readOnly = true)
    public TransferResponse get(UUID transferId) {
        return TransferResponse.of(ledger.requireTransfer(transferId), null);
    }

    @Transactional(readOnly = true)
    public List<TransferResponse> awaitingReview() {
        return ledger.awaitingReview().stream()
                .map(t -> TransferResponse.of(t, null))
                .toList();
    }

    /**
     * Applies a reviewer's verdict to a held transfer.
     *
     * Approving posts the entries that were withheld, after rechecking the
     * funds, because the hold may have sat in a queue while the sender spent
     * the money.
     */
    @Transactional
    public TransferResponse decide(UUID transferId, DecisionRequest decision) {
        Transfer resolved = decision.isApproval()
                ? ledger.release(transferId, decision.reason())
                : ledger.refuse(transferId, decision.reason());
        return TransferResponse.of(resolved, null);
    }

    @Transactional(readOnly = true)
    public StatementResponse statement(UUID accountId) {
        Account account = ledger.requireAccount(accountId);
        List<LedgerEntry> entries = ledger.statementFor(accountId);

        List<StatementResponse.StatementLine> lines = new ArrayList<>(entries.size());
        long running = 0;
        for (LedgerEntry entry : entries) {
            running += entry.signedAmount();
            lines.add(new StatementResponse.StatementLine(
                    LedgerEntryResponse.of(entry), running));
        }

        return new StatementResponse(account.getId(), account.getReference(),
                account.getCurrency(), running, lines);
    }

    /**
     * Brings money into the ledger so there is something to move.
     *
     * The risk rules do not run here. This represents a deposit arriving from
     * outside the service rather than one customer paying another, and the
     * interesting question for a deposit is reconciliation, not fraud scoring.
     */
    @Transactional
    public TransferResponse fund(UUID accountId, long amountMinor, String currency) {
        Transfer funded = ledger.fundFromExternal(accountId, amountMinor, currency);
        return TransferResponse.of(funded, null);
    }
}
