ALTER TABLE reports
ADD COLUMN IF NOT EXISTS subtype VARCHAR(40);

UPDATE reports
SET subtype = CASE
    WHEN category = 'ELECTRONICS' THEN 'OTHER_ELECTRONICS'
    WHEN category = 'BAGS'        THEN 'OTHER_BAG'
    WHEN category = 'BOOKS'       THEN 'OTHER_BOOK'
    WHEN category = 'KEYS'        THEN 'OTHER_KEY'
    WHEN category = 'CLOTHING'    THEN 'OTHER_CLOTHING'
    WHEN category = 'OTHER'       THEN 'MISC'
END
WHERE subtype IS NULL;

ALTER TABLE reports
ALTER COLUMN subtype SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_reports_matching_v2 ON reports(category, subtype, kind, event_date);
