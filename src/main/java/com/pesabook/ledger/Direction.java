package com.pesabook.ledger;

/**
 * Which side of the account an entry falls on.
 *
 * These accounts are liabilities: money the system owes the customer. A credit
 * increases what is owed and a debit decreases it, so a balance is credits
 * minus debits.
 */
public enum Direction {
    DEBIT,
    CREDIT
}
