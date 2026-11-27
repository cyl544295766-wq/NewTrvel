(function (global) {
  'use strict';
  var entries = new Map();
  var observer = typeof IntersectionObserver === 'function' ? new IntersectionObserver(function (changes) {
    changes.forEach(function (change) {
      var entry = entries.get(change.target);
      if (!entry) return;
      entry.visible = change.isIntersecting;
      if (entry.visible) start(entry); else release(entry);
    });
  }, { rootMargin: '120px 0px' }) : null;

  function pointsOf(trip) {
    return (trip.routePreview || []).filter(function (point) {
      return point.latitude != null && point.longitude != null && point.latitude !== '' && point.longitude !== ''
        && Number.isFinite(Number(point.latitude)) && Number.isFinite(Number(point.longitude))
        && Math.abs(Number(point.latitude)) <= 85 && Math.abs(Number(point.longitude)) <= 180;
    }).slice(0, 24);
  }
  function style() {
    return document.documentElement.dataset.theme === 'light' ? 'amap://styles/whitesmoke' : 'amap://styles/dark';
  }
  function release(entry) {
    entry.job++;
    entry.loading = false;
    clearTimeout(entry.timer);
    if (entry.map) {
      entry.map.destroy();
      entry.map = null;
    }
    entry.surface.replaceChildren();
  }
  function status(entry, text, retry) {
    entry.status.replaceChildren();
    var message = document.createElement('span');
    message.textContent = text;
    entry.status.appendChild(message);
    entry.status.hidden = false;
    if (retry) {
      var button = document.createElement('button');
      button.type = 'button';
      button.dataset.mapRetry = '';
      button.textContent = '重新加载';
      button.addEventListener('click', function (event) {
        event.stopPropagation();
        entry.failed = false;
        start(entry);
      });
      entry.status.appendChild(button);
    }
  }
  function fail(entry) {
    release(entry);
    entry.failed = true;
    status(entry, '地图暂时未能加载', true);
  }
  function start(entry) {
    if (!entry.visible || !entry.host.isConnected || !entry.points.length || entry.map || entry.loading || entry.failed) return;
    var job = ++entry.job;
    entry.loading = true;
    status(entry, '正在加载行程地图…');
    entry.timer = setTimeout(function () {
      if (entry.job === job) fail(entry);
    }, 20000);
    Promise.resolve().then(function () { return global.TravelOSAmap.load([]); }).then(function (AMap) {
      if (entry.job !== job || !entry.visible || !entry.host.isConnected) return;
      var path = entry.points.map(function (point) { return [Number(point.longitude), Number(point.latitude)]; });
      var map = new AMap.Map(entry.surface, {
        viewMode: '2D', center: path[0], zoom: 12, mapStyle: style(), resizeEnable: true,
        dragEnable: false, zoomEnable: false, scrollWheel: false, doubleClickZoom: false,
        keyboardEnable: false, touchZoom: false, rotateEnable: false, animateEnable: false
      });
      entry.map = map;
      map.on('complete', function () {
        if (entry.job !== job) return;
        clearTimeout(entry.timer);
        entry.loading = false;
        entry.status.hidden = true;
      });
      var markers = path.map(function (position, index) {
        var label = document.createElement('span');
        label.className = 'trip-map-point' + (index === path.length - 1 ? ' is-end' : '');
        label.textContent = String(index + 1);
        return new AMap.Marker({ position: position, title: entry.points[index].name || '行程地点',
          content: label, anchor: 'center' });
      });
      map.add(markers);
      if (path.length > 1) {
        map.add(new AMap.Polyline({ path: path, strokeColor: '#188D85', strokeWeight: 3,
          strokeOpacity: .9, strokeStyle: 'dashed', lineJoin: 'round' }));
      }
      map.setFitView(markers, true, [40, 24, 28, 24], 13);
    }).catch(function () {
      if (entry.job === job) fail(entry);
    });
  }
  function clear(container) {
    entries.forEach(function (entry, host) {
      if (container !== host && !container.contains(host)) return;
      release(entry);
      if (observer) observer.unobserve(host);
      entries.delete(host);
    });
  }
  function render(host, trip) {
    if (!host) return;
    clear(host);
    var surface = host.querySelector('.trip-map');
    var message = host.querySelector('.trip-map-status');
    if (!surface || !message) return;
    var entry = { host: host, surface: surface, status: message, points: pointsOf(trip),
      map: null, loading: false, failed: false, job: 0, timer: 0, visible: !observer };
    entries.set(host, entry);
    if (!entry.points.length) {
      status(entry, '尚未添加地点坐标，暂无地图');
      return;
    }
    if (observer) observer.observe(host); else start(entry);
  }
  var themeObserver = new MutationObserver(function () {
    entries.forEach(function (entry) { if (entry.map) entry.map.setMapStyle(style()); });
  });
  themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] });
  global.addEventListener('pagehide', function () { entries.forEach(release); });
  global.addEventListener('pageshow', function () { entries.forEach(start); });
  global.TravelOSThumbnail = { render: render, clear: clear };
})(window);
