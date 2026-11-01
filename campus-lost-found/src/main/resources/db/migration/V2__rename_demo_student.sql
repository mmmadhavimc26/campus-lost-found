UPDATE app_users
SET name = 'Mayank (demo student)', email = 'mayank@demo.invalid'
WHERE email = 'alice@demo.invalid'
  AND NOT EXISTS (SELECT 1 FROM app_users WHERE email = 'mayank@demo.invalid');
