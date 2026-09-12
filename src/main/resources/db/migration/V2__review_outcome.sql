-- A held transfer needs somewhere to record what a reviewer decided and when.
-- Without this the review queue is a dead end: something can be held and never
-- resolved either way.

alter table transfer
    add column reviewed_at    timestamptz null,
    add column review_reason  varchar(500) null;

-- Finding the queue is the common read on this table once reviews exist.
create index transfer_status_created_idx on transfer (status, created_at desc);
