# pesabook

[![CI](https://github.com/tmjoris/pesabook/actions/workflows/ci.yml/badge.svg)](https://github.com/tmjoris/pesabook/actions/workflows/ci.yml)

A payments service that can be retried safely, keeps a ledger that can be
audited, undoes a mistake without pretending it never happened, and decides
whether a payment looks wrong before the money moves.

Java 21, Spring Boot 3.3, PostgreSQL, Redis, Flyway, Spock, Testcontainers.

## The problem

Two failures that look unrelated share a cause: a payment system that cannot say
with certainty what has already happened to a given instruction.

The first is the duplicate charge. A client sends a payment, the connection
drops before the response arrives, and the client or the person holding the
phone tries again. Without a way to recognise the second attempt as the same
intent, the money moves twice.

The second is the payment that reaches the wrong person. Recovering it means
reversing something that has already settled, and a system that corrects by
editing or deleting leaves nobody able to say what actually took place.

## How I know these are real

Among Kenyans who reported losing money through mobile money, **70.0 percent
lost it by sending to the wrong recipient**. That is the 2024 FinAccess
Household Survey, run jointly by the Central Bank of Kenya, the Kenya National
Bureau of Statistics and FSD Kenya, section 4.5 on page 57.

The same survey, page 58, asked what problems people meet using financial
services:

| Reported issue | Mobile money | Bank | SACCO |
| --- | --- | --- | --- |
| System downtime | 21.1% | 11.0% | 0.8% |
| Money lost | 9.8% | 1.5% | 2.2% |

Mobile money users report downtime at about twice the rate bank users do, and
losing money at roughly six times the rate. Those two rows are one story told
twice: downtime produces the timeout that produces the retry that produces the
duplicate.

On scale, the Central Bank of Kenya publishes the figures monthly. In July 2026
there were **221.07 million agent cash in and cash out transactions worth
KSh 728.7 billion**, across 575,400 active agents. That is roughly 7.1 million
transactions a day, so a race condition that fires rarely still fires
constantly. The figure covers agent cash in and cash out only, not person to
person or merchant payments, so the true total is larger.

The clearest evidence comes from the rail itself. M-PESA's published result
codes include **15, "Duplicate Detected"**, alongside 1 for insufficient funds
and 17 for internal failure. A payment network does not spend a code on
something hypothetical.

On fraud, GSMA's 2026 industry report puts worldwide fraud losses close to 500
billion dollars and says more than 30 percent of adults in Sub Saharan Africa
have received a scam or extortion message. CGAP's May 2026 survey work across
Kenya, Rwanda, Tanzania and Uganda found over 60 percent of users had received
a fraud attempt and **14 percent of Kenyan users actually lost money**, and that
those who did trusted their provider less afterwards.

## Why this design rather than another

Both halves already have measured results published by payment systems rather
than by vendors selling something.

Confirmation of Payee in the United Kingdom cut misdirected payment claims by
**53 percent** and now runs 1.9 million checks a day across more than 99 percent
of Faster Payments. Rabobank's equivalent in the Netherlands cut misdirected
payments by half within nine months. On the risk side, Visa's account to account
pilot caught **54 percent of fraudulent transactions the banks' own systems had
missed**, and EBA CLEARING's network level detection produced about a 35 percent
fall in fraud for early adopters on SEPA.

Those figures say the category of control works. They are not a claim about this
code, and the distinction matters enough to spell out. Confirmation of Payee
compares the recipient's **name** against the account before the payment leaves,
so the sender is told "that account belongs to John Kamau, not Jane Kamau". What
is implemented here is a `new-recipient` rule that flags a large first payment to
a party never paid before. That is a weaker control aimed at the same failure,
and the 53 percent does not transfer to it. A real payee check needs a name to
verify against, which means either a directory this service does not have or a
lookup against the rail it would sit in front of.

The idempotency half is settled practice rather than invention. Stripe applies a
key to every POST, stores the first response per key and replays it on retry,
including the 500s, and rejects a key that returns with different parameters.
Square documents the same semantics. Modern Treasury publishes eight guarantees
for a ledger, among them that credits always equal debits and that records are
archived rather than deleted. Square's engineering team built an immutable
double entry ledger after, in their words, "occasionally observing
inconsistencies in the data, resulting in customer inquiries and delayed
deposits".

### What I am not claiming

Nobody publishes a duplicate charge rate or a failed transaction rate for
M-PESA or for Kenyan mobile money. I looked across regulator publications,
CGAP, GSMA, FinAccess and several search engines, and did not find one. The
argument above stands on what is measured: misdirected payments, downtime,
volume, and a result code the operator saw fit to define.

There is also a limit to what the duplicate half can do on its own. A retry is
recognised because the client sends the same key twice. A client that generates
a fresh key for every attempt will be charged twice and this service cannot tell
the difference, because from the outside those are two different instructions.
What is implemented here is the server half of a contract with two sides. The
header is mandatory rather than optional so the other half has to exist.

CGAP is also blunt that these controls cost something. Delays frustrate people,
payee verification produces warning fatigue, and aggressive checks exclude
legitimate users. The new recipient rule here fires only above an amount
threshold for that reason. A warning that fires constantly is one people learn
to click through.

## The design

### Idempotency

Every POST that moves money requires an `Idempotency-Key` header. It is required
rather than optional, because the unsafe path should not be the one a caller
gets by forgetting something.

The claim on a key is an insert on a primary key, not a read followed by a
write. Two concurrent requests both attempt the insert, the database lets
exactly one through, and the loser learns it lost from the constraint. Checking
first and inserting afterwards leaves a window where both callers believe they
are first, and that window is exactly where a retry over a slow mobile network
lands.

The insert is written as `insert ... on conflict do nothing` rather than through
`save()`. Spring Data decides between insert and merge by asking whether the
entity looks new, and an entity whose id the application assigns never looks
new, so `save()` issues a select followed by an update and quietly overwrites
the record belonging to whoever arrived first. That is the double charge the key
exists to prevent, arriving through the mechanism meant to stop it. A mocked
repository cannot exhibit it, which is why the claim is covered against a real
PostgreSQL rather than a stand in.

What a caller gets back:

| Situation | Response |
| --- | --- |
| New key | the work runs, `201`, `Idempotent-Replay: false` |
| Same key, same body, first attempt finished | the stored response, `Idempotent-Replay: true` |
| Same key, same body, first attempt still running | `409` with `Retry-After` |
| Same key, different body | `422`, and nothing runs |
| No key | `400` |

The different body case is refused rather than served the old answer because it
is a client bug, not a retry. Quietly replaying would tell a caller that the
amount they last sent had moved, when the amount they first sent had moved.

### The ledger

Entries are append only. `LedgerEntry` has no setter and nothing in the
application updates or deletes one. Every transfer writes exactly two entries, a
debit and an equal credit, inside one transaction, and the pair is checked to
sum to zero before it is written.

Balances are derived by summing entries rather than kept in a column. A stored
balance is a second copy of something the entries already say, and two copies of
one fact drift.

The whole ledger sums to zero, and one of the integration specs asserts exactly
that after a sequence of transfers and a reversal. If it ever stops holding,
money has been created or destroyed.

### Reversals

Reversing posts the mirror image of the original as a new transfer. The original
transfer and its entries are untouched. What a reader sees afterwards is that
something happened and was then undone, which is the truth, rather than a ledger
claiming it never happened.

A transfer can be reversed once. That is enforced by a unique constraint on
`transfer.reverses`, not by reading before writing, so two operators hitting the
button at the same moment cannot both succeed. The code checks too, but only to
produce a clearer message. The constraint is what makes it safe.

A reversal is allowed to take an account negative. Refusing would leave money
sitting where it does not belong because the recipient had already spent it.

### Risk

Three rules run before anything is written, and the strictest answer wins.
**Velocity** counts recent transfers from the sending account. A compromised
account tends to be drained quickly, so a burst is worth attention even when
each payment looks ordinary alone.

**Amount anomaly** has a flat ceiling and a comparison against the account's own
recent history. The comparison uses the median rather than the mean, because one
previous large payment would pull a mean up far enough to hide the next one.
Accounts with fewer than five previous transfers are left alone, since there is
no normal to compare against yet.

**New recipient** flags a sizeable first payment to a party never paid before.
This is aimed directly at the 70.0 percent figure above.

A transfer that is held or blocked writes no entries, so no balance moves, but
the attempt is still recorded with the reasons attached. A refused attempt is
one of the more useful things in a history.

## The API

| Method | Path | Idempotent | Purpose |
| --- | --- | --- | --- |
| POST | `/v1/accounts` | no | open an account |
| GET | `/v1/accounts/{id}` | | the account and its balance |
| POST | `/v1/accounts/{id}/funding` | yes | bring money in from outside the ledger |
| GET | `/v1/accounts/{id}/statement` | | every entry with a running balance |
| POST | `/v1/transfers` | yes | send money |
| GET | `/v1/transfers/{id}` | | one transfer and its status |
| GET | `/v1/transfers?status=HELD_FOR_REVIEW` | | the review queue |
| POST | `/v1/transfers/{id}/decision` | yes | approve or refuse a held transfer |
| POST | `/v1/transfers/{id}/reversals` | yes | undo a posted transfer |
| GET | `/actuator/health` | | liveness and readiness |
| GET | `/docs` | | Swagger UI, every endpoint with a send button |

The statement endpoint is what makes the append only ledger useful to a person
rather than only to the code. Its closing balance is computed by summing the
same entries the account balance is summed from, so the two cannot disagree.

Approving a held transfer rechecks the funds rather than trusting the check from
when the hold was placed. Time passes while something sits in a queue, and the
sender may have spent the money in between. A spec covers exactly that.

## Trying it without cloning anything

The API is deployed, and Swagger UI at `/docs` lists every endpoint with its
schema and a button that actually sends the request.

Two things to know before you click. The free instance sleeps after fifteen
minutes, so the first request takes roughly fifty seconds while the container
starts, and the database wakes alongside it. A request that appears to hang is
almost always this. Second, the data is public and anyone can write to it, so
treat it as a sandbox rather than evidence of anything.

The shortest path to seeing the point of the project:

```
BASE=https://pesabook.onrender.com

# open two accounts
ALICE=$(curl -s -X POST $BASE/v1/accounts -H 'Content-Type: application/json' \
  -d '{"reference":"alice-'$RANDOM'","currency":"KES"}' | jq -r .id)
BOB=$(curl -s -X POST $BASE/v1/accounts -H 'Content-Type: application/json' \
  -d '{"reference":"bob-'$RANDOM'","currency":"KES"}' | jq -r .id)

# put money into the first one
curl -s -X POST $BASE/v1/accounts/$ALICE/funding \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" \
  -d '{"amountMinor":100000,"currency":"KES"}' > /dev/null

# send a payment, keeping the key
KEY=$(uuidgen)
curl -s -X POST $BASE/v1/transfers -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $KEY" \
  -d "{\"sourceAccount\":\"$ALICE\",\"targetAccount\":\"$BOB\",\"amountMinor\":2500,\"currency\":\"KES\"}" \
  -D - -o /dev/null | grep -i idempotent-replay

# send the very same request again
curl -s -X POST $BASE/v1/transfers -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $KEY" \
  -d "{\"sourceAccount\":\"$ALICE\",\"targetAccount\":\"$BOB\",\"amountMinor\":2500,\"currency\":\"KES\"}" \
  -D - -o /dev/null | grep -i idempotent-replay

# 97500, not 95000
curl -s $BASE/v1/accounts/$ALICE | jq .balanceMinor
```

The header reads `Idempotent-Replay: false` the first time and `true` the
second, and the balance moves once. Change the amount while keeping the key and
the API returns 422 rather than quietly doing something different from what the
caller last asked for.

`GET /v1/accounts/{id}/statement` shows the entries behind that balance, which
is the append only ledger rather than a summary of one.

## Running it locally

```
docker compose up --build
```

Then:

```
# open two accounts
curl -X POST localhost:8080/v1/accounts \
  -H 'Content-Type: application/json' \
  -d '{"reference":"alice","currency":"KES"}'

# send money, and note the key
curl -X POST localhost:8080/v1/transfers \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 4f1d8c2a-1e0b-4a9c-9f3e-2b6d5a7c8e10' \
  -d '{"sourceAccount":"...","targetAccount":"...","amountMinor":250000,"currency":"KES"}'

# send it again with the same key and watch the header
#   Idempotent-Replay: true, and the balance does not move
```

## Tests

```
./gradlew unitTest   # no Docker needed
./gradlew test       # everything, needs a Docker daemon
```

`unitTest` exists because the integration specs start PostgreSQL and Redis
through Testcontainers. On a machine without Docker, run `unitTest` locally and
let CI run the rest. CI is the authority on the concurrency spec.

The spec worth reading first is `IdempotencyConcurrencySpec`. It fires twenty
simultaneous copies of one request at a running server and asserts that exactly
one did the work, that every other attempt was replayed or told to wait, that
the balance moved once, and that the ledger still sums to zero.

108 specs pass. 74 of them need no Docker.

## Known gaps

There is no authentication. Every endpoint is open, which is fine for something
demonstrating ledger and idempotency behaviour and would not be fine anywhere
else. In particular the decision endpoint should know which reviewer made a
call, and right now it only records their reason.

Idempotency records are never pruned. Stripe expires keys after 24 hours; this
keeps them forever, so the table grows without bound.

The review queue is unpaginated and unfiltered beyond status. That is fine for a
queue of tens and wrong for a queue of thousands.

Redis holds a short lived in flight marker for each key, taken before the
database is touched, so a retry arriving mid flight is turned away without
spending a round trip on it. It is deliberately only a fast path. A caller it
lets through still has to win the insert, and when Redis is unreachable the gate
lets everyone through and PostgreSQL enforces the rule exactly as before. Losing
the cache costs throughput and cannot make the system wrong, which is the
property worth protecting when something sits in front of a rule about money.
Its health indicator is off by default for the same reason: a cache should not
decide whether an instance is considered live.

The risk thresholds are reasoned rather than fitted. They are configurable
because the right values depend on the corridor and cannot be derived from
first principles, and nothing here has been tuned against real fraud data.

The deployed instance has no authentication and its data is public. That is
fine for something demonstrating ledger and idempotency behaviour and would not
be fine anywhere else.## Sources

- 2024 FinAccess Household Survey, Central Bank of Kenya, KNBS and FSD Kenya, sections 4.5 and 4.6: https://www.centralbank.go.ke/wp-content/uploads/2024/12/2024-FINACCESS-HOUSEHOLD-SURVEY-MAIN-REPORT.pdf
- Central Bank of Kenya mobile payments statistics: https://www.centralbank.go.ke/national-payments-system/mobile-payments/
- GSMA State of the Industry Report on Mobile Money 2026: https://www.gsma.com/sotir/
- CGAP, solutions to protect consumers from fraud in digital finance, May 2026: https://www.cgap.org/research/publication/solutions-to-protect-consumers-fraud-in-digital-finance
- M-PESA Daraja result codes, from the documentation as archived before the portal moved behind sign in: https://web.archive.org/web/20210612172916/https://developer.safaricom.co.ke/docs
- Stripe, idempotent requests: https://docs.stripe.com/api/idempotent_requests
- Stripe engineering, designing robust and predictable APIs with idempotency: https://stripe.com/blog/idempotency
- Square, idempotency: https://developer.squareup.com/docs/build-basics/common-api-patterns/idempotency
- Square engineering, Books, an immutable double entry accounting database service: https://developer.squareup.com/blog/books-an-immutable-double-entry-accounting-database-service/
- Modern Treasury, ledger guarantees: https://docs.moderntreasury.com/ledgers/docs/ledgers-guarantees
