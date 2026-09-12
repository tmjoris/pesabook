-- Accounts hold no balance column. A balance is derived by summing the entries
-- that reference the account, because a stored balance can drift from the
-- entries that are supposed to explain it.
create table account (
    id            uuid primary key,
    reference     varchar(64)  not null unique,
    currency      varchar(3)   not null,
    created_at    timestamptz  not null default now()
);

create table transfer (
    id               uuid primary key,
    source_account   uuid         not null references account (id),
    target_account   uuid         not null references account (id),
    amount_minor     bigint       not null,
    currency         varchar(3)   not null,
    status           varchar(16)  not null,
    risk_decision    varchar(16)  not null,
    reverses         uuid         null references transfer (id),
    created_at       timestamptz  not null default now(),

    constraint transfer_amount_positive     check (amount_minor > 0),
    constraint transfer_distinct_accounts   check (source_account <> target_account),

    -- A transfer can be reversed at most once. Enforced here rather than by
    -- reading before writing, because a read then write is not safe under
    -- concurrency.
    constraint transfer_reverses_once       unique (reverses)
);

create index transfer_source_created_idx on transfer (source_account, created_at desc);
create index transfer_target_idx on transfer (target_account);

-- Entries are append only. There is no update or delete path in the
-- application, and a correction is expressed as a further pair of entries.
create table ledger_entry (
    id            uuid primary key,
    transfer_id   uuid         not null references transfer (id),
    account_id    uuid         not null references account (id),
    direction     varchar(6)   not null,
    amount_minor  bigint       not null,
    currency      varchar(3)   not null,
    created_at    timestamptz  not null default now(),

    constraint entry_amount_positive check (amount_minor > 0),
    constraint entry_direction_valid check (direction in ('DEBIT', 'CREDIT'))
);

create index ledger_entry_account_idx on ledger_entry (account_id);
create index ledger_entry_transfer_idx on ledger_entry (transfer_id);

-- One row per idempotency key. The primary key is what makes two concurrent
-- requests carrying the same key race for a single insert, which is the whole
-- mechanism: the winner does the work and the loser is told to wait or is
-- served the winner's answer.
create table idempotency_record (
    idempotency_key     varchar(255) primary key,
    request_fingerprint varchar(64)  not null,
    status              varchar(16)  not null,
    response_status     integer      null,
    response_body       text         null,
    transfer_id         uuid         null references transfer (id),
    created_at          timestamptz  not null default now(),
    completed_at        timestamptz  null,

    constraint idempotency_status_valid check (status in ('IN_PROGRESS', 'COMPLETED'))
);

create index idempotency_created_idx on idempotency_record (created_at);
