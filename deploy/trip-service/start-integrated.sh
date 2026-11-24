#!/bin/sh
set -eu

if [ -z "${DATABASE_URL:-}" ]; then
    : "${MYSQL_TRIP_PASSWORD:?MYSQL_TRIP_PASSWORD is required}"
    export DATABASE_URL
    DATABASE_URL="$(node -e 'process.stdout.write("mysql://trip_app:" + encodeURIComponent(process.env.MYSQL_TRIP_PASSWORD) + "@mysql:3306/trip_db")')"
fi

npm run prisma:migrate
exec node dist/src/integrated-server.js
