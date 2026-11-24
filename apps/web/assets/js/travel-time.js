/* Calendar labels use stored ISO day keys. Appointment formatting uses a named zone or fixed offset. */
(function () {
  function inZone(instant, zone) {
    if (!instant) return null;
    var date = new Date(instant);
    if (!Number.isFinite(date.getTime())) return null;
    zone = (zone || 'Asia/Shanghai').replace(/^(UTC|GMT)(?=[+-])/, '');
    var offset = /^([+-])(\d{2}):?(\d{2})$/.exec(zone);
    if (offset) {
      var minutes = (Number(offset[2]) * 60 + Number(offset[3])) * (offset[1] === '-' ? -1 : 1);
      date = new Date(date.getTime() + minutes * 60000);
      zone = 'UTC';
    }
    if (zone === 'Z') zone = 'UTC';
    var options = { timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit', hourCycle: 'h23' };
    var parts = {};
    var formatter;
    try { formatter = new Intl.DateTimeFormat('en-GB', options); }
    catch (e) { formatter = new Intl.DateTimeFormat('en-GB', Object.assign(options, { timeZone: 'UTC' })); }
    formatter.formatToParts(date).forEach(function (part) {
      parts[part.type] = part.value;
    });
    return { date: parts.year + '-' + parts.month + '-' + parts.day, clock: parts.hour + ':' + parts.minute,
      month: Number(parts.month), day: Number(parts.day) };
  }

  window.TravelTime = {
    date: function (instant, zone) { return (inZone(instant, zone) || {}).date || ''; },
    clock: function (instant, zone) { return (inZone(instant, zone) || {}).clock || ''; },
    input: function (instant, zone) {
      var value = inZone(instant, zone);
      return value ? value.date + 'T' + value.clock : '';
    },
    display: function (instant, zone) {
      var value = inZone(instant, zone);
      return value ? value.month + '月' + value.day + '日 ' + value.clock : '—';
    }
  };
})();
