(() => {
  const places = Object.freeze({
    'cau-giay': { address: 'Công viên Cầu Giấy', lat: 21.0306, lng: 105.7913 },
    'my-dinh': { address: 'Bến xe Mỹ Đình', lat: 21.0277, lng: 105.7785 },
    'keangnam': { address: 'Keangnam Landmark 72', lat: 21.0174, lng: 105.7842 },
    'bao-tang': { address: 'Bảo tàng Dân tộc học', lat: 21.0408, lng: 105.7984 },
  });
  const defaults = { pickup: 'cau-giay', dropoff: 'my-dinh' };
  function resolvePlace(value) {
    if (typeof value === 'string') return Object.hasOwn(places, value) ? { id: value, ...places[value] } : null;
    if (!value || typeof value.address !== 'string' || !Number.isFinite(value.lat) || !Number.isFinite(value.lng)) return null;
    const { lat, lng } = value;
    const address = value.address.trim();
    if (!address || address.length > 500 || Math.abs(lat) > 90 || Math.abs(lng) > 180) return null;
    return { lat, lng, address };
  }
  function getRoute(pickup, dropoff) {
    const from = resolvePlace(pickup), to = resolvePlace(dropoff);
    if (!from || !to || (Math.abs(from.lat - to.lat) < .000001 && Math.abs(from.lng - to.lng) < .000001)) return null;
    return { pickup: from, dropoff: to };
  }
  function apiLocation({lat, lng, address}) { return {lat, lng, address}; }
  const locations = { places, defaults, getRoute, apiLocation };
  if (typeof module !== 'undefined' && module.exports) module.exports = locations;
  else window.DemoLocations = locations;
})();
