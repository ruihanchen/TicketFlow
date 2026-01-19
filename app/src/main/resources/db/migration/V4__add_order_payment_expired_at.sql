-- Separates cart hold deadline (expired_at) from payment completion deadline.
--
-- expired_at was doing double duty: reaper used it to release CREATED orders,
-- confirmPayment() used it to reject late confirmations. A payment initiated
-- near expired_at and confirmed slightly after (gateway latency) would be
-- rejected(user charged, ticket refused).
--
-- payment_expired_at: NULL until CREATED->PAYING, set once, never modified.
-- PG16: nullable ADD COLUMN is metadata-only, no row rewrite.

ALTER TABLE orders ADD COLUMN payment_expired_at TIMESTAMPTZ NULL;