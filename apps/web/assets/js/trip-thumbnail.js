(function (global) {
  'use strict';

  function draw(canvas, trip) {
    var ctx = canvas.getContext('2d');
    if (!ctx) return;
    var width = canvas.width, height = canvas.height;
    var points = (trip.routePreview || []).filter(function (point) {
      return point.latitude != null && point.longitude != null && point.latitude !== '' && point.longitude !== ''
        && Number.isFinite(Number(point.latitude)) && Number.isFinite(Number(point.longitude))
        && Math.abs(Number(point.latitude)) <= 90 && Math.abs(Number(point.longitude)) <= 180;
    });
    ctx.fillStyle = '#E6F0ED';
    ctx.fillRect(0, 0, width, height);
    ctx.strokeStyle = '#D5E4DF';
    ctx.lineWidth = 1;
    for (var x = 24; x < width; x += 36) {
      for (var y = 24; y < height; y += 36) {
        ctx.beginPath();
        ctx.arc(x, y, 1, 0, Math.PI * 2);
        ctx.stroke();
      }
    }
    ctx.font = '500 18px sans-serif';
    ctx.fillStyle = '#42635F';
    ctx.fillText('行程路线概览', 24, height - 20);
    if (!points.length) {
      ctx.fillStyle = '#0D6E6E';
      ctx.font = '600 28px sans-serif';
      ctx.fillText(trip.destination || trip.title || '旅行攻略', 24, 116, width - 48);
      ctx.font = '18px sans-serif';
      ctx.fillStyle = '#42635F';
      ctx.fillText('尚未添加带坐标的地点', 24, 153, width - 48);
      return;
    }
    var locations = points.map(function (point) {
      var latitude = Math.max(-85, Math.min(85, Number(point.latitude))) * Math.PI / 180;
      return { x: Number(point.longitude), y: -Math.log(Math.tan(Math.PI / 4 + latitude / 2)) * 180 / Math.PI };
    });
    var xs = locations.map(function (p) { return p.x; });
    var ys = locations.map(function (p) { return p.y; });
    var minX = Math.min.apply(null, xs), minY = Math.min.apply(null, ys);
    var spanX = Math.max.apply(null, xs) - minX, spanY = Math.max.apply(null, ys) - minY;
    var scale = Math.min((width - 150) / (spanX || 1), (height - 140) / (spanY || 1));
    var offsetX = (width - spanX * scale) / 2;
    var offsetY = 66 + (height - 140 - spanY * scale) / 2;
    var positions = locations.map(function (p) {
      return { x: offsetX + (p.x - minX) * scale, y: offsetY + (p.y - minY) * scale };
    });
    ctx.beginPath();
    positions.forEach(function (p, index) {
      if (index === 0) ctx.moveTo(p.x, p.y);
      else ctx.lineTo(p.x, p.y);
    });
    ctx.strokeStyle = '#188D85';
    ctx.lineWidth = 4;
    ctx.lineJoin = 'round';
    ctx.setLineDash([8, 6]);
    ctx.stroke();
    ctx.setLineDash([]);
    positions.forEach(function (p, index) {
      ctx.beginPath();
      ctx.arc(p.x, p.y, index === 0 || index === positions.length - 1 ? 9 : 5, 0, Math.PI * 2);
      ctx.fillStyle = index === positions.length - 1 ? '#FF8A4C' : '#0D6E6E';
      ctx.fill();
      ctx.strokeStyle = '#FFFFFF';
      ctx.lineWidth = 2;
      ctx.stroke();
    });
    ctx.font = '500 18px sans-serif';
    ctx.fillStyle = '#214B47';
    ctx.fillText(points[0].name || '起点', Math.min(width - 180, positions[0].x), positions[0].y - 17, 160);
    if (points.length > 1) {
      var last = positions[positions.length - 1];
      ctx.fillText(points[points.length - 1].name || '终点', Math.min(width - 180, last.x), last.y + 29, 160);
    }
  }

  function render(host, trip) {
    var canvas = host.querySelector('canvas');
    if (canvas) draw(canvas, trip);
    var image = host.querySelector('.trip-cover-image');
    if (image) {
      image.addEventListener('error', function () { image.remove(); }, { once: true });
      if (image.complete && !image.naturalWidth) image.remove();
    }
  }

  global.TravelOSThumbnail = { render: render };
})(window);
