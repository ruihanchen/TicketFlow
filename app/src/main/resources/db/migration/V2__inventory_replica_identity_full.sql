-- REPLICA IDENTITY FULL writes the entire old row to WAL on UPDATE/DELETE.
-- Default 'default' only includes the primary key, but the CDC handler needs
-- ticket_type_id (not id) to locate the correct Redis key on DELETE.

ALTER TABLE inventories REPLICA IDENTITY FULL;