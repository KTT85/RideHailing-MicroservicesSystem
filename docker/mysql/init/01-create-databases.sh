#!/bin/bash
# Non-executable init scripts are sourced by MySQL's entrypoint. Keep strict
# shell options local so nounset does not affect the entrypoint's optional vars.
(
set -euo pipefail

# The official MySQL entrypoint runs this once when initializing a new volume.
# Each service account owns exactly one database.
create_service_database() {
    local database="$1"
    local account="$2"
    local password="$3"
    # SQL uses NO_BACKSLASH_ESCAPES below; escape single quotes by doubling them.
    local escaped_password="${password//\'/\'\'}"

    MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --protocol=socket --user=root <<SQL
SET SESSION sql_mode = 'NO_BACKSLASH_ESCAPES';
CREATE DATABASE IF NOT EXISTS \`$database\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS '$account'@'%' IDENTIFIED BY '$escaped_password';
GRANT ALL PRIVILEGES ON \`$database\`.* TO '$account'@'%';
SQL
}

create_service_database user_db user_app "$MYSQL_USER_PASSWORD"
create_service_database driver_db driver_app "$MYSQL_DRIVER_PASSWORD"
create_service_database trip_db trip_app "$MYSQL_TRIP_PASSWORD"
create_service_database payment_db payment_app "$MYSQL_PAYMENT_PASSWORD"
create_service_database notification_db notification_app "$MYSQL_NOTIFICATION_PASSWORD"
)
