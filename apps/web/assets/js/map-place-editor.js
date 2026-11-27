/* Map-side place editing uses the same day locks and PATCH endpoint as itinerary. */
(function (global) {
  'use strict';
  function init(options) {
    var $ = function (id) { return document.getElementById(id); };
    var panel = $('dayPanel'), form = $('placeEditor'), edit = null;
    function find(days, id) {
      for (var day of days) {
        var place = (day.places || []).find(function (item) { return item.id === id; });
        if (place) return { day: day, place: place };
      }
      return null;
    }
    function lockUrl(entry) {
      return '/trips/' + encodeURIComponent(options.tripId()) + '/days/' + encodeURIComponent(entry.dayId) + '/edit-lock';
    }
    function active(entry) { return edit === entry && !entry.closing; }
    function message(text) { $('peStatus').textContent = text; }
    function coordinates() {
      var lng = $('peLng').value.trim(), lat = $('peLat').value.trim();
      if (!lng || !lat || !Number.isFinite(Number(lng)) || !Number.isFinite(Number(lat))
          || Math.abs(Number(lng)) > 180 || Math.abs(Number(lat)) > 90) return null;
      return { longitude: Number(lng), latitude: Number(lat) };
    }
    function originalCoordinates(entry) {
      var p = entry.original;
      return p.longitude != null && p.latitude != null && p.longitude !== '' && p.latitude !== ''
        && Number.isFinite(Number(p.longitude)) && Number.isFinite(Number(p.latitude));
    }
    function ui() {
      if (!edit) return;
      var blocked = edit.acquiring || edit.saving || edit.closing || edit.lockLost;
      $('peFields').disabled = blocked;
      $('pePick').disabled = blocked;
      $('peManual').disabled = blocked;
      $('peSave').disabled = blocked || edit.picking;
      $('peSave').textContent = edit.saving ? '正在保存…' : '保存地点';
      $('peCancel').disabled = $('peBack').disabled = !!(edit.saving || edit.closing);
      $('dpClose').disabled = !!(edit.saving || edit.closing);
    }
    function removePreview(entry) {
      if (entry.preview) { options.state().map.remove(entry.preview); entry.preview = null; }
    }
    function preview(entry, center) {
      removePreview(entry);
      var point = coordinates(), state = options.state();
      if (!point || !state.map) return;
      entry.preview = new state.AMap.Marker({ position: [point.longitude, point.latitude],
        content: '<span class="pe-pin">待保存位置</span>', anchor: 'bottom-center', zIndex: 180 });
      state.map.add(entry.preview);
      if (center) state.map.setZoomAndCenter(14, [point.longitude, point.latitude]);
    }
    function hideSuggestions() { $('peResults').hidden = true; $('peResults').replaceChildren(); }
    function populate(entry) {
      var p = entry.original;
      $('peName').value = p.name || '';
      $('peAddress').value = p.address || '';
      $('peType').value = ['attraction', 'hotel', 'restaurant', 'transport', 'shopping', 'custom'].includes(p.type) ? p.type : 'custom';
      $('peLng').value = p.longitude == null ? '' : p.longitude;
      $('peLat').value = p.latitude == null ? '' : p.latitude;
      $('peKeep').hidden = !originalCoordinates(entry);
    }
    function lock(entry) {
      if (entry.lockFlight) return entry.lockFlight;
      entry.lockFlight = apiPost(lockUrl(entry), {}).then(function () { entry.locked = true; })
        .finally(function () { entry.lockFlight = null; });
      return entry.lockFlight;
    }
    function stopPicking(entry) {
      if (entry.pickHandler) options.state().map.off('click', entry.pickHandler);
      entry.pickHandler = null; entry.picking = false;
      delete panel.dataset.picking;
      if (options.state().map.setDefaultCursor) options.state().map.setDefaultCursor('');
      $('pePick').textContent = '在地图上选点';
      ui();
    }
    async function close(saved) {
      var entry = edit;
      if (!entry || entry.saving || entry.closing) return;
      entry.closing = true; entry.query++;
      clearInterval(entry.heartbeat); clearTimeout(entry.searchTimer);
      stopPicking(entry); removePreview(entry); hideSuggestions(); ui();
      try { if (entry.lockFlight) await entry.lockFlight; } catch (_) { /* No lock acquired. */ }
      if (entry.locked) {
        try { await apiDelete(lockUrl(entry)); } catch (_) { /* Server TTL is the final release fallback. */ }
      }
      edit = null;
      form.hidden = true; delete panel.dataset.editing;
      entry.controls.forEach(function (item) { item.node.disabled = item.disabled; });
      $('dpClose').disabled = false;
      $('dayList').scrollTop = entry.scroll;
      if (!saved && originalCoordinates(entry)) {
        options.state().map.setZoomAndCenter(13, [Number(entry.original.longitude), Number(entry.original.latitude)]);
      }
      var trigger = $('dayList').querySelector('[data-edit-place="' + entry.id.replace(/["\\]/g, '\\$&') + '"]');
      if (trigger) trigger.focus({ preventScroll: true });
    }
    async function open(id) {
      var state = options.state();
      if (edit || !state.canEdit || !state.map) return;
      var found = find(state.days, id);
      if (!found || !(found.day.id || found.day.dayId)) return;
      var entry = { id: id, dayId: found.day.id || found.day.dayId, original: Object.assign({}, found.place),
        acquiring: true, saving: false, closing: false, lockLost: false, locked: false,
        query: 0, suggestions: [], scroll: $('dayList').scrollTop, addressEdited: false };
      edit = entry;
      entry.controls = ['dpSmaller', 'dpLarger'].map(function (id) {
        var node = $(id), value = { node: node, disabled: node.disabled }; node.disabled = true; return value;
      });
      panel.dataset.editing = 'true'; form.hidden = false;
      $('peTitle').textContent = '修改地点 · D' + found.day.dayIndex;
      populate(entry); hideSuggestions(); message('正在获取编辑状态…'); ui();
      try {
        await lock(entry);
        if (!active(entry)) return;
        var latest = await apiGet('/trips/' + encodeURIComponent(options.tripId()) + '/days');
        if (!active(entry)) return;
        var current = find(latest.days || [], id);
        if (!current || (current.day.id || current.day.dayId) !== entry.dayId) {
          throw new Error('地点已删除或移动，请关闭后刷新地图再试。');
        }
        entry.original = Object.assign({}, current.place);
        options.refresh(latest.days, id, false);
        populate(entry); entry.acquiring = false;
        message('可搜索替换地点、在地图上选点，或手动填写坐标；保存前不会改变原行程。'); ui(); $('peName').focus();
        entry.heartbeat = setInterval(async function () {
          if (!active(entry) || entry.saving || entry.lockLost || entry.lockFlight) return;
          try { await lock(entry); } catch (err) {
            if (!active(entry)) return;
            entry.lockLost = true; stopPicking(entry); clearInterval(entry.heartbeat);
            message('编辑状态已失效，请取消后重新打开。输入内容仍保留在这里。' + humanError(err)); ui();
          }
        }, 30000);
      } catch (err) {
        if (!active(entry)) return;
        entry.acquiring = false; entry.lockLost = true;
        if (entry.locked) { try { await apiDelete(lockUrl(entry)); entry.locked = false; } catch (_) {} }
        message(humanError(err)); ui();
      }
    }
    function changed() {
      if (!edit) return;
      $('peLng').value = ''; $('peLat').value = '';
      removePreview(edit); hideSuggestions();
      message('地点文字已修改，请选择搜索结果、在地图选点、手动填写坐标，或点击「沿用原位置」。');
    }
    async function search(entry, query, keyword) {
      try {
        var data = await apiGet('/places/suggestions?keyword=' + encodeURIComponent(keyword) + '&limit=8');
        if (!active(entry) || query !== entry.query || $('peName').value.trim() !== keyword) return;
        entry.suggestions = data.suggestions || [];
        hideSuggestions();
        entry.suggestions.forEach(function (item, index) {
          var button = document.createElement('button');
          button.type = 'button'; button.className = 'pe-result'; button.dataset.result = String(index);
          var name = document.createElement('span'), address = document.createElement('small');
          name.textContent = item.name || '未命名地点';
          address.textContent = [item.district, item.address].filter(Boolean).join(' · ') || '地址待补充';
          button.append(name, address); $('peResults').appendChild(button);
        });
        $('peResults').hidden = !entry.suggestions.length;
        if (!entry.suggestions.length) message('未找到匹配地点，可点击「手动填写坐标」后保存，也可在地图上选点。');
      } catch (_) {
        if (active(entry) && query === entry.query) message('地点搜索暂时不可用，仍可手动填写坐标或在地图上选点后保存。');
      }
    }
    $('peName').addEventListener('input', function () {
      if (!edit) return;
      var entry = edit, query = ++entry.query, keyword = this.value.trim();
      clearTimeout(entry.searchTimer); changed();
      if (keyword.length >= 2) entry.searchTimer = setTimeout(function () { search(entry, query, keyword); }, 300);
    });
    $('peAddress').addEventListener('input', function () {
      if (!edit) return; clearTimeout(edit.searchTimer); edit.addressEdited = true; edit.query++; changed();
    });
    $('peResults').addEventListener('click', function (event) {
      var button = event.target.closest('[data-result]');
      if (!edit || !button) return;
      var item = edit.suggestions[Number(button.dataset.result)];
      if (!item) return;
      edit.query++; clearTimeout(edit.searchTimer); edit.addressEdited = false;
      $('peName').value = item.name || '';
      $('peAddress').value = [item.district, item.address].filter(Boolean).join(' ');
      $('peLng').value = item.longitude == null ? '' : item.longitude;
      $('peLat').value = item.latitude == null ? '' : item.latitude;
      if (['attraction', 'hotel', 'restaurant', 'transport', 'shopping', 'custom'].includes(item.type)) $('peType').value = item.type;
      hideSuggestions(); preview(edit, true);
      message(coordinates() ? '已选中地点，保存后更新地图与路线。' : '该结果没有坐标，可手动填写坐标或在地图上选点。');
    });
    $('peKeep').addEventListener('click', function () {
      if (!edit || !originalCoordinates(edit)) return;
      $('peLng').value = edit.original.longitude; $('peLat').value = edit.original.latitude;
      preview(edit, true); message('将沿用原位置，仅更新地点文字或类型。');
    });
    $('peManual').addEventListener('click', function () {
      if (!edit || edit.acquiring || edit.saving || edit.closing || edit.lockLost) return;
      stopPicking(edit); hideSuggestions(); edit.query++; clearTimeout(edit.searchTimer);
      message('请填写经度（-180 至 180）和纬度（-90 至 90），无需匹配搜索结果即可保存。');
      $('peLng').focus(); $('peCoordinates').scrollIntoView({ block: 'nearest' });
    });
    ['peLng', 'peLat'].forEach(function (id) {
      $(id).addEventListener('input', function () {
        if (!edit) return;
        edit.query++; clearTimeout(edit.searchTimer); hideSuggestions(); preview(edit, false);
        message(coordinates() ? '坐标已填写，可直接保存地点，无需选择搜索结果。' : '请填写完整有效的经纬度：经度 -180 至 180，纬度 -90 至 90。');
      });
      $(id).addEventListener('change', function () { if (edit) preview(edit, true); });
    });
    $('pePick').addEventListener('click', function () {
      var entry = edit;
      if (!entry || entry.acquiring || entry.lockLost) return;
      if (entry.picking) { stopPicking(entry); message('已取消地图选点。'); return; }
      entry.picking = true; panel.dataset.picking = 'true';
      hideSuggestions(); entry.query++; clearTimeout(entry.searchTimer);
      $('pePick').textContent = '取消地图选点';
      message('点击地图上的目标位置。该位置会先预览，保存后才会替换原地点。');
      if (options.state().map.setDefaultCursor) options.state().map.setDefaultCursor('crosshair');
      entry.pickHandler = function (event) {
        if (!active(entry) || !event.lnglat) return;
        $('peLng').value = Number(event.lnglat.getLng()).toFixed(7);
        $('peLat').value = Number(event.lnglat.getLat()).toFixed(7);
        if (!entry.addressEdited) $('peAddress').value = '';
        stopPicking(entry); preview(entry, false); message('已选中地图位置，可补充地址后保存。');
      };
      options.state().map.on('click', entry.pickHandler); ui();
    });
    form.addEventListener('submit', async function (event) {
      event.preventDefault();
      var entry = edit;
      if (!entry || entry.acquiring || entry.saving || entry.closing || entry.lockLost || entry.picking) return;
      var point = coordinates(), name = $('peName').value.trim();
      if (!name) { message('请填写地点名称。'); $('peName').focus(); return; }
      if (!point) { message('请填写完整有效的经纬度，或选择搜索结果、在地图上选点。'); return; }
      entry.saving = true; ui(); hideSuggestions(); entry.query++; clearTimeout(entry.searchTimer);
      var patchStarted = false, persisted = false;
      try {
        await lock(entry);
        patchStarted = true;
        var result = await apiPatch('/trips/' + encodeURIComponent(options.tripId()) + '/places/' + encodeURIComponent(entry.id), {
          name: name, address: $('peAddress').value.trim() || null, type: $('peType').value,
          latitude: point.latitude, longitude: point.longitude
        });
        persisted = true;
        var found = find(options.state().days, entry.id);
        if (found && result.place) Object.assign(found.place, result.place);
        entry.saving = false;
        await close(true);
        options.refresh(options.state().days, entry.id, true);
      } catch (err) {
        entry.saving = false;
        if (!persisted && (err.status === 403 || err.status === 409)) {
          entry.lockLost = true; clearInterval(entry.heartbeat);
        }
        if (persisted) {
          $('placeSearchStatus').textContent = '地点已保存，但地图刷新失败，请刷新页面。';
        } else {
          var uncertain = patchStarted && (err.kind === 'network' || !err.status || err.status >= 500);
          message((uncertain ? '保存结果暂未确认，请刷新行程核对：' : '未保存：') + humanError(err));
        }
        ui();
      }
    });
    $('peBack').addEventListener('click', function () { close(false); });
    $('peCancel').addEventListener('click', function () { close(false); });
    $('dpClose').addEventListener('click', function () { close(false); });
    form.addEventListener('keydown', function (event) {
      if (event.key === 'Escape') {
        event.preventDefault();
        if (!$('peResults').hidden) hideSuggestions();
        else if (edit && edit.picking) stopPicking(edit);
        else close(false);
      } else if (event.key === 'Enter' && event.target === $('peName') && !$('peResults').hidden) {
        event.preventDefault(); var first = $('peResults').querySelector('button'); if (first) first.click();
      }
    });
    global.addEventListener('beforeunload', function (event) {
      if (edit && edit.saving) { event.preventDefault(); event.returnValue = ''; }
    });
    global.addEventListener('pagehide', function () {
      if (!edit) return;
      clearInterval(edit.heartbeat); clearTimeout(edit.searchTimer);
      // keepalive is needed here because normal API fetches can be aborted on navigation.
      if (edit.locked) {
        var token = getToken(), headers = {};
        if (token) headers.Authorization = 'Bearer ' + token;
        fetch('/api' + lockUrl(edit), { method: 'DELETE', credentials: 'same-origin', headers: headers, keepalive: true }).catch(function () {});
      }
    });
    return { open: open };
  }
  global.TravelOSMapPlaceEditor = { init: init };
})(window);
