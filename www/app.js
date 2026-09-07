// QoderWake Mobile — multi-host launcher.
// Only administrator-approved HTTPS endpoints are persisted and opened.
const Cap = window.Capacitor;
const HOSTS_KEY = 'qoderwake-hosts';
const LEGACY_HOST_KEY = 'qoderwake-host';

const $ = (id) => document.getElementById(id);
const listEl = $('host-list');
const emptyTip = $('empty-tip');
const dialog = $('host-dialog');
const form = $('host-form');
const nameInput = $('host-name');
const urlInput = $('host-url');
const formError = $('form-error');

let editingId = null;
let currentHosts = [];

async function probeHost(url) {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), 6000);
  try {
    await fetch(url, { mode: 'no-cors', cache: 'no-store', signal: ctrl.signal });
    return true;
  } catch {
    return false;
  } finally {
    clearTimeout(timer);
  }
}

function updateDot(url, up) {
  document.querySelectorAll(`.status-dot[data-url="${CSS.escape(url)}"]`).forEach((dot) => {
    dot.classList.remove('checking', 'up', 'down');
    dot.classList.add(up ? 'up' : 'down');
  });
}

function probeAll() {
  for (const host of currentHosts) {
    probeHost(host.url).then((up) => updateDot(host.url, up));
  }
}

function normalizeUrl(raw) {
  let url;
  try {
    url = new URL(raw.trim());
  } catch {
    return null;
  }
  if (url.protocol !== 'https:' || url.username || url.password) return null;
  url.hash = '';
  if (!url.pathname.endsWith('/')) url.pathname += '/';
  return url.href;
}

function normalizeStoredHost(host) {
  if (!host || typeof host.name !== 'string' || typeof host.url !== 'string') return null;
  const url = normalizeUrl(host.url);
  const name = host.name.trim();
  if (!name || !url) return null;
  return { id: String(host.id || Date.now().toString(36)), name, url };
}

async function loadHosts() {
  const stored = await Cap.nativePromise('Preferences', 'get', { key: HOSTS_KEY });
  if (stored && stored.value) {
    try {
      return JSON.parse(stored.value).map(normalizeStoredHost).filter(Boolean);
    } catch { /* corrupted storage falls through to legacy migration */ }
  }

  const legacy = await Cap.nativePromise('Preferences', 'get', { key: LEGACY_HOST_KEY });
  if (legacy && legacy.value) {
    try {
      const host = normalizeStoredHost(JSON.parse(legacy.value));
      if (host) {
        const migrated = [{ ...host, id: host.id || 'migrated-1' }];
        await saveHosts(migrated);
        await Cap.nativePromise('Preferences', 'remove', { key: LEGACY_HOST_KEY });
        return migrated;
      }
    } catch { /* invalid legacy value is ignored */ }
  }
  return [];
}

function saveHosts(list) {
  return Cap.nativePromise('Preferences', 'set', { key: HOSTS_KEY, value: JSON.stringify(list) });
}

function render(list) {
  currentHosts = list;
  listEl.textContent = '';
  emptyTip.hidden = list.length > 0;

  for (const host of list) {
    const li = document.createElement('li');
    li.className = 'host-card';

    const info = document.createElement('div');
    info.className = 'host-info';
    const name = document.createElement('div');
    name.className = 'host-name';
    const dot = document.createElement('span');
    dot.className = 'status-dot checking';
    dot.dataset.url = host.url;
    name.append(dot, document.createTextNode(host.name));
    const url = document.createElement('div');
    url.className = 'host-url';
    url.textContent = host.url;
    info.append(name, url);

    const topRow = document.createElement('div');
    topRow.className = 'card-top';
    topRow.append(info);

    const editBtn = document.createElement('button');
    editBtn.className = 'edit-btn';
    editBtn.textContent = '编辑';
    editBtn.addEventListener('click', (event) => {
      event.stopPropagation();
      openDialog(host);
    });

    const openBtn = document.createElement('button');
    openBtn.className = 'open-btn';
    openBtn.textContent = '打开';

    const actions = document.createElement('div');
    actions.className = 'card-actions';
    actions.append(editBtn, openBtn);

    li.addEventListener('click', () => openHost(host));
    li.append(topRow, actions);
    listEl.appendChild(li);
  }
  probeAll();
}

function openDialog(host) {
  editingId = host ? host.id : null;
  $('dialog-title').textContent = host ? '编辑主机' : '添加主机';
  nameInput.value = host ? host.name : '';
  urlInput.value = host ? host.url : '';
  formError.hidden = true;
  $('delete-btn').hidden = !host;
  dialog.showModal();
}

form.addEventListener('submit', async (event) => {
  event.preventDefault();
  const name = nameInput.value.trim();
  const url = normalizeUrl(urlInput.value);
  if (!name || !url) {
    formError.textContent = '请填写名称，并使用不含账号密码的 HTTPS 地址';
    formError.hidden = false;
    return;
  }

  const list = await loadHosts();
  if (editingId) {
    const index = list.findIndex((host) => host.id === editingId);
    if (index >= 0) list[index] = { ...list[index], name, url };
  } else {
    list.push({ id: Date.now().toString(36), name, url });
  }
  await saveHosts(list);
  render(list);
  dialog.close();
});

$('cancel-btn').addEventListener('click', () => dialog.close());
$('add-btn').addEventListener('click', () => openDialog(null));

$('delete-btn').addEventListener('click', async () => {
  if (!editingId || !window.confirm('确定删除这台主机？')) return;
  const list = (await loadHosts()).filter((host) => host.id !== editingId);
  await saveHosts(list);
  render(list);
  dialog.close();
});

async function openHost(host) {
  const origin = new URL(host.url).origin;
  await Promise.allSettled([
    Cap.nativePromise('Preferences', 'set', { key: 'qoderwake-current-host', value: host.name }),
    Cap.nativePromise('Preferences', 'set', { key: 'qoderwake-current-host-origin', value: origin }),
  ]);
  try {
    await Cap.nativePromise('App', 'removeAllListeners');
  } catch { /* remote page uses Android's normal back navigation */ }
  window.location.href = host.url;
}

function registerBackHandler() {
  Cap.nativeCallback('App', 'addListener', { eventName: 'backButton' }, (event) => {
    if (event && event.canGoBack) {
      window.history.back();
    } else {
      Cap.nativePromise('App', 'exitApp');
    }
  });
}

document.addEventListener('DOMContentLoaded', async () => {
  render(await loadHosts());
  registerBackHandler();
  setInterval(probeAll, 30000);
  document.addEventListener('visibilitychange', () => {
    if (!document.hidden) probeAll();
  });
});
