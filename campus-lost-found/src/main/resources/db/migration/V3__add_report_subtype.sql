ALTER TABLE reports
ADD COLUMN IF NOT EXISTS subtype VARCHAR(40);

UPDATE reports
SET subtype = CASE
    WHEN category = 'ELECTRONICS' THEN COALESCE(subtype, 'OTHER_ELECTRONICS')
    WHEN category = 'BAGS'        THEN COALESCE(subtype, 'OTHER_BAG')
    WHEN category = 'BOOKS'       THEN COALESCE(subtype, 'OTHER_BOOK')
    WHEN category = 'KEYS'        THEN COALESCE(subtype, 'OTHER_KEY')
    WHEN category = 'CLOTHING'    THEN COALESCE(subtype, 'OTHER_CLOTHING')
    WHEN category = 'OTHER'       THEN COALESCE(subtype, 'MISC')
END;

ALTER TABLE reports
ALTER COLUMN subtype SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_reports_matching_v2 ON reports(category, subtype, kind, event_date);
