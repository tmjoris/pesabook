package com.pesabook.ledger;

import com.pesabook.risk.RiskDecision;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * The only place entries are written.
 *
 * Every movement posts exactly two entries, a debit and a matching credit, in
 * one transaction. Nothing here updates or deletes an entry, which is what lets
 * the ledger be read back as a history rather than a current picture.
 */
@Service
public class LedgerService {

    private final AccountRepository accounts;
    private final TransferRepository transfers;
    private final LedgerEntryRepository entries;

    public LedgerService(AccountRepository accounts,
                         TransferRepository transfers,
                         LedgerEntryRepository entries) {
        this.accounts = accounts;
        this.transfers = transfers;
        this.entries = entries;
    }

    @Transactional
    public Account openAccount(String reference, String currency) {
        if (accounts.existsByReference(reference)) {
            throw new LedgerException("An account with reference " + reference + " already exists");
        }
        return accounts.save(new Account(reference, currency));
    }

    @Transactional(readOnly = true)
    public Account requireAccount(UUID id) {
        return accounts.findById(id)
                .orElseThrow(() -> new LedgerException("No account with id " + id));
    }

    @Transactional(readOnly = true)
    public long balanceOf(UUID accountId) {
        return accounts.balanceMinor(accountId);
    }

    /**
     * The account representing everything outside this ledger.
     *
     * Money has to enter the system from somewhere. In double entry it cannot
     * simply appear, so a deposit is a movement from an account standing for
     * the outside world. That account runs negative by design, and its balance
     * read back is how much this ledger currently owes outward.
     */
    public static final String EXTERNAL_ACCOUNT_REFERENCE = "external:settlement";

    @Transactional
    public Account externalAccount(String currency) {
        String reference = EXTERNAL_ACCOUNT_REFERENCE + ":" + currency;
        return accounts.findByReference(reference)
                .orElseGet(() -> accounts.save(new Account(reference, currency)));
    }

    /**
     * Brings money into the ledger from outside.
     *
     * The balance check is skipped for the external side only, because that
     * account is supposed to go negative. Every other movement still has to be
     * funded.
     */
    @Transactional
    public Transfer fundFromExternal(UUID target, long amountMinor, String currency) {
        Account external = externalAccount(currency);
        return postWithoutBalanceCheck(external.getId(), target, amountMinor,
                currency, RiskDecision.ALLOW, null);
    }

    /**
     * Records a transfer the risk check refused. No entries are written, so no
     * money moves, but the attempt is kept because a blocked attempt is exactly
     * the thing an investigator wants to see later.
     */
    @Transactional
    public Transfer recordWithoutPosting(UUID source, UUID target, long amountMinor,
                                         String currency, TransferStatus status,
                                         RiskDecision decision) {
        return transfers.save(new Transfer(source, target, amountMinor, currency,
                status, decision, null));
    }

    @Transactional
    public Transfer post(UUID source, UUID target, long amountMinor,
                         String currency, RiskDecision decision) {
        return post(source, target, amountMinor, currency, decision, null);
    }

    /**
     * Posts a balanced pair of entries and the transfer that owns them.
     *
     * @param reverses the transfer being reversed, or null for an ordinary movement
     */
    @Transactional
    public Transfer post(UUID source, UUID target, long amountMinor,
                         String currency, RiskDecision decision, UUID reverses) {

        // An ordinary movement has to be funded. A reversal does not, because
        // refusing one would leave money where it does not belong.
        boolean requireFunds = reverses == null;
        return write(source, target, amountMinor, currency, decision, reverses, requireFunds);
    }

    private Transfer postWithoutBalanceCheck(UUID source, UUID target, long amountMinor,
                                             String currency, RiskDecision decision,
                                             UUID reverses) {
        return write(source, target, amountMinor, currency, decision, reverses, false);
    }

    private Transfer write(UUID source, UUID target, long amountMinor, String currency,
                           RiskDecision decision, UUID reverses, boolean requireFunds) {

        if (amountMinor <= 0) {
            throw new LedgerException("A transfer must move a positive amount");
        }
        if (source.equals(target)) {
            throw new LedgerException("A transfer must move between two different accounts");
        }

        Account from = requireAccount(source);
        Account to = requireAccount(target);

        if (!from.getCurrency().equals(currency) || !to.getCurrency().equals(currency)) {
            throw new LedgerException(
                    "Both accounts must hold the transfer currency " + currency);
        }

        if (requireFunds && balanceOf(source) < amountMinor) {
            throw new LedgerException("Account " + from.getReference()
                    + " does not hold enough to move " + amountMinor);
        }

        // Flushed rather than merely saved, so a violation of the unique
        // constraint on reverses surfaces here where it can be caught and
        // turned into a clear answer, instead of at commit time where it would
        // escape as a server error.
        Transfer transfer = transfers.saveAndFlush(new Transfer(source, target, amountMinor,
                currency, TransferStatus.POSTED, decision, reverses));

        List<LedgerEntry> pair = List.of(
                new LedgerEntry(transfer.getId(), source, Direction.DEBIT, amountMinor, currency),
                new LedgerEntry(transfer.getId(), target, Direction.CREDIT, amountMinor, currency));

        long sum = pair.stream().mapToLong(LedgerEntry::signedAmount).sum();
        if (sum != 0) {
            // Unreachable while the pair above is built from one amount, and
            // kept because it is the invariant the whole design rests on.
            throw new LedgerException("Entries for a transfer must sum to zero, got " + sum);
        }

        entries.saveAll(pair);
        return transfer;
    }

    /**
     * Reverses a transfer by posting its mirror image.
     *
     * The original transfer and its entries are left exactly as they were. What
     * the reader sees afterwards is that something happened and was then undone,
     * which is the truth, rather than a ledger that claims it never happened.
     */
    @Transactional
    public Transfer reverse(UUID transferId) {
        Transfer original = transfers.findById(transferId)
                .orElseThrow(() -> new LedgerException("No transfer with id " + transferId));

        if (original.getStatus() != TransferStatus.POSTED) {
            throw new LedgerException("Only a posted transfer can be reversed, this one is "
                    + original.getStatus());
        }
        if (original.isReversal()) {
            throw new LedgerException("A reversal cannot itself be reversed");
        }

        // This check is a courtesy: it produces a clear message in the ordinary
        // case. It is not what makes the operation safe, because two callers can
        // both pass it before either has inserted. The unique constraint on
        // transfer.reverses is what actually enforces it, and the catch below is
        // how that surfaces as a sensible answer rather than a server error.
        if (transfers.existsByReverses(transferId)) {
            throw new LedgerException("Transfer " + transferId + " has already been reversed");
        }

        try {
            return post(original.getTargetAccount(), original.getSourceAccount(),
                    original.getAmountMinor(), original.getCurrency(),
                    RiskDecision.ALLOW, transferId);
        } catch (DataIntegrityViolationException e) {
            throw new LedgerException("Transfer " + transferId + " has already been reversed");
        }
    }

    @Transactional(readOnly = true)
    public List<LedgerEntry> entriesFor(UUID transferId) {
        return entries.findByTransferId(transferId);
    }

    @Transactional(readOnly = true)
    public Transfer requireTransfer(UUID transferId) {
        return transfers.findById(transferId)
                .orElseThrow(() -> new LedgerException("No transfer with id " + transferId));
    }

    /**
     * An account's entries oldest first, which is the order a statement reads
     * in and the only order a running balance makes sense in.
     */
    @Transactional(readOnly = true)
    public List<LedgerEntry> statementFor(UUID accountId) {
        requireAccount(accountId);
        return entries.findByAccountIdOrderByCreatedAtAscIdAsc(accountId);
    }

    @Transactional(readOnly = true)
    public List<Transfer> awaitingReview() {
        return transfers.findByStatusOrderByCreatedAtDesc(TransferStatus.HELD_FOR_REVIEW);
    }

    /**
     * Lets a held transfer through after a reviewer has looked at it.
     *
     * The funds are checked again here rather than trusted from when the hold
     * was placed. Time has passed, and the sender may have spent the money in
     * the meantime, so approving without rechecking would post a movement the
     * account can no longer cover.
     */
    @Transactional
    public Transfer release(UUID transferId, String reason) {
        Transfer held = requireTransfer(transferId);

        if (!held.isAwaitingReview()) {
            throw new LedgerException("Only a transfer awaiting review can be released, this one is "
                    + held.getStatus());
        }

        if (balanceOf(held.getSourceAccount()) < held.getAmountMinor()) {
            throw new LedgerException("The sending account no longer holds enough to move "
                    + held.getAmountMinor());
        }

        List<LedgerEntry> pair = List.of(
                new LedgerEntry(held.getId(), held.getSourceAccount(), Direction.DEBIT,
                        held.getAmountMinor(), held.getCurrency()),
                new LedgerEntry(held.getId(), held.getTargetAccount(), Direction.CREDIT,
                        held.getAmountMinor(), held.getCurrency()));

        if (pair.stream().mapToLong(LedgerEntry::signedAmount).sum() != 0) {
            throw new LedgerException("Entries for a released transfer must sum to zero");
        }

        entries.saveAll(pair);
        held.markReleased(reason);
        return transfers.save(held);
    }

    /**
     * Records that a reviewer refused a held transfer. No entries are written,
     * and the attempt stays in the history with the reason attached.
     */
    @Transactional
    public Transfer refuse(UUID transferId, String reason) {
        Transfer held = requireTransfer(transferId);

        if (!held.isAwaitingReview()) {
            throw new LedgerException("Only a transfer awaiting review can be refused, this one is "
                    + held.getStatus());
        }

        held.markRefused(reason);
        return transfers.save(held);
    }

    /**
     * Every entry ever written, summed. Because each transfer posts a debit and
     * an equal credit, the total across a healthy ledger is zero, whatever has
     * happened in between.
     */
    @Transactional(readOnly = true)
    public long totalOfAllEntries() {
        return entries.sumOfAllSignedAmounts();
    }
}
