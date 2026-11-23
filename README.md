# payment-service

Betalinger via Stripe Checkout (hosted), refunderinger og kvitteringer. Port **8084**, PostgreSQL `payments_db`.
Taler med resten via RabbitMQ (`cinema.events`).

## Kør lokalt med rigtig Stripe (testnøgler – standard)
1. Opret en gratis Stripe-konto, slå **Test mode** til og kopiér `sk_test_...` (Developers → API keys).
2. Installér Stripe CLI og videresend webhooks:
   ```
   stripe login
   stripe listen --forward-to localhost:8084/api/payments/webhook/stripe
   ```
   Kopiér den `whsec_...`, den skriver ud.
3. Sæt i Run Configuration (aldrig i Git):
   ```
   STRIPE_SECRET_KEY=sk_test_...
   STRIPE_WEBHOOK_SECRET=whsec_...
   ```
4. Betal med testkortet `4242 4242 4242 4242`, en vilkårlig fremtidig dato og CVC.

Servicen starter ikke, hvis Stripe er slået til, og nøglerne mangler (tydelig fejlbesked).

### Uden Stripe-konto (dev-mode)
`STRIPE_ENABLED=false`: ingen kald til Stripe. Simulér resultatet i stedet:
`POST /api/payments/dev/{bookingId}/succeed` eller `/fail`.

| Variabel | Standard | |
|---|---|---|
| `DB_HOST` / `DB_PORT` / `DB_NAME` | localhost / 5432 / payments_db | Tabeller oprettes af **Flyway** |
| `RABBITMQ_HOST` / `RABBITMQ_PORT` | localhost / 5672 | |
| `STRIPE_ENABLED` | true | |
| `STRIPE_SUCCESS_URL` / `STRIPE_CANCEL_URL` | localhost:5173/booking/... | `{BOOKING_ID}` erstattes |
| `STRIPE_CHECKOUT_VALID_FOR` | 30m | Stripe kræver mindst 30 minutter |

## Flow
1. `booking.created` → betaling `PENDING` (ingen Stripe-kald endnu).
2. Klienten kalder `GET /api/payments/booking/{bookingId}` → Stripe Checkout-link oprettes (gyldigt 30 min)
   og vises i `checkoutUrl`. 404 lige efter bookingen = eventet er ikke nået frem endnu, prøv igen.
3. Webhook `checkout.session.completed` → `COMPLETED` + `payment.completed` (Booking bekræfter).
   `checkout.session.expired` (det aktuelle link) / `async_payment_failed` → `FAILED` + `payment.failed`.
4. `booking.cancelled` → betalt: refundering (`REFUNDED` + `payment.refunded`). Ikke betalt: `CANCELLED`,
   og Checkout-siden lukkes i Stripe.
5. **Betaling efter annullering** (kunden betalte, lige da bookingen udløb): refunderes med det samme
   (`PAID_AFTER_CANCEL`), og der sendes kun `payment.refunded`.

Stripe-webhooks gemmes i `stripe_webhook_events`, så Stripes genforsøg ignoreres. Refunderinger sendes med en
idempotency key, så samme betaling aldrig refunderes to gange.

## Endpoints
`X-User-Id` (UUID) og `X-User-Role` sættes af gateway'en.

| Metode | Sti | Beskrivelse |
|---|---|---|
| GET | `/api/payments/booking/{bookingId}` | Betalingen + `checkoutUrl` (ejeren eller ADMIN) |
| GET | `/api/payments/me` | Mine betalinger |
| GET | `/api/payments?status=&limit=` | Alle betalinger – kræver `X-User-Role: ADMIN` |
| POST | `/api/payments/webhook/stripe` | Stripe webhooks (signaturen tjekkes) |
| POST | `/api/payments/dev/{bookingId}/succeed\|fail` | Kun i dev-mode |

## Events (`cinema.events`, envelope `{eventId, type, version, occurredAt, producer, correlationId, data}`)
Lytter på kø `payment-service.booking-events` (`booking.created`, `booking.cancelled`) – idempotent via
`processed_events`, dead-letter i `payment-service.dead-letter`.

Publicerer via outbox (correlationId = bookingId):

| Event | data |
|---|---|
| `payment.completed` | paymentId, bookingId, userId, amount, currency, receiptNumber, receiptUrl |
| `payment.failed` | paymentId, bookingId, userId, reason |
| `payment.refunded` | paymentId, refundId, bookingId, userId, amount, currency |

## Tests
`mvn verify` – unit-tests, `@WebMvcTest` og en integrationstest med Postgres og RabbitMQ i Testcontainers
(kræver Docker Desktop). Stripe er mocket i testene, så der skal ingen nøgler til.

## CI
`.github/workflows/ci.yml` kører testene og pusher `<DOCKERHUB_USERNAME>/payment-service:latest` til Docker Hub
på `main`. Kræver repo-secrets `DOCKERHUB_USERNAME` og `DOCKERHUB_TOKEN`. Stripe-nøglerne skal **ikke** i CI.

## Kubernetes (Docker Desktop)
`kubernetes/payment-service` (Deployment + Service) og `kubernetes/payment-postgres` (Postgres 17 med en
PersistentVolumeClaim på 1 Gi). Imaget hentes fra Docker Hub (`umairtafail/payment-service:latest`).
Forventer, at `rabbitmq` (Infrastructure-repoet) allerede kører i klusteret.

Stripe-nøglerne lægges i en Secret, der oprettes i hånden (aldrig i Git):
```
kubectl create secret generic stripe-keys \
  --from-literal=STRIPE_SECRET_KEY=sk_test_... \
  --from-literal=STRIPE_WEBHOOK_SECRET=whsec_...
kubectl apply -R -f kubernetes
stripe listen --forward-to localhost:30084/api/payments/webhook/stripe
```
Servicen er en **NodePort på 30084**, så Stripe CLI på din egen maskine kan levere webhooks. Når gateway'en
videresender `/api/payments/webhook/**`, kan den laves om til ClusterIP.
