CREATE TABLE orders (id TEXT PRIMARY KEY,merchant_id TEXT NOT NULL,description TEXT NOT NULL,amount_minor BIGINT NOT NULL CHECK(amount_minor>0 AND amount_minor<=100000000),currency TEXT NOT NULL CHECK(currency='INR'),status TEXT NOT NULL DEFAULT 'UNPAID',created_at TIMESTAMPTZ NOT NULL DEFAULT now(),paid_at TIMESTAMPTZ);
CREATE INDEX orders_merchant ON orders(merchant_id,created_at);
CREATE TABLE order_payments (order_id TEXT PRIMARY KEY REFERENCES orders(id),payment_intent_id TEXT NOT NULL UNIQUE,provider_transaction_id TEXT NOT NULL,created_at TIMESTAMPTZ NOT NULL DEFAULT now());
