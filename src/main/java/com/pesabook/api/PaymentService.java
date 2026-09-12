package com.pesabook.api;

import com.pesabook.api.dto.TransferRequest;
import com.pesabook.api.dto.TransferResponse;
import com.pesabook.ledger.LedgerService;
import com.pesabook.ledger.Transfer;
import com.pesabook.ledger.TransferStatus;
import com.pesabook.risk.RiskAssessment;
import com.pesabook.risk.RiskContext;
import com.pesabook.risk.RiskDecision;
import com.pesabook.risk.RiskEngine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
}
