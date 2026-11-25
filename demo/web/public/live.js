(() => {
  'use strict';
  function el(tag, css, text) {
    const node = document.createElement(tag);
    if (css) node.className = css;
    if (text !== undefined) node.textContent = text;
    return node;
  }

  function renderFlow(container, label, result) {
    const steps = result.steps ?? [];
    const awaiting = result.phase === 'awaiting-restart';
    const metrics = result.kind === 'performance'
      ? steps.find(step => step.action === 'k6 · kết quả thực tế')?.data : undefined;
    const number = new Intl.NumberFormat('vi-VN', { maximumFractionDigits: 2 });
    const performanceDetail = metrics && [
      [metrics.requests, value => number.format(value) + ' yêu cầu'],
      [metrics.requestsPerSecond, value => number.format(value) + ' yêu cầu/giây'],
      [metrics.p95Milliseconds, value => 'p95 ' + number.format(value) + ' ms'],
      [metrics.errorRate, value => number.format(value * 100) + '% lỗi HTTP'],
      [metrics.virtualUsers, value => number.format(value) + ' người dùng ảo'],
      [metrics.durationSeconds, value => number.format(value) + ' giây'],
    ].filter(([value]) => Number.isFinite(value)).map(([value, format]) => format(value)).join(' · ');
    const summary = {
      time: result.time, action: label, ok: awaiting ? undefined : result.ok,
      outcome: awaiting ? 'pending' : undefined,
      detail: [performanceDetail, result.error ||
        (awaiting ? 'Đã xếp hàng, chờ khởi động lại Payment' : 'Hoàn tất ' + steps.length + ' bước')].filter(Boolean).join(' · '),
    };
    const flow = el('div', 'log-scroll');
    for (const [index, step] of [summary, ...steps].entries()) {
      const { time, action, service, detail, outcome, ok, url, ...data } = step;
      const state = outcome || (ok === true ? 'success' : ok === false ? 'failure' : 'pending');
      const statuses = { success: 'Thành công', failure: 'Thất bại', pending: 'Đang chờ' };
      const assertion = typeof ok === 'boolean'
        ? (index === 0 ? 'Ca kiểm thử ' : 'Bước kiểm thử ') + (ok ? 'đạt' : 'chưa đạt') : '';
      const clock = new Date(time || Date.now()).toLocaleTimeString('vi-VN');
      const item = el('article', 'log');
      const body = el('div', '');
      const heading = el('div', 'event-head');
      heading.append(el('strong', '', action), el('span', 'event-state ' + state, statuses[state]));
      body.append(heading, el('p', '', [detail, assertion].filter(Boolean).join(' · ')));
      if (typeof url === 'string' && /^http:\/\/127\.0\.0\.1:\d+\/#\/queues\//.test(url)) {
        const rabbit = el('a', 'event-link', 'Xem hàng đợi trên RabbitMQ');
        rabbit.href = url;
        rabbit.target = '_blank';
        rabbit.rel = 'noopener';
        body.append(rabbit);
      }
      if (Object.keys(data).length) {
        const disclosure = el('details', 'event-data');
        disclosure.append(el('summary', '', 'Dữ liệu sự kiện'), el('pre', '', JSON.stringify(data, null, 2)));
        body.append(disclosure);
      }
      item.append(el('time', '', clock), el('b', '', service || 'API'), body);
      flow.append(item);
    }
    container.replaceChildren(flow);
  }

  window.renderLive = function renderLive(container, selectedLocations, resultPanel) {
    container.replaceChildren();
    const panel = el('section', 'lv-panel');
    panel.setAttribute('aria-label', 'API thật');
    container.append(panel);
    const header = el('div', 'lv-heading');
    const refresh = el('button', 'lv-btn', 'Kiểm tra lại');
    refresh.type = 'button';
    header.append(refresh);
    const status = el('p', 'lv-summary', 'Đang kết nối…');
    status.setAttribute('role', 'status');
    const grid = el('div', 'lv-grid');
    const actions = el('div', 'lv-cases');
    const results = resultPanel.querySelector('[role="region"]');
    const download = resultPanel.querySelector('#export-demo');
    let latestResult;
    download.onclick = () => {
      if (!latestResult) return;
      const file = new Blob([JSON.stringify(latestResult, null, 2)], { type: 'application/json' });
      const link = el('a', '');
      const url = URL.createObjectURL(file);
      link.href = url;
      link.download = 'ds03-' + (latestResult.kind || 'demo') + '.json';
      link.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    };
    function showResult() {
      resultPanel.hidden = false;
      const bounds = resultPanel.getBoundingClientRect();
      if (bounds.top < 0 || bounds.top > window.innerHeight - 100) resultPanel.scrollIntoView({ block: 'start' });
    }
    function finishResult(label, data) {
      latestResult = { label, ...data };
      renderFlow(results, label, data);
      download.disabled = false;
      showResult();
    }
    const recovery = el('div', 'lv-guide');
    const resume = el('button', 'lv-btn', 'Xác minh sau khi bật Payment');
    resume.type = 'button';
    recovery.hidden = true;
    recovery.append(
      el('p', '', 'Yêu cầu đang chờ. Xem Ready tại payment.commands, bật lại Payment rồi bấm xác minh.'),
      el('code', '', 'docker compose -p ds03-demo start payment-service'),
      resume,
    );
    panel.append(header, status, grid, actions, recovery);
    let running = false;
    let checking = false;
    let caseState;
    const paymentRecoveryLabel = 'Payment dừng, khởi động lại và xử lý yêu cầu đang chờ - Lỗi và phục hồi';
    resume.onclick = () => run('payment-resume', paymentRecoveryLabel);

    function localGuide() {
      status.textContent = 'Mở Trạm xe trên máy để kết nối API của dự án.';
      grid.replaceChildren();
      actions.replaceChildren();
      const guide = el('div', 'lv-guide');
      guide.append(el('h3', '', 'Mở bản cục bộ'), el('p', '', 'Trong thư mục chứa bộ web, chạy lệnh sau rồi mở địa chỉ hiển thị.'));
      guide.append(el('code', '', 'node local-demo.cjs'));
      const a = el('a', 'lv-link', 'Mở http://127.0.0.1:4175');
      a.href = 'http://127.0.0.1:4175';
      a.target = '_blank';
      a.rel = 'noopener';
      guide.append(a);
      actions.append(guide);
    }
    const local = location.protocol === 'http:' && ['localhost','127.0.0.1'].includes(location.hostname);
    if (!local) { refresh.hidden = true; localGuide(); return; }

    async function run(kind, label) {
      if (running) return;
      running = true;
      actions.querySelectorAll('button').forEach(button => { button.disabled = true; });
      resume.disabled = true;
      refresh.disabled = true;
      download.disabled = true;
      results.replaceChildren(el('p', 'empty', 'Đang chạy ' + label + '…'));
      showResult();
      try {
        const body = ['trip', 'trip-race', 'matching-race', 'reject', 'integrated-ride', 'integrated-refund', 'f01', 'f02', 'performance'].includes(kind) ? selectedLocations() : {};
        if (body === null) throw new Error('Chọn điểm đón và điểm trả khác nhau trên bản đồ.');
        const response = await fetch('/api/live/' + kind, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
        const data = await response.json();
        if (!response.ok || data.source !== 'ds03-local') throw new Error(data.error || 'Máy chủ chưa trả kết quả.');
        if (data.phase === 'awaiting-restart') recovery.hidden = false;
        if (data.phase === 'recovered') recovery.hidden = true;
        finishResult(label, data);
      } catch (err) {
        finishResult(label, { kind, ok: false, error: err.message });
      } finally {
        running = false;
        await update();
        actions.querySelectorAll('button').forEach(button => { button.disabled = running; });
        resume.disabled = running;
        refresh.disabled = checking || running;
      }
    }

    async function update(recordHealth = false) {
      if (checking || running) return;
      checking = true;
      refresh.disabled = true;
      if (caseState === undefined) status.textContent = 'Đang đọc health check của sáu dịch vụ…';
      try {
        const response = await fetch('/api/live/health', { cache:'no-store', signal: AbortSignal.timeout(12000) });
        const data = await response.json();
        if (!response.ok || data.source !== 'ds03-local') throw new Error();
        const ready = row => row.code === 200 && row.status === 'UP';
        const count = data.services.filter(ready).length;
        const time = new Date(data.time).toLocaleTimeString('vi-VN');
        status.textContent = count + '/6 dịch vụ sẵn sàng · Kiểm tra lúc ' + time;
        grid.replaceChildren(...data.services.map(row => {
          const card = el('div', 'lv-card');
          const stat = el('strong', ready(row) ? 'lv-online' : 'lv-down', ready(row) ? 'Hoạt động' : 'Chưa sẵn sàng');
          card.append(el('h3','',row.name), stat, el('code','', ':' + row.port + ' · ' + (row.code ? 'HTTP ' + row.code : 'Mất kết nối') + ' · ' + row.ms + ' ms'));
          return card;
        }));
        const up = name => data.services.some(row => row.name === name && ready(row));
        const unavailable = (...names) => names.filter(name => !up(name)).map(name => name + ' chưa sẵn sàng');
        const rabbitIssue = data.rabbitReady ? [] : ['RabbitMQ chưa kết nối được (cổng ' + data.rabbitPort + ')'];
        const driverTokenIssue = data.raceConfigured ? [] : ['Thiếu DRIVER_INTERNAL_TOKEN'];
        const paymentTokenIssue = data.paymentConfigured ? [] : ['Thiếu PAYMENT_INTERNAL_TOKEN'];
        const baseIssues = unavailable('User', 'Driver');
        const paymentIssues = [...unavailable('Payment'), ...rabbitIssue, ...paymentTokenIssue];
        const rideIssues = [...unavailable('User', 'Driver', 'Trip', 'Matching', 'Payment', 'Notification'),
          ...rabbitIssue, ...driverTokenIssue];
        const trip = data.services.find(row => row.name === 'Trip');
        if (up('Trip') && !trip?.rideWorkflow) rideIssues.push('Trip đang chạy bản chưa có luồng tích hợp');
        const faultIssues = [...rideIssues];
        if (trip?.rideWorkflow && !trip.demoFailpoints) {
          faultIssues.push('Khởi động stack với DS03-terminal.ps1 -Stage Up -EnableFailpoints');
        }
        const retryIssues = [...rabbitIssue, ...unavailable('Matching')];
        if (up('Driver')) retryIssues.push('Dừng Driver của stack ds03-demo để chạy ca này');
        const paymentStopIssues = [...rabbitIssue, ...paymentTokenIssue];
        if (up('Payment')) paymentStopIssues.push('Dừng Payment của stack ds03-demo để chạy ca này');
        if (data.paymentRecoveryPending) paymentStopIssues.push('Xác minh ca Payment đang chờ trước khi chạy ca mới');
        recovery.hidden = !data.paymentRecoveryPending;
        const rabbit = el('a', 'lv-link', 'Mở RabbitMQ');
        rabbit.href = 'http://127.0.0.1:' + data.rabbitPort + '/#/queues';
        rabbit.target = '_blank';
        rabbit.rel = 'noopener';
        header.replaceChildren(rabbit, refresh);
        if (recordHealth) {
          const label = 'D01 · Kiểm tra sáu dịch vụ - Khởi động';
          finishResult(label, {
            kind: 'health', ok: count === 6, time: data.time,
            steps: data.services.map(row => ({
              service: row.name, action: 'GET /actuator/health', method: 'GET', route: '/actuator/health',
              http: row.code, expectedHttp: 200, time: data.time, ok: ready(row), detail: row.status + ' · ' + row.ms + ' ms', data: row,
            })),
          });
        }
        const cases = [
          ['integrated-ride', 'Đặt xe và hoàn thành chuyến - Tích hợp xuyên suốt', 'Chọn điểm đón và trả → tạo chuyến → ghép tài xế → chuyển khoản một lần → tài xế nhận, bắt đầu và hoàn thành chuyến → giải phóng tài xế và lưu thông báo.', rideIssues, 'matching.commands · trip-service.queue · payment.commands · notification.commands'],
          ['integrated-refund', 'Hủy chuyến đã thanh toán và hoàn tiền - Tích hợp Saga', 'Hủy chuyến đã thanh toán trước khi tài xế bắt đầu. Kiểm tra tài xế được giải phóng, Payment hoàn đúng một lần và Notification lưu thông báo hủy.', rideIssues, 'trip-service.queue · payment.commands · notification.commands'],
          ['driver', 'Tài xế và phân quyền - Chức năng', 'Đăng nhập, cập nhật vị trí và bật nhận chuyến. Ca này cũng kiểm tra bảo mật và dữ liệu sai qua 401, 403 và 400.', baseIssues, 'HTTP'],
          ['trip', 'Gửi lại yêu cầu đặt xe vẫn chỉ tạo một chuyến - Chức năng', 'Gửi hai lần cùng một yêu cầu đặt xe với cùng mã chống trùng. Kiểm tra cả hai phản hồi có cùng tripId, rồi đọc lại chuyến ở trạng thái COMPLETED.', rideIssues, 'trip-service.queue'],
          ['matching-race', 'Hai hành khách đặt xe cùng lúc, tranh một tài xế - Đồng thời', 'Gửi hai yêu cầu tới Trip cùng lúc. Trip phát hai sự kiện đặt chuyến. Kiểm tra chỉ một chuyến giữ được tài xế.', rideIssues, 'matching.commands · trip-service.queue'],
          ['f01', 'Matching dừng sau khi giữ tài xế - Lỗi và phục hồi', 'Matching dừng trước khi phát DriverAssigned. Sau khi khởi động lại, Matching dùng lại reservation của đúng chuyến rồi tiếp tục luồng đặt xe.', faultIssues, 'matching.commands · trip-service.queue'],
          ['f02', 'Trip dừng sau khi giải phóng tài xế - Lỗi và phục hồi', 'Trip dừng trước khi phát yêu cầu hoàn tiền. Sau khi khởi động lại, Trip đọc outbox, hoàn đúng một lần và kết thúc hủy chuyến.', faultIssues, 'trip-service.queue · payment.commands · notification.commands'],
          ['payment', 'Chuyển khoản, gửi lặp yêu cầu thu và hoàn tiền - Chức năng', 'Cùng một chuyến chỉ được thu và hoàn tiền một lần dù yêu cầu thu được gửi lại.', paymentIssues, 'payment.commands'],
          ['cash', 'Tài xế xác nhận đã thu tiền mặt - Chức năng', 'Thanh toán chờ xác nhận sau khi chuyến kết thúc. Gửi lặp xác nhận vẫn chỉ ghi nhận một lần.', paymentIssues, 'payment.commands'],
          ['trip-race', 'Tài xế bắt đầu, hành khách hủy cùng lúc - Đồng thời', 'Trên cùng một chuyến đã được nhận, gửi đồng thời yêu cầu bắt đầu của tài xế và yêu cầu hủy của hành khách. Chỉ một yêu cầu được chấp nhận.', rideIssues, 'trip-service.queue'],
          ['reject', 'Tài xế từ chối chuyến - Chức năng', 'Tài xế đã được ghép từ chối chuyến. Kiểm tra giải phóng tài xế, hoàn tiền và lý do hủy driver_rejected.', rideIssues, 'trip-service.queue · payment.commands'],
          ['retry', 'Driver mất kết nối, Matching thử lại và chuyển message vào DLQ - Lỗi và phục hồi', 'Khi Driver đã dừng, gửi TripRequested và theo dõi các lượt thử lại của Matching đến khi hết lượt.', retryIssues, 'matching.retry · matching.dlq'],
          ['payment-stop', paymentRecoveryLabel, 'Dừng Payment bằng docker compose -p ds03-demo stop payment-service. Bấm Kiểm tra lại rồi gửi yêu cầu vào hàng đợi.', paymentStopIssues, 'payment.commands'],
          ['performance', 'Đo tốc độ API chi tiết chuyến bằng k6 - Hiệu năng', 'Đo API đọc một chuyến trên Trip tích hợp (8083) với 3 người dùng ảo trong 8 giây.', rideIssues, 'HTTP'],
        ];
        const nextCaseState = JSON.stringify(cases);
        if (caseState === nextCaseState) return cases;
        const cards = [];
        for (const [kind, label, detail, issues, observation] of cases) {
          const canRun = issues.length === 0;
          const card = el('div', 'lv-case');
          const copy = el('div');
          copy.append(el('h3', '', label), el('p','',detail));
          if (observation !== 'HTTP') copy.append(el('small', 'lv-watch', 'RabbitMQ · ' + observation));
          const hint = el('small', 'lv-hint', issues.join(' · '));
          hint.setAttribute('role', 'status');
          if (!canRun) copy.append(hint);
          const button = el('button', 'lv-btn', 'Chạy kiểm thử');
          button.type = 'button';
          button.disabled = running;
          button.setAttribute('aria-label', button.textContent + ' · ' + label);
          button.onclick = async () => {
            if (running) return;
            if (canRun) return run(kind, label);
            hint.textContent = 'Đang kiểm tra dịch vụ…';
            const latest = await update();
            if (running) return;
            const currentIssues = latest?.find(([id]) => id === kind)?.[3] ?? issues;
            if (currentIssues.length === 0) return run(kind, label);
            hint.textContent = 'Chưa thể chạy · ' + currentIssues.join(' · ');
            finishResult(label, { kind, ok: false, error: currentIssues.join(' · ') });
          };
          card.append(copy, button);
          cards.push(card);
        }
        actions.replaceChildren(...cards);
        caseState = nextCaseState;
        return cases;
      } catch {
        if (caseState === undefined) localGuide();
        else status.textContent = 'Chưa đọc được trạng thái mới. Bấm Kiểm tra lại để thử lại.';
      } finally {
        checking = false;
        refresh.disabled = running;
      }
    }
    refresh.onclick = () => update(true);
    update();
    const healthTimer = setInterval(() => {
      if (!panel.isConnected) clearInterval(healthTimer);
      else if (!document.hidden) update();
    }, 15000);
  };
})();
