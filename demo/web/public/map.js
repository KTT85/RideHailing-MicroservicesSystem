window.createRoadMap = function createRoadMap(container, status) {
  function announce(message, state = 'info') {
    status.textContent = message;
    status.dataset.state = state;
  }
  if (!window.L) {
    return { setRoute: () => announce('Chưa tải được bản đồ. Kiểm tra kết nối Internet rồi tải lại trang.', 'error') };
  }

  const map = L.map(container).setView([21.0285, 105.82], 12);
  map.attributionControl.addAttribution(
    '<a href="https://routing.openstreetmap.de/about.html">OSRM / FOSSGIS</a> · ' +
    '<a href="https://www.openstreetmap.org/fixthemap">Báo lỗi</a>',
  );
  L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxZoom: 19,
    attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>',
  }).addTo(map);
  const pins = L.layerGroup().addTo(map);
  const roads = L.layerGroup().addTo(map);
  const routes = new Map();
  const number = new Intl.NumberFormat('vi-VN', { maximumFractionDigits: 1 });
  let controller;
  let pending;
  let lastRequest = -Infinity;

  function marker(place, letter) {
    const label = document.createElement('span');
    label.textContent = place.address;
    const icon = L.divIcon({
      className: 'road-marker road-marker-' + letter.toLowerCase(),
      html: letter,
      iconSize: [28, 28],
      iconAnchor: [14, 14],
    });
    return L.marker([place.lat, place.lng], { icon, title: place.address, alt: place.address })
      .bindTooltip(label).addTo(pins);
  }

  function draw(route, endpoints) {
    const geometry = L.geoJSON(route.geometry, {
      style: { color: '#a6371e', weight: 5, opacity: .9 },
    }).addTo(roads);
    map.fitBounds(geometry.getBounds().extend(endpoints), { padding: [30, 30], maxZoom: 16 });
    announce('Tuyến ô tô · ' + number.format(route.distance / 1000) + ' km · khoảng ' +
      Math.max(1, Math.round(route.duration / 60)) + ' phút');
  }

  return {
    setRoute(selection) {
      clearTimeout(pending);
      controller?.abort();
      pins.clearLayers();
      roads.clearLayers();
      if (!selection) {
        announce('Chọn điểm đón và điểm trả để xem lộ trình.');
        return;
      }
      const { pickup, dropoff } = selection;
      const endpoints = L.latLngBounds([marker(pickup, 'A').getLatLng(), marker(dropoff, 'B').getLatLng()]);
      map.fitBounds(endpoints, { padding: [30, 30], maxZoom: 16 });
      const key = [pickup, dropoff].map(place => place.lng + ',' + place.lat).join(';');
      const previousRoute = routes.get(key);
      if (previousRoute) {
        draw(previousRoute, endpoints);
        return;
      }
      announce('Đang tìm lộ trình ô tô…');
      const request = new AbortController();
      controller = request;
      pending = setTimeout(async () => {
        lastRequest = performance.now();
        try {
          const url = 'https://routing.openstreetmap.de/routed-car/route/v1/driving/' +
            key + '?overview=full&geometries=geojson&alternatives=false&steps=false';
          const response = await fetch(url, { signal: AbortSignal.any([request.signal, AbortSignal.timeout(10000)]) });
          const data = await response.json();
          if (request.signal.aborted) return;
          if (data.code === 'NoRoute' || data.code === 'NoSegment') {
            announce('Không tìm được tuyến ô tô phù hợp. Thử chọn điểm gần đường xe chạy.', 'error');
            return;
          }
          const route = data.routes?.[0];
          if (!response.ok || data.code !== 'Ok' || route?.geometry?.type !== 'LineString' || route.geometry.coordinates.length < 2) {
            throw new Error('Routing unavailable');
          }
          if (routes.size >= 20) routes.clear();
          routes.set(key, route);
          draw(route, endpoints);
        } catch {
          if (!request.signal.aborted) {
            roads.clearLayers();
            announce('Chưa lấy được lộ trình. Chọn lại điểm hoặc thử tải lại trang.', 'error');
          }
        }
      }, Math.max(180, 1100 - (performance.now() - lastRequest)));
    },
  };
};
