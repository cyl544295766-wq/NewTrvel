/* global apiGet, apiPost, humanError */
window.TravelOSRecap = {
  init(tripId, userId, notify) {
    if (!tripId || !userId) return;
    const path = '/trips/' + encodeURIComponent(tripId) + '/journals/recap';
    const storageKey = 'travelos.recap:' + userId + ':' + tripId;
    const element = id => document.getElementById(id);
    const dialog = element('recapDialog');
    const form = element('recapForm');
    const status = element('recapStatus');
    const materialsPanel = element('recapMaterials');
    const submit = element('recapSubmit');
    const retry = element('recapReload');
    const another = element('recapAnother');
    const expenses = element('recapExpenses');
    const photoField = element('recapPhotoField');
    const noteField = element('recapNoteField');

    function restore() {
      try {
        return JSON.parse(sessionStorage.getItem(storageKey)) || {};
      } catch {
        return {};
      }
    }

    const saved = restore();
    let photoIds = Array.isArray(saved.photoIds) ? saved.photoIds : [];
    let noteIds = Array.isArray(saved.noteIds) ? saved.noteIds : [];
    let basicId = saved.basicId;
    let attempt = saved.attempt;
    let materials;
    let loading = false;
    let generating = false;
    expenses.checked = saved.includeExpenses === true;

    function persist() {
      try {
        sessionStorage.setItem(storageKey, JSON.stringify({
          photoIds, noteIds, includeExpenses: expenses.checked, attempt, basicId
        }));
      } catch { /* Keep the selection in memory when storage is unavailable. */ }
    }

    function requestId() {
      const bytes = crypto.getRandomValues(new Uint8Array(16));
      return Array.from(bytes, value => value.toString(16).padStart(2, '0')).join('');
    }

    function selectedPayload() {
      return { photoIds, noteIds, includeExpenses: expenses.checked };
    }

    function fingerprint() {
      return JSON.stringify(selectedPayload());
    }

    function updateControls() {
      photoField.disabled = noteField.disabled = expenses.disabled = loading || generating;
      for (const id of ['recapModule', 'recapMaterialButton']) {
        const button = element(id);
        if (button) button.disabled = generating;
      }
      submit.disabled = !materials || loading || generating;
      retry.disabled = loading || generating;
      const existing = attempt && attempt.journalId && attempt.selection === fingerprint();
      another.hidden = !existing || !materials || loading || generating;
      submit.textContent = generating ? '正在保存…' : existing ? '打开私密草稿' : '保存为私密草稿';
    }

    function updateCounts() {
      element('recapPhotoCount').textContent = '代表照片 ' + photoIds.length + '/' + materials.maxPhotos;
      element('recapNoteCount').textContent = '行程笔记 ' + noteIds.length + '/' + materials.maxNotes;
      for (const input of materialsPanel.querySelectorAll('input[data-photo]')) {
        const index = photoIds.indexOf(input.value);
        input.closest('label').querySelector('[data-order]').textContent = index < 0 ? '' : '第 ' + (index + 1) + ' 张';
      }
    }

    function showOptions(container, options, ids, max, photos, emptyText) {
      container.replaceChildren();
      if (!options.length) {
        const empty = document.createElement('p');
        empty.textContent = emptyText;
        container.append(empty);
        return;
      }
      const rows = document.createDocumentFragment();
      for (const [index, option] of options.entries()) {
        const label = document.createElement('label');
        label.className = 'recap-option';
        const input = document.createElement('input');
        input.type = 'checkbox';
        input.value = option.id;
        input.checked = ids.includes(option.id);
        label.append(input);
        if (photos && option.url && option.url.startsWith('/files/')) {
          const image = document.createElement('img');
          image.src = option.url;
          image.alt = '';
          image.loading = 'lazy';
          image.addEventListener('error', () => image.remove(), { once: true });
          label.append(image);
        }
        const description = document.createElement('span');
        const title = document.createElement('span');
        title.textContent = photos
          ? [option.date, option.place].filter(Boolean).join(' · ') || '照片 ' + (index + 1)
          : [option.date, option.label].filter(Boolean).join(' · ');
        description.append(title);
        const detail = document.createElement('span');
        detail.className = 'recap-material-meta';
        detail.textContent = photos ? option.caption : option.text;
        if (detail.textContent) description.append(detail);
        if (photos) {
          input.dataset.photo = '';
          const order = document.createElement('span');
          order.className = 'recap-material-meta';
          order.dataset.order = '';
          description.append(order);
        }
        label.append(description);
        input.addEventListener('change', () => {
          if (input.checked && ids.length >= max) {
            input.checked = false;
            status.textContent = '最多选择 ' + max + (photos ? ' 张照片' : ' 条笔记');
            return;
          }
          if (input.checked) {
            ids.push(option.id);
          } else {
            ids.splice(ids.indexOf(option.id), 1);
          }
          status.textContent = '';
          persist();
          updateCounts();
          updateControls();
        });
        rows.append(label);
      }
      container.append(rows);
    }

    async function loadMaterials() {
      if (loading) return;
      loading = true;
      status.textContent = '正在加载行程素材…';
      retry.hidden = true;
      updateControls();
      try {
        const response = await apiGet(path + '/materials');
        const photoSet = new Set(response.photos.map(photo => photo.id));
        const noteSet = new Set(response.notes.map(note => note.id));
        const count = photoIds.length + noteIds.length;
        photoIds = photoIds.filter(id => photoSet.has(id));
        noteIds = noteIds.filter(id => noteSet.has(id));
        materials = response;
        showOptions(element('recapPhotos'), materials.photos, photoIds, materials.maxPhotos, true, '还没有照片');
        showOptions(element('recapNotes'), materials.notes, noteIds, materials.maxNotes, false, '还没有行程笔记');
        materialsPanel.hidden = false;
        updateCounts();
        persist();
        status.textContent = count === photoIds.length + noteIds.length ? '' : '部分素材已移除，请核对其余选择。';
      } catch (err) {
        materials = null;
        materialsPanel.hidden = true;
        status.textContent = humanError(err);
        retry.hidden = false;
      } finally {
        loading = false;
        updateControls();
      }
    }

    function openJournal(id) {
      location.href = '/journal-detail/?id=' + encodeURIComponent(id) + '&trip=' + encodeURIComponent(tripId);
    }

    async function generate(basic) {
      if (generating) return;
      let payload;
      if (basic) {
        basicId ||= requestId();
        payload = { requestId: basicId };
      } else {
        const selection = fingerprint();
        if (!attempt || attempt.selection !== selection) attempt = { selection, requestId: requestId() };
        payload = { ...selectedPayload(), requestId: attempt.requestId };
      }
      persist();
      generating = true;
      const hint = element('recapHint');
      if (basic && hint) hint.textContent = '正在生成私密草稿…';
      if (!basic) {
        retry.hidden = true;
        status.textContent = '正在生成私密草稿…';
      }
      updateControls();
      try {
        // Even a known draft goes through the server so authorization and deletions are checked again.
        const result = await apiPost(path, payload);
        const id = result && result.journal && result.journal.id;
        if (!id) throw new Error('服务器未返回草稿，请重试');
        if (!basic) attempt.journalId = id;
        persist();
        openJournal(id);
      } catch (err) {
        const message = humanError(err);
        if (err.status === 409 && message.includes('回顾已删除')) {
          if (basic) basicId = null;
          else attempt = null;
          persist();
        }
        if (basic || !dialog.open) notify(message);
        else status.textContent = message;
        if (!basic && (err.status === 400 || err.status === 409)) retry.hidden = false;
      } finally {
        generating = false;
        if (basic && hint) hint.textContent = '一键生成基础回顾';
        updateControls();
      }
    }

    element('modules').addEventListener('click', event => {
      if (event.target.closest('#recapModule')) generate(true);
      if (event.target.closest('#recapMaterialButton')) {
        dialog.showModal();
        loadMaterials();
      }
    });
    element('recapClose').addEventListener('click', () => dialog.close());
    retry.addEventListener('click', loadMaterials);
    another.addEventListener('click', () => {
      attempt = null;
      persist();
      status.textContent = '已开启一篇新的草稿，可继续使用当前素材。';
      updateControls();
    });
    expenses.addEventListener('change', () => {
      persist();
      updateControls();
    });
    form.addEventListener('submit', event => {
      event.preventDefault();
      if (!submit.disabled) generate(false);
    });
  }
};
