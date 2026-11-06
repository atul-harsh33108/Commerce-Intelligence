-- Carry the trace across the outbox (Step 13).
--
-- THE PROBLEM THIS SOLVES. The outbox deliberately decouples "the order is committed" from "the
-- event is published": the request thread writes a row and returns, and a scheduled relay publishes
-- it up to 500 ms later, on a different thread, in a different transaction. That is the whole point
-- of the pattern - and it severs the trace, because a trace context lives on a thread and the
-- request's thread is long gone.
--
-- Without this column the result is not "no tracing". It is worse: the relay's own scheduled tick
-- starts a fresh trace, so the consumer's work gets a plausible-looking trace that begins at the
-- relay and has no connection to the order that caused it. Four disconnected traces instead of one
-- story, each of which looks fine on its own.
--
-- So the W3C traceparent is captured at append() time, inside the same transaction that writes the
-- row, and restored on the relay thread before the send. It is data about the event's origin, and it
-- belongs in the row for the same reason event_id does.
--
-- Nullable, because a row written outside any trace context is legitimate - the reindex job, a
-- Flyway-time seed, anything not on a request thread. A null here means "not traced", not "broken".
ALTER TABLE outbox_events ADD COLUMN traceparent VARCHAR(64);

COMMENT ON COLUMN outbox_events.traceparent IS
    'W3C traceparent captured when the event was appended, so the relay can publish under the '
    'trace that caused it rather than under its own scheduled tick.';
