// Local HTTP bridge for the DS03 presentation. Requires Node.js 18+.
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const { randomUUID, randomInt, randomBytes } = require('node:crypto');
const { execFile } = require('node:child_process');
const { promisify } = require('node:util');
const { brokerDemo } = require('./broker-demo.cjs');
const { defaults, getRoute, apiLocation } = require('./public/locations.js');

const publicDir = path.join(__dirname, 'public');
const listenPort = Number(process.env.DS03_WEB_PORT || 4175);
const envFile = path.resolve(process.env.DS03_REPO || path.join(__dirname, '../..'), '.env');
function settings() {
  const result = {};
  if (fs.existsSync(envFile)) {
    for (const line of fs.readFileSync(envFile, 'utf8').split(/\r?\n/)) {
      const match = /^(USER_HTTP_PORT|PAYMENT_HTTP_PORT|DRIVER_INTERNAL_TOKEN|PAYMENT_INTERNAL_TOKEN|RABBITMQ_USER|RABBITMQ_PASSWORD|RABBITMQ_MANAGEMENT_PORT|DS03_K6_BIN)=(.*)$/.exec(line.trim());
      if (match) result[match[1]] = match[2].replace(/^["']|["']$/g, '');
    }
  }
  return {
    User: Number(process.env.USER_HTTP_PORT || result.USER_HTTP_PORT || 8081),
    Payment: Number(process.env.PAYMENT_HTTP_PORT || result.PAYMENT_HTTP_PORT || 8085),
    Driver: Number(process.env.DS03_DRIVER_PORT || 8082), Trip: 8083, Matching: 8084, Notification: 8086,
    serviceToken: process.env.DRIVER_INTERNAL_TOKEN || result.DRIVER_INTERNAL_TOKEN,
    paymentToken: process.env.PAYMENT_INTERNAL_TOKEN || result.PAYMENT_INTERNAL_TOKEN,
    Rabbit: Number(process.env.RABBITMQ_MANAGEMENT_PORT || result.RABBITMQ_MANAGEMENT_PORT || 15672),
    rabbitUser: process.env.RABBITMQ_USER || result.RABBITMQ_USER,
    rabbitPassword: process.env.RABBITMQ_PASSWORD || result.RABBITMQ_PASSWORD,
    k6Bin: process.env.DS03_K6_BIN || result.DS03_K6_BIN || 'k6',
  };
}
const components = ['User', 'Driver', 'Trip', 'Matching', 'Payment', 'Notification'];
const rideCases = ['integrated-ride', 'integrated-refund', 'f01', 'f02', 'trip', 'trip-race', 'reject', 'performance'];
let paymentRecovery;
let manualRide;
function pendingPaymentRecovery() {
  if (paymentRecovery && Date.now() - paymentRecovery.queuedAt >= 14 * 60 * 1000) paymentRecovery = undefined;
  return paymentRecovery;
}

function publicData(data) {
  return JSON.parse(JSON.stringify(data ?? null, (key, value) =>
    /token|password|secret|authorization|cookie|credential|api.?key|stack.?trace/i.test(key) ? undefined : value));
}

async function call(port, method, route, body, headers = {}) {
  try {
    const res = await fetch('http://127.0.0.1:' + port + route, {
      method, headers: { 'Content-Type': 'application/json', ...headers },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(5000), redirect: 'error',
    });
    const data = await res.json().catch(() => null);
    return { code: res.status, data };
  } catch {
    throw Object.assign(new Error('Không kết nối được cổng ' + port + '. Kiểm tra container và lệnh Up.'), { code: 'UNREACHABLE' });
  }
}
async function health() {
  const ports = settings();
  const services = await Promise.all(components.map(async name => {
    const port = ports[name];
    const start = Date.now();
    try {
      const result = await call(port, 'GET', '/actuator/health');
      return { name, port, code: result.code, status: result.data?.status || 'UNKNOWN', ms: Date.now() - start,
        ...(name === 'Trip' ? { rideWorkflow: result.data?.rideWorkflow === true,
          demoFailpoints: result.data?.demoFailpoints === true } : {}) };
    } catch {
      return { name, port, code: null, status: 'UNREACHABLE', ms: Date.now() - start };
    }
  }));
  let rabbitReady = false;
  if (ports.rabbitUser && ports.rabbitPassword) {
    try {
      const basic = Buffer.from(ports.rabbitUser + ':' + ports.rabbitPassword).toString('base64');
      rabbitReady = (await call(ports.Rabbit, 'GET', '/api/overview', undefined, { Authorization: 'Basic ' + basic })).code === 200;
    } catch { /* The case list shows the missing broker. */ }
  }
  return { source: 'ds03-local', services, time: new Date().toISOString(), raceConfigured: Boolean(ports.serviceToken),
    paymentConfigured: Boolean(ports.paymentToken), paymentRecoveryPending: Boolean(pendingPaymentRecovery()),
    rabbitReady, rabbitPort: ports.Rabbit };
}

async function scenario(kind, locations) {
  const ports = settings();
  const steps = [];
  const result = { source: 'ds03-local', kind, ok: false, steps, time: new Date().toISOString() };
  const log = (action, detail, ok, fields = {}) => steps.push({
    action, detail, ok, time: new Date().toISOString(), ...fields,
  });
  function verify(label, condition, detail, fields) {
    log(label, detail, Boolean(condition), fields);
    if (!condition) throw new Error('Sai kết quả tại bước ' + label + '.');
  }
  async function expect(service, method, route, wanted, body, headers, { quiet = false, allowRestart = false } = {}) {
    const action = service + ' · ' + method + ' ' + route;
    const context = { service, method, route, expectedHttp: wanted };
    if (body !== undefined) context.request = publicData(body);
    // The publication envelope is logged independently with its IDs.
    if (service === 'Rabbit' && context.request?.payload) delete context.request.payload;
    if (headers?.['X-Correlation-Id']) context.correlationId = headers['X-Correlation-Id'];
    let response;
    try {
      response = await call(ports[service], method, route, body, headers);
    } catch (error) {
      if (allowRestart && error.code === 'UNREACHABLE') throw error;
      log(action, error.message, false, { ...context, outcome: 'failure', data: { error: error.message } });
      throw error;
    }
    const { code, data } = response;
    const passed = Array.isArray(wanted) ? wanted.includes(code) : code === wanted;
    if (!quiet || !passed) {
      log(action, 'HTTP ' + code + ' · mong đợi ' + wanted, passed, {
        ...context, http: code, outcome: code < 400 ? 'success' : 'failure', data: publicData(data),
      });
    }
    if (!passed) throw new Error('Sai kết quả tại bước ' + action + '.');
    return data;
  }
  async function account(role) {
    const email = 'ds03-web-' + randomUUID() + '@example.com';
    const password = randomBytes(24).toString('base64');
    await expect('User', 'POST', '/auth/register', 201, {
      email, password, name: 'DS03 Demo ' + role, role, phone: '09' + randomInt(10_000_000, 100_000_000),
    });
    const login = await expect('User', 'POST', '/auth/login', 200, { identifier: email, password });
    verify('Đăng nhập ' + role, Boolean(login?.accessToken && login?.user?.role === role), login?.user?.role || 'Thiếu vai trò');
    return { headers: { Authorization: 'Bearer ' + login.accessToken }, id: login.user.id };
  }
  async function waitFor(service, route, headers, label, condition, allowRestart = false) {
    const deadline = Date.now() + (allowRestart ? 70_000 : 35_000);
    let data;
    let interrupted = false;
    do {
      try {
        data = await expect(service, 'GET', route, 200, undefined, headers, { quiet: true, allowRestart });
      } catch (error) {
        if (!allowRestart || error.code !== 'UNREACHABLE') throw error;
        if (!interrupted) {
          interrupted = true;
          log(service + ' · GET ' + route, 'Mất kết nối trong lúc chờ khởi động lại.', false,
            { service, method: 'GET', route, http: null, data: { error: error.message } });
        }
        await new Promise(resolve => setTimeout(resolve, 650));
        continue;
      }
      if (condition(data)) {
        verify(label, true, 'HTTP 200', { service, method: 'GET', route, http: 200,
          correlationId: headers['X-Correlation-Id'], data: publicData(data) });
        return data;
      }
      await new Promise(resolve => setTimeout(resolve, 650));
    } while (Date.now() < deadline);
    verify(label, false, 'Hết thời gian chờ. Đây là phản hồi gần nhất.', { service, method: 'GET', route, http: data === undefined ? null : 200,
      correlationId: headers['X-Correlation-Id'], data: publicData(data) });
  }
  let driverHeaders;
  let driverId;
  let reservationIds = [];
  const rideCleanup = [];
  const trusted = { 'X-Service-Token': ports.serviceToken || '' };
  try {
    if (kind.startsWith('booking-')) {
      const action = kind.slice('booking-'.length);
      if (action === 'create') {
        if (manualRide?.id) {
          const current = (await expect('Trip', 'GET', '/api/rides/' + manualRide.id, 200, undefined, manualRide.headers)).data;
          if (!['COMPLETED', 'CANCELLED'].includes(current.status) || (current.driverId && !current.driverReleased)) {
            throw new Error('Đang có chuyến. Hãy hoàn thành hoặc hủy chuyến trước.');
          }
        }
        for (const service of ['Trip', 'User', 'Matching', 'Driver', 'Payment']) {
          const status = await expect(service, 'GET', '/actuator/health', 200);
          if (status?.status !== 'UP' || (service === 'Trip' && !status.rideWorkflow)) {
            throw new Error(service + ' chưa sẵn sàng cho luồng đặt xe.');
          }
        }
        if (!ports.serviceToken) throw new Error('Thiếu cấu hình Driver của stack.');
        if (!manualRide || manualRide.id) {
          const passenger = await account('PASSENGER');
          manualRide = {
            headers: { ...passenger.headers, 'Idempotency-Key': randomUUID(), 'X-Correlation-Id': randomUUID() },
            body: { pickup: apiLocation(locations.pickup), dropoff: apiLocation(locations.dropoff),
              vehicleType: 'BIKE', paymentMethod: 'TRANSFER' },
          };
        }
        if (!manualRide.driverId) {
          const driver = await account('DRIVER');
          const driverAuth = { ...driver.headers, 'X-Correlation-Id': manualRide.headers['X-Correlation-Id'] };
          const profile = await expect('Driver', 'POST', '/drivers/profile', 201, {
            licensePlate: '51A-' + randomBytes(5).toString('hex'), vehicleType: 'BIKE',
          }, driverAuth);
          const pickup = manualRide.body.pickup;
          await expect('Driver', 'PUT', '/drivers/me/location', 200, { latitude: pickup.lat, longitude: pickup.lng }, driverAuth);
          await expect('Driver', 'PATCH', '/drivers/me/availability', 200, { acceptingTrips: true }, driverAuth);
          Object.assign(manualRide, { driverId: profile.driverId, driverAuth });
        }
        const reply = await expect('Trip', 'POST', '/api/rides', [201, 200], manualRide.body, manualRide.headers);
        if (!reply?.data?.id) throw new Error('Trip chưa trả mã chuyến.');
        manualRide.id = reply.data.id;
        result.ride = reply.data;
      } else if (manualRide?.id) {
        const { id, headers, driverAuth } = manualRide;
        const route = '/api/rides/' + id;
        const isDriver = ['accept', 'start', 'complete'].includes(action);
        const reply = action === 'status'
          ? await expect('Trip', 'GET', route, 200, undefined, headers, { quiet: true })
          : await expect('Trip', 'POST', route + '/' + action, 200, undefined, isDriver ? driverAuth : headers);
        result.ride = reply.data;
      }
      if (result.ride) {
        result.demoDriver = result.ride.driverId === manualRide.driverId;
        const paymentState = result.ride.paymentState;
        if (['SUCCESS', 'FAILED', 'REFUNDED', 'REFUND_FAILED'].includes(paymentState) &&
            paymentState !== manualRide.paymentChecked) {
          try {
            await expect('Payment', 'GET', '/payments/by-trip/' + manualRide.id, 200, undefined, manualRide.headers);
            manualRide.paymentChecked = paymentState;
          } catch { /* Keep displaying the state confirmed by Trip. */ }
        }
        const terminal = ['COMPLETED', 'CANCELLED'].includes(result.ride.status);
        if (terminal && (!result.ride.driverId || result.ride.driverReleased) && !manualRide.driverOffline) {
          try {
            const profile = await expect('Driver', 'GET', '/drivers/me', 200, undefined, manualRide.driverAuth, { quiet: true });
            if (profile.status === 'AVAILABLE') {
              await expect('Driver', 'PATCH', '/drivers/me/availability', 200, { acceptingTrips: false }, manualRide.driverAuth);
              manualRide.driverOffline = true;
            } else if (profile.status === 'OFFLINE') {
              manualRide.driverOffline = true;
            }
          } catch { /* Trip's terminal state remains available while Driver recovers. */ }
        }
      }
      result.ok = true;
      return result;
    }
    const brokerCases = ['matching-race', 'payment', 'cash', 'retry', 'payment-stop', 'payment-resume', ...rideCases];
    if (brokerCases.includes(kind) && (!ports.rabbitUser || !ports.rabbitPassword)) throw new Error('Thiếu cấu hình RabbitMQ của stack.');
    const broker = brokerDemo({ ports, expect, verify, log });
    if (kind === 'payment-stop') {
      if (pendingPaymentRecovery()) throw new Error('Đã có yêu cầu chờ phục hồi. Hãy xác minh ca đang chạy.');
      paymentRecovery = { ...await broker.queuePaymentWhileStopped(), queuedAt: Date.now() };
      result.phase = 'awaiting-restart';
      result.ok = true;
      return result;
    }
    if (kind === 'payment-resume') {
      const recovery = pendingPaymentRecovery();
      if (!recovery) throw new Error('Chưa có yêu cầu chờ phục hồi hoặc ca đã hết hạn.');
      await broker.verifyPaymentAfterRestart(recovery);
      paymentRecovery = undefined;
      result.phase = 'recovered';
      result.ok = true;
      return result;
    }
    if (kind === 'payment' || kind === 'cash' || kind === 'retry') {
      if (kind === 'retry') await broker.deadLetter();
      else await broker.payment(kind);
      result.ok = true;
      return result;
    }
    if (kind !== 'driver' && !ports.serviceToken) throw new Error('Cần DRIVER_INTERNAL_TOKEN của stack.');
    const driverAccount = await account('DRIVER');
    driverHeaders = driverAccount.headers;
    const profile = await expect('Driver', 'POST', '/drivers/profile', 201, {
      licensePlate: '51A-' + randomBytes(5).toString('hex'), vehicleType: 'BIKE',
    }, driverHeaders);
    driverId = profile?.driverId;
    verify('Tạo hồ sơ tài xế', Boolean(driverId && profile.status === 'OFFLINE'), profile?.status || 'Thiếu hồ sơ');
    const pickup = locations?.pickup;
    await expect('Driver', 'PUT', '/drivers/me/location', 200, {
      latitude: pickup?.lat ?? 21.0306, longitude: pickup?.lng ?? 105.7913,
    }, driverHeaders);
    const ready = await expect('Driver', 'PATCH', '/drivers/me/availability', 200, { acceptingTrips: true }, driverHeaders);
    verify('Bật nhận chuyến', ready?.status === 'AVAILABLE', ready?.status || 'Thiếu trạng thái');
    if (kind === 'driver') {
      const passenger = (await account('PASSENGER')).headers;
      await expect('User', 'GET', '/users/me', 200, undefined, passenger);
      await expect('User', 'GET', '/users/me', 401);
      await expect('Driver', 'GET', '/drivers/me', 403, undefined, passenger);
      await expect('Driver', 'PUT', '/drivers/me/location', 400, { latitude: 91, longitude: 106.66 }, driverHeaders);
    } else if (rideCases.includes(kind)) {
      const candidates = await expect('Driver', 'GET', '/internal/drivers/candidates?' + new URLSearchParams({
        pickupLatitude: pickup.lat, pickupLongitude: pickup.lng, vehicleType: 'BIKE', limit: '5',
      }), 200, undefined, trusted);
      verify('Tài xế BIKE của ca demo đang sẵn sàng',
        Array.isArray(candidates) && candidates.length === 1 && candidates[0].driverId === driverId,
        'Ca này cần đúng một tài xế BIKE đang nhận chuyến.', { data: candidates });

      const passenger = await account('PASSENGER');
      const correlationId = randomUUID();
      const headers = { ...passenger.headers, 'X-Correlation-Id': correlationId, 'Idempotency-Key': randomUUID() };
      if (kind === 'f01' || kind === 'f02') {
        headers['X-Demo-Failpoint'] = kind.toUpperCase();
        headers['X-Demo-Failpoint-Token'] = ports.serviceToken;
      }
      const driverAuth = { ...driverHeaders, 'X-Correlation-Id': correlationId };
      let refund = ['integrated-refund', 'f02', 'reject'].includes(kind);
      const matchEvents = ['TripRequested', 'DriverAssigned'];
      const commonEvents = [...matchEvents, 'PaymentRequested', 'PaymentSucceeded', 'TripConfirmed', 'DriverReleased'];
      const refundEvents = ['DriverReleaseRequested', 'PaymentRefundRequested', 'PaymentRefunded', 'TripCancelled'];
      const completionEvents = ['DriverStartRequested', 'DriverStarted', 'TripStarted', 'TripCompleted'];
      const eventTypes = () => [...commonEvents, ...(refund ? refundEvents : completionEvents)];
      const observationQueue = await broker.observeRide(kind, kind === 'performance' ? matchEvents :
        kind === 'trip-race' ? [...commonEvents, ...refundEvents, ...completionEvents] : eventTypes());
      const body = { pickup: apiLocation(locations.pickup), dropoff: apiLocation(locations.dropoff),
        vehicleType: 'BIKE', paymentMethod: 'TRANSFER' };
      const created = (await expect('Trip', 'POST', '/api/rides', 201, body, headers))?.data;
      verify('Trip lưu chuyến REQUESTED và mã liên kết', Boolean(created?.id && created.status === 'REQUESTED' && created.correlationId === correlationId),
        created?.id || 'Thiếu tripId', { data: created });
      const id = created.id;
      const route = '/api/rides/' + encodeURIComponent(id);
      const paymentRoute = '/payments/by-trip/' + encodeURIComponent(id);
      const notificationRoute = '/notifications/me?tripId=' + encodeURIComponent(id);
      rideCleanup.push({ headers, route });
      const repeat = (await expect('Trip', 'POST', '/api/rides', 200, body, headers))?.data;
      verify('Gửi lại yêu cầu đặt xe', repeat?.id === id, 'Cùng khóa trả về cùng chuyến.', { data: repeat });
      await waitFor('Trip', route, headers, kind === 'f01' ? 'F01 · Matching dùng lại reservation sau khởi động lại' : 'Matching đã ghép đúng tài xế',
        data => data?.data?.status === 'DRIVER_ASSIGNED' && data.data.driverId === driverId &&
          (kind !== 'f01' || Boolean(data.data.demoFaultAt)), kind === 'f01');
      await waitFor('Driver', '/drivers/me', driverAuth, 'Driver đã giữ đúng tripId',
        data => data?.status === 'RESERVED' && data.currentTripId === id);
      if (kind === 'performance') {
        const { stdout } = await promisify(execFile)(ports.k6Bin, ['run', '--quiet', path.join(__dirname, 'k6-trip.js')], {
          timeout: 20000, windowsHide: true, env: { ...process.env, DS03_TRIP_PORT: String(ports.Trip),
            DS03_TRIP_PATH: route, DS03_TRIP_AUTHORIZATION: headers.Authorization },
        }).catch(error => {
          if (error.code === 'ENOENT') {
            throw new Error('Không tìm thấy k6. Kiểm tra PATH hoặc đặt DS03_K6_BIN thành đường dẫn đầy đủ đến k6.exe trong .env.');
          }
          let measured;
          try { measured = JSON.parse(error.stdout); } catch { /* k6 may exit before generating a summary. */ }
          if (measured?.requests !== undefined) {
            log('k6 · kết quả thực tế', 'Ngưỡng kiểm thử chưa đạt', false, { data: measured });
            throw new Error('Bài đo k6 không đạt ngưỡng kiểm thử.');
          }
          throw new Error(error.killed ? 'Bài đo k6 vượt quá thời gian chờ.' : 'k6 thoát trước khi trả về kết quả đo.');
        });
        const data = JSON.parse(stdout);
        verify('k6 · kết quả thực tế', data.requests > 0 && data.errorRate === 0 && data.checkRate === 1,
          data.requests + ' request · p95 ' + Number(data.p95Milliseconds).toFixed(1) + ' ms', { tripId: id, data });
        result.ok = true;
        return result;
      }
      await expect('Trip', 'POST', route + '/pay', 200, undefined, headers);
      await expect('Trip', 'POST', route + '/pay', 200, undefined, headers);
      await waitFor('Trip', route, headers, 'Trip nhận kết quả thanh toán thành công',
        data => data?.data?.status === 'MATCHED' && data.data.paymentState === 'SUCCESS');
      const paid = await expect('Payment', 'GET', paymentRoute, 200, undefined, headers);
      verify('Payment chỉ thu một lần', paid?.tripId === id && paid.status === 'SUCCESS' &&
        Number(paid.amount) === created.fare && paid.operations?.length === 1 &&
        paid.operations[0].type === 'PAY' && paid.operations[0].status === 'SUCCESS',
      'Đối chiếu số tiền, mã chuyến và danh sách thao tác.', { data: paid });
      if (kind !== 'reject') await expect('Trip', 'POST', route + '/accept', 200, undefined, driverAuth);
      if (kind === 'trip-race') {
        const [start, cancel] = await Promise.all([
          expect('Trip', 'POST', route + '/start', [200, 409], undefined, driverAuth),
          expect('Trip', 'POST', route + '/cancel', [200, 409], undefined, headers),
        ]);
        const started = start?.data?.status === 'STARTING';
        refund = cancel?.data?.status === 'COMPENSATING';
        const codes = steps.filter(step => step.method === 'POST' && ['/start', '/cancel'].some(action => step.route === route + action))
          .map(step => step.http).sort((a, b) => a - b);
        verify('Chỉ một yêu cầu bắt đầu hoặc hủy được chấp nhận',
          started !== refund && codes.join(',') === '200,409', started ? 'Tài xế bắt đầu trước.' : 'Hành khách hủy trước.', { data: { start, cancel } });
      }
      if (refund) {
        if (kind === 'reject') await expect('Trip', 'POST', route + '/reject', 200, undefined, driverAuth);
        else if (kind !== 'trip-race') await expect('Trip', 'POST', route + '/cancel', 200, undefined, headers);
        await waitFor('Trip', route, headers, kind === 'f02' ? 'F02 · Trip xử lý tiếp Saga sau khởi động lại' : 'Saga xác nhận hoàn tiền và hủy chuyến',
          data => data?.data?.status === 'CANCELLED' && data.data.paymentState === 'REFUNDED' &&
            data.data.driverReleased && (kind !== 'reject' || data.data.cancelReason === 'driver_rejected') &&
            (kind !== 'f02' || Boolean(data.data.demoFaultAt)), kind === 'f02');
        const refunded = await expect('Payment', 'GET', paymentRoute, 200, undefined, headers);
        verify('Payment chỉ hoàn một lần', refunded?.id === paid.id && refunded.status === 'REFUNDED' &&
          Number(refunded.refundedAmount) === created.fare && refunded.operations?.length === 2 &&
          refunded.operations.filter(op => op.type === 'REFUND' && op.status === 'SUCCESS').length === 1,
        'Đối chiếu số tiền đã hoàn và thao tác REFUND.', { data: refunded });
      } else {
        if (kind !== 'trip-race') await expect('Trip', 'POST', route + '/start', 200, undefined, driverAuth);
        await waitFor('Driver', '/drivers/me', driverAuth, 'Driver đang phục vụ đúng chuyến',
          data => data?.status === 'BUSY' && data.currentTripId === id);
        await waitFor('Trip', route, headers, 'Trip nhận xác nhận Driver đã bắt đầu',
          data => data?.data?.status === 'IN_PROGRESS');
        await expect('Trip', 'POST', route + '/complete', 200, undefined, driverAuth);
        await waitFor('Trip', route, headers, 'Trip hoàn thành và nhận xác nhận giải phóng tài xế',
          data => data?.data?.status === 'COMPLETED' && data.data.driverReleased && data.data.paymentId === paid.id);
      }
      await waitFor('Driver', '/drivers/me', driverAuth, 'Tài xế đã sẵn sàng nhận chuyến mới',
        data => data?.status === 'AVAILABLE' && !data.currentTripId);
      const milestone = refund ? 'TripCancelled' : 'TripCompleted';
      const hasNotice = (data, userId, type) => Array.isArray(data) && data.filter(row => row.tripId === id
        && row.userId === userId && row.correlationId === correlationId && row.eventType === type).length === 1;
      const passengerNotices = await waitFor('Notification', notificationRoute, headers,
        'Notification lưu thông báo cho hành khách', data => hasNotice(data, passenger.id, milestone)
          && hasNotice(data, passenger.id, 'TripConfirmed') && hasNotice(data, passenger.id, 'PaymentSucceeded')
          && hasNotice(data, passenger.id, refund ? 'PaymentRefunded' : 'TripStarted'));
      await waitFor('Notification', notificationRoute, driverAuth, 'Notification lưu thông báo cho tài xế',
        data => hasNotice(data, driverAccount.id, 'TripConfirmed') &&
          (!refund || hasNotice(data, driverAccount.id, 'TripCancelled')));
      if (kind === 'integrated-ride') {
        const receipt = passengerNotices.find(row => row.eventType === milestone);
        const readRoute = '/notifications/' + encodeURIComponent(receipt.id) + '/read';
        await expect('Notification', 'PATCH', readRoute, 404, undefined, driverAuth);
        await expect('Notification', 'PATCH', readRoute, 204, undefined, headers);
        await waitFor('Notification', notificationRoute, headers, 'Hành khách đánh dấu thông báo đã đọc',
          data => Array.isArray(data) && data.some(row => row.id === receipt.id && row.readAt));
      }
      await broker.verifyRideEvents(observationQueue, id, correlationId, eventTypes());
      rideCleanup.length = 0;
    } else if (kind === 'matching-race') {
      const candidates = await expect('Driver', 'GET', '/internal/drivers/candidates?' + new URLSearchParams({
        pickupLatitude: pickup.lat, pickupLongitude: pickup.lng, vehicleType: 'BIKE', limit: '5',
      }), 200, undefined, trusted);
      verify('Chỉ một tài xế đủ điều kiện', Array.isArray(candidates) && candidates.length === 1 && candidates[0].driverId === driverId,
        'Ca test cần đúng một tài xế BIKE đang AVAILABLE.', { data: candidates });
      const passengers = await Promise.all([account('PASSENGER'), account('PASSENGER')]);
      const observationQueue = await broker.observeRide(kind, ['TripRequested', 'DriverAssigned', 'NoDriverAvailable']);
      const body = { pickup: apiLocation(locations.pickup), dropoff: apiLocation(locations.dropoff),
        vehicleType: 'BIKE', paymentMethod: 'TRANSFER' };
      const replies = await Promise.allSettled(passengers.map(async passenger => {
        const correlationId = randomUUID();
        const headers = { ...passenger.headers, 'X-Correlation-Id': correlationId, 'Idempotency-Key': randomUUID() };
        const trip = (await expect('Trip', 'POST', '/api/rides', 201, body, headers))?.data;
        if (!trip?.id) throw new Error('Trip chưa trả tripId.');
        const route = '/api/rides/' + encodeURIComponent(trip.id);
        rideCleanup.push({ headers, route });
        return { trip, route, headers, correlationId };
      }));
      const rejected = replies.find(reply => reply.status === 'rejected');
      if (rejected) throw rejected.reason;
      const both = await Promise.allSettled(replies.map(async ({ value: ride }) => {
        const result = await waitFor('Trip', ride.route, ride.headers, 'Đọc kết quả Matching ' + ride.trip.id,
          data => ['DRIVER_ASSIGNED', 'CANCELLED'].includes(data?.data?.status));
        return { ...ride, result: result.data };
      }));
      const failed = both.find(reply => reply.status === 'rejected');
      if (failed) throw failed.reason;
      const rides = both.map(({ value }) => value);
      const winners = rides.filter(ride => ride.result.status === 'DRIVER_ASSIGNED' && ride.result.driverId === driverId);
      const losers = rides.filter(ride => ride.result.status === 'CANCELLED' && ride.result.cancelReason === 'no_driver_available');
      verify('Hai chuyến chỉ có một chuyến giữ được tài xế', winners.length === 1 && losers.length === 1,
        'Một DriverAssigned và một NoDriverAvailable.', { data: rides.map(ride => ride.result) });
      const [winner] = winners;
      const saved = await expect('Driver', 'GET', '/drivers/me', 200, undefined, driverHeaders);
      verify('Đối chiếu currentTripId', saved.status === 'RESERVED' && saved.currentTripId === winner.trip.id,
        saved.currentTripId || 'Thiếu mã chuyến', { tripId: winner.trip.id, data: saved });
      for (const ride of rides) {
        const type = ride === winner ? 'DriverAssigned' : 'NoDriverAvailable';
        await broker.verifyRideEvents(observationQueue, ride.trip.id, ride.correlationId, ['TripRequested', type]);
      }
    } else {
      reservationIds = [randomUUID(), randomUUID()];
      const route = '/internal/drivers/' + encodeURIComponent(driverId) + '/reserve';
      const replies = await Promise.allSettled(reservationIds.map(tripId => call(ports.Driver, 'POST', route, { tripId }, trusted)));
      const codes = replies.map(item => item.status === 'fulfilled' ? item.value.code : 0);
      reservationIds.forEach((id, i) => {
        const reply = replies[i];
        const code = codes[i];
        log('Reserve ' + id, code ? 'HTTP ' + code : reply.reason.message, [200, 409].includes(code), {
          service: 'Driver', method: 'POST', route, tripId: id, http: code || null,
          outcome: code === 200 ? 'success' : 'failure',
          data: reply.status === 'fulfilled' ? publicData(reply.value.data) : { error: reply.reason.message },
        });
      });
      verify('Hai yêu cầu reserve đồng thời', [...codes].sort().join(',') === '200,409', codes.join(' / '));
      const saved = await expect('Driver', 'GET', '/drivers/me', 200, undefined, driverHeaders);
      verify('Đối chiếu currentTripId', saved?.status === 'RESERVED' && saved?.currentTripId === reservationIds[codes.indexOf(200)], saved?.currentTripId || 'Thiếu mã chuyến');
    }
    result.ok = true;
  } catch (error) {
    result.error = error.message;
  } finally {
    if (driverHeaders && driverId) {
      try {
        for (const { route, headers } of rideCleanup) {
          const ride = (await expect('Trip', 'GET', route, 200, undefined, headers))?.data;
          if (['REQUESTED','DRIVER_ASSIGNED','PAYMENT_PENDING','MATCHED','ACCEPTED','COMPENSATING'].includes(ride?.status)) {
            await expect('Trip', 'POST', route + '/cancel', 200, undefined, headers);
            await waitFor('Trip', route, headers, 'Dọn chuyến demo qua Saga', data => data?.data?.status === 'CANCELLED');
          }
        }
      } catch (error) {
        result.ok = false;
        result.error = [result.error, 'Dọn chuyến demo: ' + error.message].filter(Boolean).join(' ');
      }
      try {
        let profile = await expect('Driver', 'GET', '/drivers/me', 200, undefined, driverHeaders);
        if (reservationIds.includes(profile?.currentTripId)) {
          profile = await expect('Driver', 'POST', '/internal/drivers/' + encodeURIComponent(driverId) + '/release', 200, { tripId: profile.currentTripId }, trusted);
          verify('Giải phóng tài xế', profile?.status === 'AVAILABLE' && !profile.currentTripId, profile?.status || 'Thiếu trạng thái');
        }
        if (profile?.status === 'AVAILABLE') profile = await expect('Driver', 'PATCH', '/drivers/me/availability', 200, { acceptingTrips: false }, driverHeaders);
        verify('Tài xế kết thúc ca', profile?.status === 'OFFLINE', profile?.status || 'Thiếu trạng thái');
      } catch (error) {
        result.ok = false;
        result.error = [result.error, 'Dọn dữ liệu demo: ' + error.message].filter(Boolean).join(' ');
      }
    }
  }
  return result;
}

const mime = { '.html': 'text/html; charset=utf-8', '.js': 'application/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.svg': 'image/svg+xml', '.png': 'image/png', '.webp': 'image/webp', '.jpg':'image/jpeg', '.ico':'image/x-icon', '.woff2':'font/woff2', '.json':'application/json' };
let active = false;
function json(res, code, data) {
  res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' });
  res.end(JSON.stringify(data));
}
async function readTripRoute(req) {
  let size = 0;
  const chunks = [];
  for await (const chunk of req) {
    size += chunk.length;
    if (size <= 2048) chunks.push(chunk);
  }
  if (size > 2048) return null;
  let input;
  try { input = JSON.parse(Buffer.concat(chunks).toString() || '{}'); }
  catch { return null; }
  if (!input || typeof input !== 'object' || Array.isArray(input)) return null;
  const {pickup = defaults.pickup, dropoff = defaults.dropoff} = input;
  return getRoute(pickup, dropoff);
}
const server = http.createServer(async (req, res) => {
  try {
    const host = req.headers.host;
    if (!['127.0.0.1:' + listenPort, 'localhost:' + listenPort].includes(host)) return json(res, 403, { error: 'Host không hợp lệ.' });
    const url = new URL(req.url, 'http://' + host);
    if (url.pathname.startsWith('/api/')) {
      if (req.headers.origin && req.headers.origin !== 'http://' + host) return json(res, 403, { error: 'Origin không hợp lệ.' });
      if (req.method === 'GET' && url.pathname === '/api/live/health') return json(res, 200, await health());
      const match = /^\/api\/live\/(driver|race|trip|trip-race|matching-race|payment|cash|retry|performance|reject|payment-stop|payment-resume|integrated-ride|integrated-refund|f01|f02|booking-(?:create|status|pay|cancel|accept|start|complete))$/.exec(url.pathname);
      if (!match || req.method !== 'POST') return json(res, 404, { error: 'Sai API.' });
      if (req.headers.origin !== 'http://' + host || req.headers['content-type'] !== 'application/json') return json(res, 403, { error: 'Hãy chạy ca thử từ trang demo trên máy.' });
      if (active) return json(res, 409, { error: 'Một ca kiểm thử đang chạy.' });
      active = true;
      try {
        const needsRoute = ['matching-race', 'booking-create', ...rideCases].includes(match[1]);
        const locations = needsRoute ? await readTripRoute(req) : null;
        if (needsRoute && !locations) return json(res, 400, { error: 'Chọn hai địa điểm khác nhau có địa chỉ và tọa độ hợp lệ.' });
        return json(res, 200, await scenario(match[1], locations));
      }
      finally { active = false; }
    }
    if (req.method !== 'GET' && req.method !== 'HEAD') return json(res, 405, { error: 'Phương thức không hỗ trợ.' });
    const requested = decodeURIComponent(url.pathname === '/' ? '/index.html' : url.pathname);
    const filename = path.resolve(publicDir, '.' + requested);
    if (!filename.startsWith(publicDir + path.sep) || requested.includes('\\') || requested.split('/').some(part => part.startsWith('.'))) return json(res, 404, {});
    const type = mime[path.extname(filename).toLowerCase()];
    if (!type || !fs.existsSync(filename) || !fs.statSync(filename).isFile() || !fs.realpathSync(filename).startsWith(fs.realpathSync(publicDir) + path.sep)) return json(res, 404, {});
    res.writeHead(200, { 'Content-Type': type, 'X-Content-Type-Options': 'nosniff', 'Cache-Control': 'no-cache' });
    if (req.method === 'HEAD') return res.end();
    fs.createReadStream(filename).on('error', () => res.destroy()).pipe(res);
  } catch {
    if (!res.headersSent) json(res, 500, { error: 'Không thể đọc cấu hình hoặc phản hồi của service.' });
  }
});
server.on('error', error => { console.error('Không mở được cổng ' + listenPort + ' (' + error.code + ').'); process.exitCode = 1; });
server.listen(listenPort, '127.0.0.1', () => console.log('DS03 · http://127.0.0.1:' + listenPort));
