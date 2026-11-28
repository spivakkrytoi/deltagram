const tg = window.Telegram?.WebApp;
tg?.ready();
tg?.expand();
tg?.disableVerticalSwipes?.();

const initData = tg?.initData || '';
const state = {
  gifts: [],
  recipient: '',
  gift: null,
  catalog: null,
  selection: { backdrop: '', model: '', pattern: '' },
  sheetKind: '',
  previewToken: 0,
};

const $ = (id) => document.getElementById(id);
const listView = $('list-view');
const detailView = $('detail-view');
const doneView = $('done-view');
const giftsEl = $('gifts');
const listStatus = $('list-status');
const searchInput = $('search');
const cta = $('cta');
const issueButton = $('issue');
const issueStatus = $('issue-status');
const sheet = $('sheet');
const sheetTitle = $('sheet-title');
const sheetSearch = $('sheet-search');
const attrList = $('attr-list');
const toast = $('toast');

const jsonCache = new Map();
const inFlight = new Map();
const thumbObservers = [];
const previewAnims = [];

function colorHex(value) {
  const n = Number(value || 0) >>> 0;
  return `#${n.toString(16).padStart(6, '0').slice(-6)}`;
}

function hueFor(id) {
  let hash = 0;
  for (const ch of String(id)) hash = (hash * 31 + ch.charCodeAt(0)) >>> 0;
  return hash % 360;
}

function giftGradient(gift) {
  const bg = gift.background || {};
  if (bg.center || bg.edge) {
    return `radial-gradient(circle at 35% 22%, ${colorHex(bg.center)}, ${colorHex(bg.edge)} 72%)`;
  }
  const hue = hueFor(gift.id);
  return `radial-gradient(circle at 35% 22%, hsl(${hue} 48% 42%), hsl(${(hue + 24) % 360} 55% 16%) 72%)`;
}

function backdropGradient(item) {
  const backdrop = item.backdrop || {};
  return `radial-gradient(circle at 35% 22%, ${colorHex(backdrop.center)}, ${colorHex(backdrop.edge)} 72%)`;
}

function showToast(message, bad = false) {
  toast.textContent = message;
  toast.className = bad ? 'toast toast--bad' : 'toast';
  toast.hidden = false;
  clearTimeout(showToast.timer);
  showToast.timer = setTimeout(() => { toast.hidden = true; }, 3200);
}

async function api(path) {
  const response = await fetch(path, {
    headers: { 'X-Init-Data': initData },
    cache: 'no-store',
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(data.error || `Ошибка ${response.status}`);
  return data;
}

async function loadAnimationJSON(giftID, kind, attributeID) {
  const path = `/gift-lab/api/gifts/${giftID}/collectibles/${kind}/${attributeID}/animation`;
  if (jsonCache.has(path)) return jsonCache.get(path);
  if (inFlight.has(path)) return inFlight.get(path);
  const promise = api(path).then((data) => {
    jsonCache.set(path, data);
    inFlight.delete(path);
    return data;
  }).catch((error) => {
    inFlight.delete(path);
    throw error;
  });
  inFlight.set(path, promise);
  return promise;
}

function destroy(list) {
  while (list.length) {
    try { list.pop().destroy(); } catch { /* already detached */ }
  }
}

function mountLottie(container, animationData, { loop = true, size = '100%' } = {}) {
  container.innerHTML = '';
  if (!window.lottie || !animationData) return null;
  const animation = window.lottie.loadAnimation({
    container,
    renderer: 'svg',
    loop,
    autoplay: loop,
    animationData,
  });
  animation.addEventListener('DOMLoaded', () => {
    if (!loop) animation.goToAndStop(0, true);
  });
  animation.setSubframe?.(false);
  if (size) {
    container.style.width = size;
    container.style.height = size;
  }
  return animation;
}

function previewHasContent(animation, container) {
  const svg = container.querySelector('svg');
  if (!svg) return false;
  const total = Math.max(1, Math.round(animation.totalFrames || 1));
  animation.pause?.();
  let area = 0;
  for (const ratio of [0.02, 0.15, 0.3, 0.45, 0.6, 0.75, 0.9]) {
    animation.goToAndStop(Math.min(total - 1, Math.floor(total * ratio)), true);
    for (const path of svg.querySelectorAll('path')) {
      try {
        const box = path.getBBox();
        if (box.width > 0 && box.height > 0) area += box.width * box.height;
      } catch {
        // Path is not rendered at this frame.
      }
    }
    if (area > 800) return true;
  }
  return area > 800;
}

function showStaticModel(container, giftID, modelID) {
  container.innerHTML = '';
  const image = document.createElement('img');
  image.className = 'attr__still';
  image.alt = '';
  image.src = `/gift-lab/thumbs/models/${giftID}-${modelID}.png?v=20261129`;
  image.style.width = '100%';
  image.style.height = '100%';
  image.style.objectFit = 'contain';
  container.appendChild(image);
}

// Some released model animations have broken parenting/opacity and render an
// empty SVG at every frame. Fall back to the pre-rendered still so the preview
// never shows a blank card.
function guardModelPreview(animation, container, giftID, modelID) {
  const started = Date.now();
  const check = () => {
    if (!animation.isLoaded && !animation.totalFrames) {
      if (Date.now() - started < 4000) window.setTimeout(check, 60);
      return;
    }
    if (previewHasContent(animation, container)) {
      animation.play?.();
      return;
    }
    const index = previewAnims.indexOf(animation);
    if (index >= 0) previewAnims.splice(index, 1);
    try { animation.destroy(); } catch { /* already detached */ }
    showStaticModel(container, giftID, modelID);
  };
  window.setTimeout(check, 60);
}

function pick(list) {
  if (!list?.length) return null;
  return list[Math.floor(Math.random() * list.length)];
}

function selectionItems() {
  const byID = (rows, id) => rows.find((row) => row.id === id) || null;
  return {
    backdrop: byID(state.catalog?.backdrops || [], state.selection.backdrop),
    model: byID(state.catalog?.models || [], state.selection.model),
    pattern: byID(state.catalog?.patterns || [], state.selection.pattern),
  };
}

function updatePreview() {
  if (!state.catalog) return;
  const token = ++state.previewToken;
  const items = selectionItems();
  const backdrop = items.backdrop?.backdrop;

  $('preview-backdrop').style.background = backdrop
    ? `radial-gradient(circle at 35% 22%, ${colorHex(backdrop.center)}, ${colorHex(backdrop.edge)} 72%)`
    : giftGradient(state.gift);
  $('preview-backdrop').style.color = backdrop ? colorHex(backdrop.text) : '#fff';
  $('preview-pattern').style.opacity = backdrop ? '0.26' : '0.18';

  $('value-backdrop').textContent = items.backdrop?.name || 'Не выбран';
  $('value-model').textContent = items.model?.name || 'Не выбрана';
  $('value-pattern').textContent = items.pattern?.name || 'Не выбран';

  destroy(previewAnims);
  const patternBox = $('preview-pattern');
  const modelBox = $('preview-model');
  patternBox.innerHTML = '';
  modelBox.innerHTML = '';

  if (items.pattern) {
    loadAnimationJSON(state.gift.id, 'pattern', items.pattern.id)
      .then((data) => {
        if (token !== state.previewToken) return;
        const animation = mountLottie(patternBox, data, { loop: true });
        if (animation) previewAnims.push(animation);
      })
      .catch(() => {});
  }
  if (items.model) {
    loadAnimationJSON(state.gift.id, 'model', items.model.id)
      .then((data) => {
        if (token !== state.previewToken) return;
        const animation = mountLottie(modelBox, data, { loop: true });
        if (animation) {
          previewAnims.push(animation);
          guardModelPreview(animation, modelBox, state.gift.id, items.model.id);
        }
      })
      .catch(() => {});
  }
}

function setIssueStatus(text, tone = 'hint') {
  issueStatus.className = `status status--${tone}`;
  issueStatus.innerHTML = '';
  const span = document.createElement('span');
  span.className = 'status__text';
  span.textContent = text;
  issueStatus.appendChild(span);
}

function updateIssueState() {
  if (!state.catalog) {
    issueButton.disabled = true;
    return;
  }
  const selected = state.selection.backdrop && state.selection.model && state.selection.pattern;
  const ready = Boolean(initData) && selected;
  issueButton.disabled = !ready;
  if (!initData) {
    setIssueStatus('Откройте Gift Lab через кнопку бота в Telegram.', 'warn');
  } else if (!selected) {
    setIssueStatus('Выберите фон, модель и символ.', 'warn');
  } else {
    setIssueStatus(`Готово к выдаче получателю ${state.recipient || 'Gift Lab'}.`, 'hint');
  }
}

function renderDetail() {
  const gift = state.gift;
  const catalog = state.catalog;
  if (!gift || !catalog) return;
  $('detail-title').textContent = gift.title;
  $('detail-meta').textContent = 'Коллекция' + (gift.enabled ? '' : ' · снят с продажи');
  const hint = $('detail-hint');
  hint.textContent = `Подарок будет выдан получателю ${state.recipient || 'Gift Lab'} и появится в его профиле DeltaGram.`;
  updatePreview();
  updateIssueState();
}

async function openGift(gift) {
  state.gift = gift;
  state.catalog = null;
  listView.hidden = true;
  detailView.hidden = false;
  doneView.hidden = true;
  cta.hidden = false;
  $('detail-title').textContent = gift.title;
  $('detail-meta').textContent = 'Загружаем коллекцию…';
  $('detail-hint').textContent = '';
  setIssueStatus('Проверяем коллекцию…');
  issueButton.disabled = true;
  window.scrollTo({ top: 0, behavior: 'instant' });

  try {
    const catalog = await api(`/gift-lab/api/gifts/${gift.id}/collectibles`);
    state.catalog = catalog;
    const backdrop = pick(catalog.backdrops);
    const model = pick(catalog.models);
    const pattern = pick(catalog.patterns);
    state.selection = {
      backdrop: backdrop?.id || '',
      model: model?.id || '',
      pattern: pattern?.id || '',
    };
    renderDetail();
  } catch (error) {
    state.catalog = null;
    $('detail-meta').textContent = 'Коллекция недоступна';
    $('detail-hint').textContent = error.message;
    setIssueStatus(error.message, 'bad');
    issueButton.disabled = true;
  }
}

function renderList() {
  const query = searchInput.value.trim().toLowerCase();
  const gifts = state.gifts.filter((gift) => !query || gift.title.toLowerCase().includes(query));
  giftsEl.innerHTML = '';
  if (!gifts.length) {
    giftsEl.innerHTML = '<div class="empty">Подарки не найдены</div>';
    return;
  }
  gifts.forEach((gift, index) => {
    const row = document.createElement('button');
    row.type = 'button';
    row.className = 'glist__row';
    row.style.setProperty('--i', String(Math.min(index, 12)));

    const art = document.createElement('span');
    art.className = 'glist__art';
    if (gift.thumb) {
      const image = document.createElement('img');
      image.src = gift.thumb;
      image.alt = '';
      image.loading = 'lazy';
      image.decoding = 'async';
      art.appendChild(image);
    } else {
      const letter = document.createElement('span');
      letter.className = 'glist__letter';
      letter.textContent = gift.title.trim().slice(0, 1).toUpperCase();
      art.appendChild(letter);
    }

    const info = document.createElement('span');
    info.className = 'glist__info';
    const title = document.createElement('span');
    title.className = 'glist__title';
    title.textContent = gift.title;
    const meta = document.createElement('span');
    meta.className = 'glist__meta';
    meta.textContent = 'Коллекция' + (gift.enabled ? '' : ' · снят с продажи');
    info.append(title, meta);

    const chev = document.createElement('span');
    chev.className = 'glist__chev';
    chev.textContent = '›';

    row.append(art, info, chev);
    row.addEventListener('click', () => openGift(gift));
    giftsEl.appendChild(row);
  });
}

function closeSheet() {
  sheet.hidden = true;
  state.sheetKind = '';
  while (thumbObservers.length) thumbObservers.pop().disconnect();
  attrList.innerHTML = '';
}

function attachThumb(container, kind, item) {
  // Model thumbnails are pre-rendered on the grey card background, so the
  // picker opens instantly instead of downloading ~175 KB of Lottie per row.
  if (kind === 'model') {
    const image = document.createElement('img');
    image.className = 'attr__still';
    image.alt = '';
    image.loading = 'lazy';
    image.decoding = 'async';
    image.src = `/gift-lab/thumbs/models/${state.gift.id}-${item.id}.png?v=20261129`;
    image.addEventListener('error', () => {
      image.remove();
      mountLottieFallback(container, kind, item);
    }, { once: true });
    container.appendChild(image);
    return;
  }
  mountLottieFallback(container, kind, item);
}

function mountLottieFallback(container, kind, item) {
  let loaded = false;
  const load = async () => {
    if (loaded) return;
    loaded = true;
    try {
      const data = await loadAnimationJSON(state.gift.id, kind, item.id);
      mountLottie(container, data, { loop: false });
    } catch {
      container.textContent = '·';
    }
  };
  if (!('IntersectionObserver' in window)) {
    load();
    return;
  }
  const observer = new IntersectionObserver((entries) => {
    for (const entry of entries) {
      if (entry.isIntersecting) {
        observer.disconnect();
        load();
      }
    }
  }, { root: sheet, rootMargin: '120px' });
  observer.observe(container);
  thumbObservers.push(observer);
}

function renderSheet(kind) {
  const query = sheetSearch.value.trim().toLowerCase();
  const source = kind === 'backdrop' ? state.catalog.backdrops : kind === 'model' ? state.catalog.models : state.catalog.patterns;
  const items = source.filter((item) => !query || item.name.toLowerCase().includes(query));
  attrList.innerHTML = '';
  if (!items.length) {
    attrList.innerHTML = '<li class="attr__empty">Ничего не найдено</li>';
    return;
  }
  const selected = state.selection[kind];
  for (const item of items) {
    const li = document.createElement('li');
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'attr__item' + (item.id === selected ? ' is-active' : '');

    const thumb = document.createElement('span');
    thumb.className = 'attr__thumb';
    if (kind === 'backdrop') {
      thumb.classList.add('attr__thumb--backdrop');
      thumb.style.background = backdropGradient(item);
    } else {
      attachThumb(thumb, kind, item);
    }

    const info = document.createElement('span');
    info.className = 'attr__info';
    const name = document.createElement('span');
    name.className = 'attr__name';
    name.textContent = item.name;
    if (item.rarity > 0) {
      const rarity = document.createElement('span');
      rarity.className = 'rarity';
      rarity.textContent = `${(item.rarity / 10).toFixed(1).replace('.0', '')}%`;
      name.appendChild(rarity);
    }
    const sub = document.createElement('span');
    sub.className = 'attr__sub';
    sub.textContent = kind === 'backdrop' ? 'Фон коллекции' : kind === 'model' ? 'Модель' : 'Символ';
    info.append(name, sub);

    const check = document.createElement('span');
    check.className = 'attr__check';
    check.textContent = item.id === selected ? '✓' : '';

    button.append(thumb, info, check);
    button.addEventListener('click', () => {
      state.selection[kind] = item.id;
      closeSheet();
      updatePreview();
      updateIssueState();
    });
    li.appendChild(button);
    attrList.appendChild(li);
  }
}

function openSheet(kind) {
  if (!state.catalog) return;
  state.sheetKind = kind;
  sheetTitle.textContent = kind === 'backdrop' ? 'Фон' : kind === 'model' ? 'Модель' : 'Символ';
  sheetSearch.value = '';
  sheetSearch.placeholder = kind === 'backdrop' ? 'Найти фон' : kind === 'model' ? 'Найти модель' : 'Найти символ';
  sheet.hidden = false;
  renderSheet(kind);
  setTimeout(() => sheetSearch.focus(), 60);
}

function showList() {
  state.gift = null;
  state.catalog = null;
  detailView.hidden = true;
  doneView.hidden = true;
  listView.hidden = false;
  cta.hidden = true;
  closeSheet();
  destroy(previewAnims);
  window.scrollTo({ top: 0, behavior: 'instant' });
}

function showDone(result) {
  detailView.hidden = true;
  cta.hidden = true;
  doneView.hidden = false;
  const count = result?.result?.details?.collectible_issued;
  $('done-text').textContent = `Подарок выдан ${result.recipient || state.recipient || 'получателю Gift Lab'}.${count ? ` Всего выпущено: ${count}.` : ''}`;
  window.scrollTo({ top: 0, behavior: 'instant' });
}

async function issueGift() {
  if (!state.gift || !state.catalog) return;
  issueButton.disabled = true;
  setIssueStatus('Выдаём подарок…');
  try {
    const result = await fetch('/gift-lab/api/issue', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Init-Data': initData },
      body: JSON.stringify({
        giftID: state.gift.id,
        model: state.selection.model,
        pattern: state.selection.pattern,
        backdrop: state.selection.backdrop,
        requestID: crypto.randomUUID(),
      }),
    }).then(async (response) => {
      const data = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(data.error || `Ошибка ${response.status}`);
      return data;
    });
    state.catalog.issued += 1;
    showDone(result);
  } catch (error) {
    showToast(error.message, true);
    setIssueStatus(error.message, 'bad');
    updateIssueState();
  }
}

async function load() {
  if (!initData) {
    listStatus.className = 'error-box';
    listStatus.textContent = 'Откройте Gift Lab через кнопку бота в Telegram — список доступен только владельцу.';
    return;
  }
  try {
    const data = await api('/gift-lab/api/gifts');
    state.gifts = data.gifts || [];
    state.recipient = data.recipient || '';
    listStatus.hidden = true;
    renderList();
  } catch (error) {
    listStatus.className = 'error-box';
    listStatus.textContent = error.message;
  }
}

searchInput.addEventListener('input', renderList);
$('back').addEventListener('click', showList);
$('done-list').addEventListener('click', showList);
$('done-more').addEventListener('click', () => {
  const gift = state.gift;
  doneView.hidden = true;
  if (gift) openGift(gift);
  else showList();
});
document.querySelectorAll('[data-kind]').forEach((button) => {
  button.addEventListener('click', () => openSheet(button.dataset.kind));
});
sheetSearch.addEventListener('input', () => {
  if (state.sheetKind) renderSheet(state.sheetKind);
});
sheet.addEventListener('click', (event) => {
  if (event.target === sheet) closeSheet();
});
issueButton.addEventListener('click', issueGift);
$('roll').addEventListener('click', () => {
  if (!state.catalog) return;
  const backdrop = pick(state.catalog.backdrops);
  const model = pick(state.catalog.models);
  const pattern = pick(state.catalog.patterns);
  state.selection = {
    backdrop: backdrop?.id || '',
    model: model?.id || '',
    pattern: pattern?.id || '',
  };
  updatePreview();
  updateIssueState();
});

load();
