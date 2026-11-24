const { randomUUID } = require('node:crypto');
const { setTimeout: delay } = require('node:timers/promises');

function brokerDemo({ ports, expect, verify, log }) {
  const events = 'ride.events';
  const delivery = 'matching.delivery';
  const management = 'http://127.0.0.1:' + ports.Rabbit;
  const queuePage = name => management + '/#/queues/%2F/' + encodeURIComponent(name);
  const rabbitHeaders = {
    Authorization: 'Basic ' + Buffer.from(ports.rabbitUser + ':' + ports.rabbitPassword).toString('base64'),
  };
  const request = (method, route, code, body, options) => expect('Rabbit', method, '/api' + route, code, body, rabbitHeaders, options);
  const envelope = (eventType, correlationId, payload) => ({
    eventId: randomUUID(), eventType, correlationId, timestamp: new Date().toISOString(), payload,
  });
  async function observe(label, bindings) {
    const queue = 'ds03.demo.' + label + '.' + randomUUID().slice(0, 12);
    await request('PUT', '/queues/%2F/' + queue, 201, {
      durable: true, auto_delete: false, arguments: { 'x-expires': 900000, 'x-message-ttl': 900000 },
    });
    for (const [exchange, routingKey] of bindings) {
      await request('POST', '/bindings/%2F/e/' + exchange + '/q/' + queue, 201, {
        routing_key: routingKey, arguments: {},
      });
    }
    log('Hàng đợi quan sát', queue, true, { service: 'Rabbit', url: queuePage(queue), data: { bindings } });
    return queue;
  }
  async function publish(event) {
    const answer = await request('POST', '/exchanges/%2F/' + events + '/publish', 200, {
      properties: { delivery_mode: 2, content_type: 'application/json', correlation_id: event.correlationId },
      routing_key: event.eventType, payload: JSON.stringify(event), payload_encoding: 'string',
    });
    verify('RabbitMQ định tuyến ' + event.eventType, answer?.routed === true, answer?.routed ? 'routed=true' : 'routed=false', {
      service: 'Rabbit', correlationId: event.correlationId, tripId: event.payload.tripId, data: event,
    });
  }
  async function waitFor(feedback, query, matches, milliseconds = 25000) {
    const end = Date.now() + milliseconds;
    do {
      const value = await query();
      if (matches(value)) return value;
      if (Date.now() < end) await delay(600);
    } while (Date.now() < end);
    throw new Error('Hết thời gian chờ: ' + feedback + '.');
  }
  async function snapshots(queue) {
    const res = await request('POST', '/queues/%2F/' + queue + '/get', 200, {
      count: 35, ackmode: 'ack_requeue_true', encoding: 'auto', truncate: 30000,
    }, { quiet: true });
    return res.flatMap(message => {
      try {
        return [{ routingKey: message.routing_key, headers: message.properties?.headers ?? {}, event: JSON.parse(message.payload) }];
      } catch { return []; }
    });
  }
  async function outcomes(queue, ids, types) {
    const matches = row => ids.includes(row.event.payload?.tripId) && types.includes(row.event.eventType);
    const messages = await waitFor('sự kiện ' + types.join(' / '), () => snapshots(queue),
      rows => ids.every(id => rows.some(row => matches(row) && row.event.payload.tripId === id)), 35000);
    const selected = messages.filter(matches);
    for (const message of selected) {
      log(message.event.eventType, message.routingKey, true, {
        service: 'Rabbit', url: queuePage(queue), tripId: message.event.payload.tripId,
        correlationId: message.event.correlationId, data: message,
      });
    }
    return selected;
  }
  async function payment(kind) {
    if (!ports.paymentToken) throw new Error('Thiếu PAYMENT_INTERNAL_TOKEN trong .env.');
    const tripId = randomUUID(), passengerId = randomUUID(), correlationId = randomUUID();
    const method = kind === 'cash' ? 'CASH' : 'TRANSFER';
    const base = { tripId, passengerId, method, amount: 50000, currency: 'VND' };
    const types = ['PaymentSucceeded', 'PaymentRefunded', 'PaymentFailed'];
    const queue = await observe(kind, types.map(type => [events, type]));
    const route = '/internal/payments/by-trip/' + tripId;
    const auth = { 'X-Service-Token': ports.paymentToken };
    const read = () => expect('Payment', 'GET', route, [200, 404], undefined, auth, { quiet: true });
    const waitPayment = (states, operations) => waitFor('Payment ' + states.join('/'), read,
      value => states.includes(value?.status) && value.operations?.length === operations, 42000);
    if (kind === 'cash') {
      await publish(envelope('TripCompleted', correlationId, base));
      const pending = await waitPayment(['PENDING'], 0);
      verify('Chưa thu tiền khi TripCompleted', pending.status === 'PENDING', pending.status, { tripId, correlationId, data: pending });
      const confirm = envelope('CashPaymentConfirmationRequested', correlationId, { ...base, idempotencyKey: tripId + ':CONFIRM_CASH' });
      await publish(confirm);
      await publish(confirm);
      const final = await waitPayment(['SUCCESS'], 1);
      await outcomes(queue, [tripId], ['PaymentSucceeded']);
      verify('Chỉ một lần xác nhận CASH', final.status === 'SUCCESS' && Number(final.amount) === base.amount, final.status, { tripId, correlationId, data: final });
      return;
    }
    const pay = envelope('PaymentRequested', correlationId, { ...base, idempotencyKey: tripId + ':PAY' });
    await publish(pay);
    const paid = await waitPayment(['SUCCESS', 'FAILED'], 1);
    await outcomes(queue, [tripId], [paid.status === 'SUCCESS' ? 'PaymentSucceeded' : 'PaymentFailed']);
    verify('Payment đã thu tiền', paid.status === 'SUCCESS' && Number(paid.amount) === base.amount,
      paid.status, { tripId, correlationId, data: paid });
    await publish(pay);
    await publish(envelope('PaymentRequested', correlationId, pay.payload));
    const refund = envelope('PaymentRefundRequested', correlationId, { ...base, idempotencyKey: tripId + ':REFUND' });
    await publish(refund);
    const final = await waitPayment(['REFUNDED'], 2);
    const evidence = await outcomes(queue, [tripId], ['PaymentRefunded']);
    const successEvents = (await snapshots(queue)).filter(x => x.event.eventType === 'PaymentSucceeded' && x.event.payload?.tripId === tripId);
    verify('PAY lặp không thu thêm, hoàn đúng một lần',
      Number(final.refundedAmount) === base.amount && new Set(successEvents.map(x=>x.event.eventId)).size === 1 &&
      new Set(evidence.map(x=>x.event.eventId)).size === 1,
      final.status, { tripId, correlationId, data: final });
  }
  async function queuePaymentWhileStopped() {
    if (!ports.paymentToken) throw new Error('Thiếu PAYMENT_INTERNAL_TOKEN trong .env.');
    const health = await serviceHealth('Payment');
    const metricsPath = '/queues/%2F/payment.commands';
    const before = await request('GET', metricsPath, 200, undefined, { quiet: true });
    const { consumers, messages_ready: ready } = before;
    verify('Payment đã dừng nhận yêu cầu', health.status !== 'UP' && consumers === 0 && Number.isFinite(ready),
      'Payment ' + health.status + ' · ' + consumers + ' consumer',
      { service: 'Rabbit', url: queuePage('payment.commands'), data: { health, consumers, ready } });
    const queue = await observe('payment-restart', [[events, 'PaymentRequested'], [events, 'PaymentSucceeded']]);
    const tripId = randomUUID(), correlationId = randomUUID(), amount = 50000;
    const event = envelope('PaymentRequested', correlationId, {
      tripId, passengerId: randomUUID(), method: 'TRANSFER', amount, currency: 'VND', idempotencyKey: tripId + ':PAY',
    });
    await publish(event);
    await outcomes(queue, [tripId], ['PaymentRequested']);
    const after = await waitFor('payment.commands tăng Ready trong lúc Payment dừng',
      () => request('GET', metricsPath, 200, undefined, { quiet: true }),
      value => value.consumers === 0 && value.messages_ready >= ready + 1, 16000);
    log('Yêu cầu đang chờ trong payment.commands', 'Ready ' + ready + ' → ' + after.messages_ready,
      true, { service: 'Rabbit', url: queuePage('payment.commands'), tripId, correlationId,
        data: { consumers: after.consumers, readyBefore: ready, readyAfter: after.messages_ready, eventId: event.eventId } });
    return { queue, tripId, correlationId, amount, event };
  }
  async function verifyPaymentAfterRestart({ queue, tripId, correlationId, amount, event }) {
    const fields = { tripId, correlationId };
    const health = await waitFor('Payment UP', () => serviceHealth('Payment'), value => value.status === 'UP', 35000);
    verify('Payment đã hoạt động trở lại', health.status === 'UP', 'UP', { ...fields, service: 'Payment', data: health });
    const auth = { 'X-Service-Token': ports.paymentToken };
    const path = '/internal/payments/by-trip/' + tripId;
    const payment = await waitFor('Payment xử lý yêu cầu đã xếp hàng',
      () => expect('Payment', 'GET', path, [200, 404], undefined, auth, { quiet: true }),
      value => value?.status === 'SUCCESS' || value?.status === 'FAILED', 35000);
    verify('Yêu cầu trước restart được xử lý', payment.status === 'SUCCESS' && payment.operations?.length === 1 && Number(payment.amount) === amount,
      payment.status, { ...fields, service:'Payment', data:payment });
    const replies = await outcomes(queue, [tripId], ['PaymentSucceeded']);
    verify('Đúng một PaymentSucceeded cho cùng yêu cầu', new Set(replies.map(row => row.event.eventId)).size === 1 &&
      replies.every(row => row.event.correlationId === correlationId), 'Cùng tripId và correlationId',
      { ...fields, service:'Rabbit', url:queuePage(queue), data: { requestEventId:event.eventId, result:replies } });
  }
  async function deadLetter() {
    if ((await serviceHealth('Driver')).status === 'UP') {
      throw new Error('Để thử retry, dừng Driver của stack ds03-demo, giữ Matching và RabbitMQ hoạt động rồi chạy lại ca.');
    }
    const queue = await observe('retry', [[delivery, 'matching.retry'], [delivery, 'matching.dlq']]);
    const tripId = randomUUID(), correlationId = randomUUID();
    await publish(envelope('TripRequested', correlationId, {
      tripId, passengerId: randomUUID(), pickupLatitude: 21.0306, pickupLongitude: 105.7913, vehicleType: 'BIKE',
    }));
    const trace = await waitFor('message vào matching.dlq', () => snapshots(queue),
      rows => rows.some(row => row.routingKey === 'matching.dlq' && row.event.correlationId === correlationId), 80000);
    const run = trace.filter(row => row.event.correlationId === correlationId);
    const retries = run.filter(row => row.routingKey === 'matching.retry');
    const dead = run.find(row => row.routingKey === 'matching.dlq');
    verify('Matching hết lượt retry và message vào DLQ', retries.length > 0 && Boolean(dead),
      retries.length + ' lần retry quan sát được', { service:'Rabbit', tripId, correlationId, url:queuePage(queue), data:run });
  }
  async function serviceHealth(name) {
    try {
      const reply = await fetch('http://127.0.0.1:' + ports[name] + '/actuator/health', { signal: AbortSignal.timeout(2500) });
      return await reply.json();
    } catch { return { status: 'UNREACHABLE' }; }
  }
  const observeRide = (label, types) => observe(label, types.map(type => [events, type]));
  async function verifyRideEvents(queue, tripId, correlationId, types) {
    const rows = await waitFor(types.join(' / '), () => snapshots(queue), messages =>
      types.every(type => messages.some(({ event }) => event.eventType === type &&
        event.payload?.tripId === tripId && event.correlationId === correlationId)), 25000);
    for (const message of rows.filter(({ event }) => event.payload?.tripId === tripId &&
      event.correlationId === correlationId && types.includes(event.eventType))) {
      log(message.event.eventType, message.routingKey, true, {
        service: 'Rabbit', url: queuePage(queue), tripId, correlationId, data: message,
      });
    }
  }
  return { payment, deadLetter, queuePaymentWhileStopped, verifyPaymentAfterRestart, observeRide, verifyRideEvents };
}

module.exports = { brokerDemo };
