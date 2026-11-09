# payment-service

Betalinger via Stripe hosted checkout + kvitteringer. Port **8084**, PostgreSQL `payments_db`.

## Dev-mode (standard)
`STRIPE_ENABLED=false`: ingen kald til Stripe. Simulér i stedet:
- `POST /api/payments/dev/{bookingId}/succeed`
- `POST /api/payments/dev/{bookingId}/fail`

## Rigtig Stripe (testnøgler)
```
STRIPE_ENABLED=true
STRIPE_SECRET_KEY=sk_test_...
STRIPE_WEBHOOK_SECRET=whsec_...      # fra: stripe listen --forward-to localhost:8084/api/payments/webhook/stripe
```

## Endpoints
| Metode | Sti | Beskrivelse |
|---|---|---|
| GET | `/api/payments/booking/{bookingId}` | Klienten henter `checkoutUrl` her |
| GET | `/api/payments/me` | Mine betalinger (`X-User-Id`) |
| POST | `/api/payments/webhook/stripe` | Stripe webhooks (signatur verificeres) |

## Events (når RabbitMQ-repoet er klar)
Skal lytte på `booking.created` (→ `createPaymentForBooking`) og `booking.cancelled` (→ `handleBookingCancelled`).
Skal publicere `payment.completed`, `payment.failed`, `payment.refunded`. Se `TODO (RabbitMQ)` i `PaymentService`.

Indtil da oprettes der ingen betalinger automatisk, så dev-endpoints'ene har intet at arbejde på.
