-- Withings sends measures as an integer and a power of ten, and multiplying them in binary
-- floating point stored values such as 62.861000000000004 and 21.400000000000002. Ingestion
-- and replay now round scalar values to six decimal places; this brings stored rows in line.
UPDATE scalar_samples
SET value = round(value::numeric, 6)::double precision
WHERE value <> round(value::numeric, 6)::double precision;
