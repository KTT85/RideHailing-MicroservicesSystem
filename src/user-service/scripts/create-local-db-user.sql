-- Run in Workbench using the root connection to the LOCAL MySQL server.
-- Replace REPLACE_WITH_YOUR_PASSWORD before running. Do not commit the edited file.
-- Does not create/drop tables or modify existing account passwords.
CREATE USER IF NOT EXISTS 'user_app'@'localhost'
    IDENTIFIED BY 'REPLACE_WITH_YOUR_PASSWORD';

GRANT SELECT, INSERT, UPDATE, DELETE ON user_db.*
    TO 'user_app'@'localhost';

SHOW GRANTS FOR 'user_app'@'localhost';
