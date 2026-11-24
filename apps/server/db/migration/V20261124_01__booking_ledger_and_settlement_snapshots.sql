ALTER TABLE bookings ADD COLUMN IF NOT EXISTS cancelled_at TIMESTAMP(3);
ALTER TABLE trip_expenses ADD COLUMN IF NOT EXISTS booking_id TEXT;
ALTER TABLE trip_expenses ADD COLUMN IF NOT EXISTS booking_payment_id TEXT;
ALTER TABLE trip_expenses ADD COLUMN IF NOT EXISTS booking_operation_key TEXT;
ALTER TABLE trip_expenses ADD COLUMN IF NOT EXISTS adjustment_kind TEXT;

CREATE INDEX IF NOT EXISTS idx_trip_expenses_booking_id
  ON trip_expenses(booking_id) WHERE booking_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_trip_expenses_booking_payment
  ON trip_expenses(booking_payment_id) WHERE booking_payment_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_trip_expenses_booking_operation
  ON trip_expenses(booking_id, booking_operation_key) WHERE booking_operation_key IS NOT NULL;

-- Repair stale links left by the former delete flow; keep all valid history.
UPDATE bookings AS booking
SET expense_id = NULL
WHERE expense_id IS NOT NULL AND NOT EXISTS (
  SELECT 1 FROM trip_expenses e WHERE e.id = booking.expense_id AND e.trip_id = booking.trip_id
);

UPDATE trip_expenses AS expense
SET booking_id = booking.id
FROM bookings AS booking
WHERE expense.id = booking.expense_id AND expense.trip_id = booking.trip_id
  AND expense.booking_id IS NULL
  AND NOT EXISTS (
    SELECT 1 FROM bookings other WHERE other.expense_id = expense.id AND other.id <> booking.id
  );

ALTER TABLE trip_settlements ADD COLUMN IF NOT EXISTS currency TEXT;
ALTER TABLE trip_settlements ADD COLUMN IF NOT EXISTS preview_token TEXT;
CREATE UNIQUE INDEX IF NOT EXISTS uq_trip_settlements_preview
  ON trip_settlements(trip_id, currency, preview_token) WHERE preview_token IS NOT NULL;
