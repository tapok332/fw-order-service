-- Order ↔ Payment integration fields.
--
-- The actual payment lives in payment-service (own DB schema), but the order
-- needs enough of its lifecycle state to:
--   * Show payment status to the user without an extra service hop.
--   * Idempotently react to Kafka payment.completed / payment.failed events.
--   * Drive admin refund flow.
--
-- We do NOT duplicate the Stripe charge / refund identifiers — those are in
-- payment-service. Only `stripe_payment_intent_id` lives here, because it is
-- the contract handle returned to the frontend at order creation time.

ALTER TABLE orders
    ADD COLUMN payment_status            VARCHAR(50)              NOT NULL DEFAULT 'PENDING',
    ADD COLUMN stripe_payment_intent_id  VARCHAR(255),
    ADD COLUMN paid_at                   TIMESTAMP WITH TIME ZONE,
    ADD COLUMN failure_code              VARCHAR(100),
    ADD COLUMN failure_message           VARCHAR(1000);

-- Unique-when-present: one PaymentIntent maps to one Order.
CREATE UNIQUE INDEX uq_orders_stripe_intent
    ON orders (stripe_payment_intent_id)
    WHERE stripe_payment_intent_id IS NOT NULL;

CREATE INDEX idx_orders_payment_status ON orders (payment_status);

-- Backfill: existing rows are treated as legacy "no payment recorded"
-- (their effective status was always PENDING under the simulated provider,
-- so the default works correctly).
