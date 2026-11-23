# Notification REST API (T5)

Requires Node.js 20. Run from `src/notification-service`:

```powershell
npm ci
npm test
npm run dev
```

Default port: 3005; override with `PORT`. `npm run dev` compiles TypeScript then starts the standalone in-memory app. Restart it after source changes. Data is reset when the process exits.

PowerShell demo (use `curl` instead of `curl.exe` on Linux/macOS):

```powershell
curl.exe -X POST http://localhost:3005/api/notifications/test-send -H 'Content-Type: application/json' -d '{"recipientId":"passenger-1","recipientRole":"PASSENGER","channel":"PUSH","title":"Demo","body":"Hello"}'
curl.exe 'http://localhost:3005/api/notifications?recipientId=passenger-1'
```

In Postman, POST the same URL with Body > raw > JSON and the object above, then GET the history URL. POST returns 201 and the stored notification with `status: SENT`, ISO timestamps, `eventType: notification.test` and `tripId: null`. GET history returns an array; GET `/api/notifications/:id` returns a single object or 404 with `error.code: NOTIFICATION_NOT_FOUND`.

Recipient and trip filters are optional and combined with AND. An unfiltered GET returns all demo notifications. Empty or repeated filters, unknown query fields, invalid bodies and whitespace-only IDs produce 400. IDs are opaque strings, not restricted to UUIDs, to preserve compatibility with other repository implementations.

Inject the T4 repository through `createApp(repository)`. The unchanged interface has no `findById`, so detail lookup uses `list({})` and searches by ID. This is appropriate for the small demo; larger datasets would require a jointly agreed repository contract extension.

Required runtime libraries: `express` 4.21.2, `zod` 3.24.1, `pino` 9.6.0.
Development libraries: `typescript` 5.7.2, `jest` 29.7.0, `supertest` 7.0.0, and `@types/node`, `@types/express`, `@types/jest`, `@types/supertest` (exact versions in package.json). Tests compile with TypeScript before Jest, so no ts-jest or ts-node is required.

The filenames explicitly requested by T5/T4 are retained even where they differ from the general kebab-case rule. Existing Java files, Prisma/RabbitMQ integration, and Trip Service are untouched.
