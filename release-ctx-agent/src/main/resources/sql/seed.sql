-- Years of checkout history. Sized with psql variables: -v customers=100000 -v orders=1000000
INSERT INTO stores (code, city)
SELECT 'ST' || lpad(g::text, 4, '0'), (ARRAY['Austin','Boston','Chicago','Denver','Seattle'])[1 + g % 5]
FROM generate_series(1, 200) g;

INSERT INTO customers (email, created_at)
SELECT 'customer' || g || '@example.com', now() - (g % 2000) * interval '1 day'
FROM generate_series(1, :customers) g;

-- Mostly delivered history, a working set of open orders; created_at follows id, as a live table's does.
INSERT INTO orders (customer_id, status, amount, created_at, updated_at)
SELECT 1 + (hashint4(g) & 2147483647) % :customers,
       CASE WHEN g > :orders * 0.985 THEN (ARRAY['PENDING','PAID','PAID','SHIPPED'])[1 + g % 4]
            WHEN g % 200 = 0 THEN 'CANCELLED'
            ELSE 'DELIVERED' END,
       round((5 + (hashint4(g * 7) & 2147483647) % 50000 / 100.0)::numeric, 2),
       now() - (:orders - g) * interval '30 seconds',
       now() - (:orders - g) * interval '30 seconds'
FROM generate_series(1, :orders) g;

ANALYZE customers, stores, orders;
