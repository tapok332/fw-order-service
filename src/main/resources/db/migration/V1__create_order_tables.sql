CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE OR REPLACE FUNCTION update_timestamp()
    RETURNS TRIGGER AS
$$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Orders table
CREATE TABLE orders (
    id               UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    profile_id       UUID NOT NULL,
    store_id         UUID NOT NULL,
    store_name       VARCHAR(255),
    status           VARCHAR(50) NOT NULL,
    payment_type     VARCHAR(50),
    delivery_type    VARCHAR(50),
    delivery_address VARCHAR(500),
    pickup_code      VARCHAR(10),
    total_price_amount_minor BIGINT NOT NULL,
    total_price_currency     VARCHAR(3) NOT NULL,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- Order items table
CREATE TABLE order_items (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    order_id        UUID NOT NULL,
    surprise_box_id UUID,
    name            VARCHAR(255) NOT NULL,
    price_amount_minor BIGINT NOT NULL,
    price_currency     VARCHAR(3) NOT NULL,
    quantity        INT NOT NULL,
    image_url       VARCHAR(500),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE CASCADE
);

-- Order status history table
CREATE TABLE order_status_history (
    id         UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    order_id   UUID NOT NULL,
    status     VARCHAR(50) NOT NULL,
    changed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_order_status_history_order FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE CASCADE
);

-- Outbox and idempotency tables
CREATE TABLE outbox_events (
    id             UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    event_type     VARCHAR(100) NOT NULL,
    topic          VARCHAR(100) NOT NULL,
    event_key      VARCHAR(255),
    payload        TEXT NOT NULL,
    correlation_id UUID,
    published      BOOLEAN NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    published_at   TIMESTAMP WITH TIME ZONE
);

CREATE TABLE processed_events (
    event_id     UUID PRIMARY KEY,
    event_type   VARCHAR(100) NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- Indexes
CREATE INDEX idx_orders_profile_id ON orders (profile_id);
CREATE INDEX idx_orders_store_id ON orders (store_id);
CREATE INDEX idx_order_items_order_id ON order_items (order_id);
CREATE INDEX idx_order_status_history_order_id ON order_status_history (order_id);

-- Triggers
CREATE TRIGGER update_orders_timestamp BEFORE UPDATE ON orders FOR EACH ROW EXECUTE FUNCTION update_timestamp();
CREATE TRIGGER update_order_items_timestamp BEFORE UPDATE ON order_items FOR EACH ROW EXECUTE FUNCTION update_timestamp();
