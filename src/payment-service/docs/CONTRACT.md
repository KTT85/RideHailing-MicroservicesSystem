# Payment contract v1

Based on instructor specification sections 4, 7 (DS03), 9 and the group's
`ke_hoach.md`. This is the implemented Payment contract for Trip/Notification to
adopt and integration-test; those services are still skeletons in this checkout.

## Ownership and trust

Trip owns fare, trip state, passenger/assigned-driver authorization and the Saga.
Payment never reads another service's database. Only trusted backend producers
may publish commands. RabbitMQ authenticates connections using environment-based
credentials. Restrict broker access/vhost permissions before sharing a deployment;
local RabbitMQ management publishing is a test harness, not a passenger API.

Trip must validate: PAY only for the owner, TRANSFER, PENDING_PAYMENT; cash
confirmation only by the assigned driver, CASH, COMPLETED; REFUND only from an
accepted cancellation Saga before STARTING. No client-supplied amount is trusted.
Trip checks these rules; Payment checks its own immutable values and money states.

## Transport

- Durable topic exchange: `ride.events`.
- Routing key is the exact `eventType` (case-sensitive).
- Payment's durable input queue: `payment.commands`.
- Dedicated retry exchange: `payment.delivery` (direct).
- Retry queue/key: `payment.retry`, TTL 5000 ms, dead-letters to `payment.commands`.
- Dead-letter queue/key: `payment.dlq`. Invalid/conflicting/oversized messages go
  there immediately; processing errors use a bounded retry counter.
- Result consumers MUST declare their own durable queue and bind result routing
  keys before the demo. Broker confirmation without a route is not delivery.
- Persistent messages, manual transaction boundary with container AUTO ACK after
  service commit; publisher requires broker confirm AND absence of mandatory return.
- At-least-once delivery. Republished outbox event keeps its eventId. Consumers must
  deduplicate and guard transitions, including PaymentSucceeded arriving late after
  cancellation or after PaymentRefunded. Do not order by wall-clock timestamps.

Envelope (UUIDs are canonical lowercase strings, timestamps UTC ISO-8601):

```json
{
  "eventId": "dfedc65f-c1d3-4e5a-998e-82227271d05d",
  "eventType": "PaymentRequested",
  "correlationId": "dfedc65f-c1d3-4e5a-998e-82227271d05d",
  "timestamp": "2026-10-16T00:00:00Z",
  "payload": {
    "tripId": "59c36866-d083-4c4f-95f5-29f58375074b",
    "passengerId": "455c5f5e-785c-4112-af43-8aa0a145b21c",
    "method": "TRANSFER",
    "amount": 50000,
    "currency": "VND",
    "idempotencyKey": "59c36866-d083-4c4f-95f5-29f58375074b:PAY"
  }
}
```

| Input event | Method | Stable key | Behavior |
|---|---|---|---|
| PaymentRequested | TRANSFER | tripId:PAY | Create one pending PAY |
| PaymentRefundRequested | TRANSFER | tripId:REFUND | Full refund, waits for successful PAY |
| CashPaymentConfirmationRequested | CASH | tripId:CONFIRM_CASH | Record driver's confirmed collection |
| TripCompleted | CASH | Not required | Create PENDING only if absent; never downgrade SUCCESS |
| TripCompleted | TRANSFER | Not required | Deduplicate and ignore; never charge again |

All inputs include tripId/passengerId/method/amount/currency, including TripCompleted.
Amounts are positive DECIMAL(19,2), VND only in V1. Changing amount/method/passenger
for an existing trip is a conflict. Retry uses the same key and operation row.

## Results

Output envelope uses the same fields. Payload includes tripId, passengerId,
paymentId, operationId, operationType, idempotencyKey, method, amount, currency,
status (payment state), operationStatus and refundedAmount.

| Result | Meaning / Trip responsibility |
|---|---|
| PaymentSucceeded | PAY collected or CASH confirmed; inspect method; advance only if trip still eligible |
| PaymentFailed | Definitive PAY rejection; Trip releases driver through Saga, no refund |
| PaymentRefunded | Full refund confirmed; Trip may finish cancellation once release is also confirmed |
| PaymentRefundFailed | Refund exhausted automatic retries / prerequisite not resolved; keep Saga unresolved |
| PaymentReviewRequired | PAY retries exhausted without final collection result; keep trip unresolved and reconcile |

`PaymentReviewRequired` makes the plan's MANUAL_REVIEW behavior explicit. Trip and
Notification must add this result to their contract before end-to-end integration.
Do not interpret a missing payment row or timeout as proof PAY failed. A command
may still be in Trip outbox or RabbitMQ. Internal read returns 404 if not yet created.

## Consistency and recovery

MySQL payment row lock serializes PAY/REFUND/CASH transitions. Unique constraints
enforce one payment per trip and one operation per type. Event dedup insertion,
operation acceptance and new payment commit together. Simulator money changes,
operation result and result outbox commit together. No real bank is contacted.

Payment statuses: PENDING, SUCCESS, FAILED, REFUND_PENDING, REFUNDED, REFUND_FAILED.
Operation statuses: PENDING, SUCCESS, FAILED, MANUAL_REVIEW (`PROCESSING` is reserved,
not used: no durable in-progress claim is needed for the atomic local simulator).
Exponential operation/outbox backoff starts at PAYMENT_RETRY_SECONDS, capped at
3600 seconds. Default max attempts: 5. Refund-before-PAY is retried, never treated
as refunded. After exhaustion, authorized operator resumes the same row through
the admin API after correcting the cause. Ordinary restart needs no manual edits.

Workers scan persistent pending rows after restart. Crash before commit rolls
back; crash after commit leaves the outbox. Crash after broker ACK before marking
PUBLISHED can resend the same event; the receiver must deduplicate it.

## REST

| Method/path | Authentication |
|---|---|
| GET /payments/by-trip/{tripId} | User HS256 JWT, owner PASSENGER or ADMIN |
| GET /internal/payments/by-trip/{tripId} | X-Service-Token = PAYMENT_INTERNAL_TOKEN, for Trip reconciliation |
| POST /admin/payments/operations/{id}/retry | ADMIN JWT; only MANUAL_REVIEW rows |
| POST /admin/payments/outbox/{eventId}/retry | ADMIN JWT; only MANUAL_REVIEW rows |
| GET /actuator/health | Public infrastructure health |

JWT_SECRET and JWT_ISSUER must match User. Public driver reads go through Trip,
which knows the assignment; Payment does not store driver ownership. Admin roles
are provisioned separately, never through public registration. Retry endpoints
return 202 and cannot change an amount or retry already successful operations.

## Limits

V1 runs one Payment instance/publisher. MySQL and RabbitMQ are single points of
failure; outages delay work. Health reflects broker/database connectivity, while
MANUAL_REVIEW in the read API/SQL reflects stuck business work. Rabbit classic
queue dead-letter transfer itself is not an exactly-once guarantee. A real payment
provider would require provider idempotency keys, signed callbacks and reconciliation;
the atomic simulator is intentionally scoped to the course prototype.
