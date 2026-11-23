import request from 'supertest';
import { createApp } from '../../src/notification-service/src/app';
import { InMemoryNotificationRepository } from '../../src/notification-service/src/repositories/InMemoryNotificationRepository';

const INPUT = { recipientId: 'passenger-1', recipientRole: 'PASSENGER', channel: 'PUSH', title: 'Hello', body: 'Demo' };
const BASE = '/api/notifications';

describe('Notification REST API', () => {
  let repository: InMemoryNotificationRepository;
  let app: ReturnType<typeof createApp>;
  beforeEach(() => { repository = new InMemoryNotificationRepository(); app = createApp(repository); });

  test.each(['PUSH', 'SMS', 'EMAIL'])('sends and retrieves %s notifications', async channel => {
    const sent = await request(app).post(`${BASE}/test-send`).send({ ...INPUT, channel }).expect(201);
    expect(sent.body).toMatchObject({ ...INPUT, channel, status: 'SENT', tripId: null, eventType: 'notification.test' });
    expect(Number.isNaN(Date.parse(sent.body.createdAt))).toBe(false);
    expect(Number.isNaN(Date.parse(sent.body.sentAt))).toBe(false);
    const detail = await request(app).get(`${BASE}/${sent.body.id}`).expect(200);
    expect(detail.body).toEqual(sent.body);
    const history = await request(app).get(BASE).query({ recipientId: INPUT.recipientId }).expect(200);
    expect(history.body).toEqual([sent.body]);
    expect((await request(app).get(BASE).query({ recipientId: 'another' }).expect(200)).body).toEqual([]);
  });

  test('supports driver and trip filtering', async () => {
    await repository.create({ ...INPUT, recipientRole: 'DRIVER', channel: 'SMS', eventType: 'trip.started', tripId: 'trip-1' });
    expect((await request(app).get(BASE).query({ tripId: 'trip-1', recipientId: INPUT.recipientId }).expect(200)).body).toHaveLength(1);
    expect((await request(app).get(BASE).query({ tripId: 'other' }).expect(200)).body).toEqual([]);
  });

  test('returns a standard notification 404', async () => {
    const result = await request(app).get(`${BASE}/missing`).expect(404);
    expect(result.body).toEqual({ error: { code: 'NOTIFICATION_NOT_FOUND', message: expect.any(String) } });
  });

  test.each([
    {}, { ...INPUT, recipientId: ' ' }, { ...INPUT, recipientRole: 'ADMIN' },
    { ...INPUT, channel: 'FAX' }, { ...INPUT, title: '' }, { ...INPUT, body: 42 },
    { ...INPUT, status: 'SENT' },
  ])('rejects invalid send input %j', async input => {
    const result = await request(app).post(`${BASE}/test-send`).send(input).expect(400);
    expect(result.body.error.code).toBe('VALIDATION_ERROR');
    expect(await repository.list({})).toEqual([]);
  });

  test.each(['?recipientId=', '?recipientId=a&recipientId=b', '?tripId=', '?unknown=x'])('rejects invalid query %s', async query => {
    expect((await request(app).get(BASE + query).expect(400)).body.error.code).toBe('VALIDATION_ERROR');
  });

  test('validates id parameters', async () => {
    expect((await request(app).get(`${BASE}/%20`).expect(400)).body.error.code).toBe('VALIDATION_ERROR');
  });

  test('handles malformed JSON centrally', async () => {
    const result = await request(app).post(`${BASE}/test-send`).set('Content-Type', 'application/json').send('{').expect(400);
    expect(result.body.error.code).toBe('INVALID_JSON');
  });

  test('propagates or generates correlation ids even on errors', async () => {
    const response = await request(app).get(`${BASE}/missing`).set('x-correlation-id', 'demo-123').expect(404);
    expect(response.headers['x-correlation-id']).toBe('demo-123');
    expect((await request(app).get(BASE)).headers['x-correlation-id']).toEqual(expect.any(String));
  });

  test.each(['list', 'create', 'markSent'] as const)('handles %s repository failures without leaking details', async method => {
    jest.spyOn(repository, method).mockRejectedValueOnce(new Error('private database details'));
    const result = method === 'list'
      ? await request(app).get(BASE).expect(500)
      : await request(app).post(`${BASE}/test-send`).send(INPUT).expect(500);
    expect(result.body).toEqual({ error: { code: 'INTERNAL_SERVER_ERROR', message: 'Internal server error' } });
  });

  test('keeps app instances isolated', async () => {
    await request(app).post(`${BASE}/test-send`).send(INPUT).expect(201);
    expect((await request(createApp()).get(BASE).expect(200)).body).toEqual([]);
  });
});
