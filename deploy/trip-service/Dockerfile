FROM node:20-bookworm-slim

WORKDIR /app

COPY src/trip-service/package*.json ./
RUN npm install --include=dev

COPY src/trip-service/prisma ./prisma
COPY src/trip-service/src ./src
COPY src/trip-service/tsconfig.json ./tsconfig.json
RUN npx prisma generate

CMD ["npx", "prisma", "migrate", "deploy"]
