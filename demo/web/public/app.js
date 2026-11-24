(() => {
  'use strict';
  const by = id => document.getElementById(id);
  const points = ['pickup', 'dropoff'];
  const chosen = { ...DemoLocations.defaults };
  const searchCache = new Map();
  const normalize = text => text.toLocaleLowerCase('vi').normalize('NFD')
    .replace(/\p{Diacritic}/gu, '').replace(/đ/g, 'd').trim();
  for (const field of points) {
    const input = by(field);
    const results = by(field + '-results');
    const feedback = by(field + '-feedback');
    const button = by(field + '-search').querySelector('button');
    const other = field === 'pickup' ? 'dropoff' : 'pickup';
    let controller;
    input.value = DemoLocations.places[chosen[field]].address;

    function showResults(matches) {
      results.replaceChildren();
      for (const match of matches) {
        const item = document.createElement('li');
        const option = document.createElement('button');
        option.type = 'button';
        option.textContent = match.address;
        option.onclick = () => {
          const canUse = !chosen[other] || DemoLocations.getRoute(chosen[other], match.value);
          if (!canUse) {
            feedback.textContent = 'Điểm đón và điểm trả phải khác nhau.';
            return;
          }
          chosen[field] = match.value;
          input.value = match.address;
          results.hidden = true;
          feedback.textContent = '';
          showRoute();
          input.focus();
        };
        item.append(option);
        results.append(item);
      }
      results.hidden = !matches.length;
    }
    input.oninput = () => {
      controller?.abort();
      chosen[field] = null;
      const query = normalize(input.value);
      const nearby = Object.entries(DemoLocations.places).filter(([,place]) => normalize(place.address).includes(query));
      showResults(query ? nearby.map(([value,place]) => ({ value, address: place.address })) : []);
      feedback.textContent = query ? 'Chọn gợi ý hoặc bấm Tìm để tra địa chỉ.' : '';
      button.disabled = false;
      showRoute();
    };
    by(field + '-search').onsubmit = async event => {
      event.preventDefault();
      const query = input.value.trim();
      if (query.length < 3) {
        feedback.textContent = 'Nhập ít nhất 3 ký tự để tìm.';
        return;
      }
      controller?.abort();
      const request = new AbortController();
      controller = request;
      button.disabled = true;
      feedback.textContent = 'Đang tìm địa điểm…';
      try {
        const key = normalize(query);
        let matches = searchCache.get(key);
        if (!matches) {
          const url = new URL('https://photon.komoot.io/api/');
          url.search = new URLSearchParams({ q: query, lat: '21.0285', lon: '105.8542', zoom: '11', bbox: '105.64,20.86,106.06,21.22', limit: '5' });
          const response = await fetch(url, { signal: AbortSignal.any([request.signal, AbortSignal.timeout(8000)]) });
          if (!response.ok) throw new Error('Place search failed');
          const data = await response.json();
          matches = data.features.flatMap(({ geometry, properties }) => {
            const [lng, lat] = geometry?.coordinates ?? [];
            const { name, street, housenumber, district, city, state } = properties ?? {};
            const streetAddress = [housenumber, street].filter(Boolean).join(' ');
            const address = [...new Set([name, streetAddress, district, city || state].filter(Boolean))].join(', ').slice(0, 500);
            return address && Number.isFinite(lat) && Number.isFinite(lng) ? [{ address, value: {lat, lng, address} }] : [];
          });
          if (searchCache.size >= 20) searchCache.clear();
          searchCache.set(key, matches);
        }
        if (request.signal.aborted) return;
        if (matches.length) showResults(matches);
        feedback.textContent = matches.length ? 'Chọn một địa điểm trong kết quả.' :
          'Chưa tìm thấy địa điểm ở khu vực Hà Nội. Thử thêm tên đường hoặc phường.';
      } catch {
        if (!request.signal.aborted) feedback.textContent = 'Chưa kết nối được dịch vụ tìm kiếm. Thử lại hoặc chọn gợi ý có sẵn.';
      } finally {
        if (controller === request) button.disabled = false;
      }
    };
  }

  const roadMap = createRoadMap(by('map'), by('route-status'));
  function selection() {
    if (!DemoLocations.getRoute(chosen.pickup, chosen.dropoff)) {
      throw new Error('Chọn điểm đón và điểm trả trong kết quả tìm kiếm trước khi chạy kiểm thử.');
    }
    return { ...chosen };
  }
  function showRoute() {
    const route = DemoLocations.getRoute(chosen.pickup, chosen.dropoff);
    roadMap.setRoute(route);
  }

  renderLive(by('live'), selection, by('demo-results'));
  showRoute();
})();
