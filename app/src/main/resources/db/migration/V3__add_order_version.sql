-- @Version column for Order optimistic locking against the timeout or pay race.
-- See Order.java for the failure mode this guards against.
--
-- PG16: ADD COLUMN with constant DEFAULT is metadata only, no row rewrite, constant time.
--
-- Pre-existing rows read version=0 on first load; no backfill needed.

ALTER TABLE orders ADD COLUMN version BIGINT NOT NULL DEFAULT 0;