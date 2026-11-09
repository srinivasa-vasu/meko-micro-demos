-- The checkout schema as it has run for years: designed properly from day one.
CREATE TABLE IF NOT EXISTS customers (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email       text        NOT NULL UNIQUE,
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS stores (
    id    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code  text NOT NULL UNIQUE,
    city  text NOT NULL
);

CREATE TABLE IF NOT EXISTS orders (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id  bigint        NOT NULL REFERENCES customers (id),
    status       text          NOT NULL CHECK (status IN ('PENDING', 'PAID', 'SHIPPED', 'DELIVERED', 'CANCELLED')),
    amount       numeric(12,2) NOT NULL,
    currency     char(3)       NOT NULL DEFAULT 'USD',
    created_at   timestamptz   NOT NULL DEFAULT now(),
    updated_at   timestamptz   NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS orders_customer_idx ON orders (customer_id, created_at);
-- The warehouse dashboard's index: "the oldest paid orders still to pick".
CREATE INDEX IF NOT EXISTS orders_status_idx ON orders (status, created_at);
