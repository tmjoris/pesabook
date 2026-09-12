package com.pesabook.risk;

import com.pesabook.ledger.TransferRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Flags an amount that is out of character for the account.
 *
 * Two separate concerns. A flat ceiling catches anything large in absolute
 * terms. A comparison against the account's own recent history catches an
 * amount that is modest in absolute terms but unlike anything this account has
 * sent before, which is the shape a drained account tends to have.
 *
 * The median is used rather than the mean because one previous large transfer
 * would drag a mean up far enough to hide the next one.
 */
@Component
public class AmountAnomalyRule implements RiskRule {

    private static final int SAMPLE_SIZE = 20;
    private static final int MINIMUM_HISTORY = 5;

    private final TransferRepository transfers;
    private final long reviewCeilingMinor;
    private final long multipleOfMedian;

    public AmountAnomalyRule(TransferRepository transfers,
                             @Value("${pesabook.risk.amount.review-minor}") long reviewCeilingMinor,
                             @Value("${pesabook.risk.amount.multiple-of-median}") long multipleOfMedian) {
        this.transfers = transfers;
        this.reviewCeilingMinor = reviewCeilingMinor;
        this.multipleOfMedian = multipleOfMedian;
    }

    @Override
    public String name() {
        return "amount-anomaly";
    }

    @Override
    public RiskSignal evaluate(RiskContext context) {
        if (context.amountMinor() >= reviewCeilingMinor) {
            return new RiskSignal(name(), RiskDecision.REVIEW,
                    "amount " + context.amountMinor() + " is at or above the ceiling "
                            + reviewCeilingMinor);
        }

        List<Long> history = transfers.recentAmountsFromAccount(
                context.sourceAccount(), SAMPLE_SIZE);

        // Without enough history there is no normal to compare against, and
        // guessing one would flag every new account's first few transfers.
        if (history.size() < MINIMUM_HISTORY) {
            return RiskSignal.allow(name());
        }

        long median = medianOf(history);
        if (median > 0 && context.amountMinor() > median * multipleOfMedian) {
            return new RiskSignal(name(), RiskDecision.REVIEW,
                    "amount " + context.amountMinor() + " is more than " + multipleOfMedian
                            + " times the recent median of " + median);
        }

        return RiskSignal.allow(name());
    }

    private static long medianOf(List<Long> amounts) {
        List<Long> sorted = new ArrayList<>(amounts);
        sorted.sort(Long::compareTo);
        int middle = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(middle);
        }
        return (sorted.get(middle - 1) + sorted.get(middle)) / 2;
    }
}
