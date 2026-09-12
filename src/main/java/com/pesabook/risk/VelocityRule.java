package com.pesabook.risk;

import com.pesabook.ledger.TransferRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Counts how often an account has sent money recently.
 *
 * A compromised account is usually drained quickly, so a burst of transfers in
 * a short window is worth a look even when each one is unremarkable on its own.
 */
@Component
public class VelocityRule implements RiskRule {

    private final TransferRepository transfers;
    private final Duration window;
    private final long reviewThreshold;
    private final long blockThreshold;

    public VelocityRule(TransferRepository transfers,
                        @Value("${pesabook.risk.velocity.window-minutes}") long windowMinutes,
                        @Value("${pesabook.risk.velocity.review-threshold}") long reviewThreshold,
                        @Value("${pesabook.risk.velocity.block-threshold}") long blockThreshold) {
        this.transfers = transfers;
        this.window = Duration.ofMinutes(windowMinutes);
        this.reviewThreshold = reviewThreshold;
        this.blockThreshold = blockThreshold;
    }

    @Override
    public String name() {
        return "velocity";
    }

    @Override
    public RiskSignal evaluate(RiskContext context) {
        Instant since = Instant.now().minus(window);
        long recent = transfers.countAttemptsFromAccountSince(context.sourceAccount(), since);

        // The count excludes the attempt being judged, so compare the count
        // this one would make.
        long including = recent + 1;

        if (including >= blockThreshold) {
            return new RiskSignal(name(), RiskDecision.BLOCK,
                    including + " transfers in " + window.toMinutes() + " minutes");
        }
        if (including >= reviewThreshold) {
            return new RiskSignal(name(), RiskDecision.REVIEW,
                    including + " transfers in " + window.toMinutes() + " minutes");
        }
        return RiskSignal.allow(name());
    }
}
