CREATE TABLE outbox (id TEXT PRIMARY KEY, event_type TEXT NOT NULL, payload TEXT NOT NULL, delivered BOOLEAN NOT NULL DEFAULT FALSE, attempts INT NOT NULL DEFAULT 0, next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(), created_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE INDEX outbox_pending ON outbox(next_attempt_at) WHERE NOT delivered;
CREATE TABLE processed_events (id TEXT PRIMARY KEY, created_at TIMESTAMPTZ NOT NULL DEFAULT now());
