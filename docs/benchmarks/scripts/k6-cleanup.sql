--deletion order follows FK constraints: order_status_history → orders → inventories → ticket_types → events
BEGIN;

DELETE FROM order_status_history
WHERE order_id IN (
    SELECT o.id FROM orders o
                         JOIN ticket_types tt ON o.ticket_type_id = tt.id
                         JOIN events e ON tt.event_id = e.id
    WHERE e.name LIKE 'Load Test%'
);

DELETE FROM orders
WHERE ticket_type_id IN (
    SELECT tt.id FROM ticket_types tt
                          JOIN events e ON tt.event_id = e.id
    WHERE e.name LIKE 'Load Test%'
);

DELETE FROM inventories
WHERE ticket_type_id IN (
    SELECT tt.id FROM ticket_types tt
                          JOIN events e ON tt.event_id = e.id
    WHERE e.name LIKE 'Load Test%'
);

DELETE FROM ticket_types
WHERE event_id IN (
    SELECT id FROM events WHERE name LIKE 'Load Test%'
);

DELETE FROM events WHERE name LIKE 'Load Test%';

DELETE FROM users WHERE username IN ('loadtest_admin', 'loadtest_user');

COMMIT;

SELECT
    (SELECT COUNT(*) FROM events WHERE name LIKE 'Load Test%') AS leftover_events,
    (SELECT COUNT(*) FROM orders) AS remaining_orders,
    (SELECT COUNT(*) FROM inventories) AS remaining_inventories;