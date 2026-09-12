package com.pesabook.risk;

import com.pesabook.ledger.TransferRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Flags a sizeable first payment to someone the sender has never paid.
 *
 * This is the case the 2024 FinAccess Household Survey measures: among Kenyans
 * who lost money through mobile money, 70.0 percent lost it by sending to the
 * wrong recipient. A first payment to a new party is where that happens, and it
 * is also the shape of a social engineering scam.
 *
 * The amount threshold matters. Flagging every first payment would flag most
 * ordinary activity, and a warning that fires constantly is one people learn to
 * dismiss.
 */
@Component
public class NewRecipientRule implements RiskRule {

    private final TransferRepository transfers;
    private final long reviewAboveMinor;

    public NewRecipientRule(TransferRepository transfers,
                            @Value("${pesabook.risk.new-recipient.review-minor}") long reviewAboveMinor) {
        this.transfers = transfers;
        this.reviewAboveMinor = reviewAboveMinor;
    }

    @Override
    public String name() {
        return "new-recipient";
    }

    @Override
    public RiskSignal evaluate(RiskContext context) {
        if (context.amountMinor() < reviewAboveMinor) {
            return RiskSignal.allow(name());
        }

        long previous = transfers.countPostedBetween(
                context.sourceAccount(), context.targetAccount());

        if (previous == 0) {
            return new RiskSignal(name(), RiskDecision.REVIEW,
                    "first payment to this recipient, and " + context.amountMinor()
                            + " is at or above " + reviewAboveMinor);
        }

        return RiskSignal.allow(name());
    }
}
