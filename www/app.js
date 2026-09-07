// QoderWake Mobile —— qoderwake-cn WebUI 的 Android WebView 壳
// 单主机管理：默认 https://qoderwake.rvs-lighting.com/，可编辑；保存到
// Capacitor Preferences。打开前清掉 backButton 监听（避免跨页残留）。
const Cap = window.Capacitor;
const KEY = 'qoderwake-host';
const DEFAULT_HOST = {
  name: 'QoderWake',
  url: 'https://qoderwake.rvs-lighting.com/',
};

const $ = (id) => document.getElementById(id);
const listEl = $('host-list');
const emptyTip = $('empty-tip');
const dialog = $('host-dialog');
const form = $('host-form');
const nameInput = $('host-name');
const urlInput = $('host-url');
const formError = $('form-error');

let currentHost = null;

// 在线探测：no-cors 下能收到任意响应（含 302/401）即在线，超时/网络错误即离线
async function probe(url) {
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

function renderDot(up) {
  const dot = listEl.querySelector('.status-dot');
  if (!dot) return;
  dot.classList.remove('checking', 'up', 'down');
  dot.classList.add(up ? 'up' : 'down');
}

async function probeAndPaint() {
  if (!currentHost) return;
  renderDot(await probe(currentHost.url));
}

function render(host) {
  currentHost = host;
  listEl.textContent = '';
  emptyTip.hidden = !!host;

  if (!host) {
    $('add-btn').setAttribute('aria-label', '添加主机');
    return;
  }
  $('add-btn').setAttribute('aria-label', '编辑主机地址');

  const li = document.createElement('li');
  li.className = 'host-card';

  const info = document.createElement('div');
  info.className = 'host-info';
  const name = document.createElement('div');
  name.className = 'host-name';
  const dot = document.createElement('span');
  dot.className = 'status-dot checking';
  name.append(dot, document.createTextNode(host.name));
  const url = document.createElement('div');
  url.className = 'host-url';
  url.textContent = host.url;
  info.append(name, url);

  const openBtn = document.createElement('button');
  openBtn.className = 'open-btn primary';
  openBtn.textContent = '打开';
  openBtn.addEventListener('click', (e) => {
    e.stopPropagation();
    openHost(host);
  });

  const editBtn = document.createElement('button');
  editBtn.className = 'edit-btn';
  editBtn.textContent = '编辑';
  editBtn.addEventListener('click', (e) => {
    e.stopPropagation();
    openDialog(host);
  });

  const topRow = document.createElement('div');
  topRow.className = 'card-top';
  topRow.append(info);

  const actions = document.createElement('div');
  actions.className = 'card-actions';
  actions.append(editBtn, openBtn);

  li.addEventListener('click', () => openHost(host));
  li.append(topRow, actions);
  listEl.appendChild(li);
}

function openDialog(host) {
  nameInput.value = host ? host.name : '';
  urlInput.value = host ? host.url : '';
  $('dialog-title').textContent = host ? '编辑主机' : '添加主机';
  $('save-btn').textContent = '保存';
  $('delete-btn').hidden = !host;
  formError.hidden = true;
  dialog.showModal();
}

function normalizeUrl(raw) {
  let u;
  try {
    u = new URL(raw.trim());
  } catch {
    return null;
  }
  if (u.protocol !== 'http:' && u.protocol !== 'https:') return null;
  // 强制带尾斜杠，避免 /assets 解析为相对于 origin 的根
  let href = u.href;
  if (!href.endsWith('/')) href += '/';
  return href;
}

form.addEventListener('submit', async (e) => {
  e.preventDefault();
  const name = nameInput.value.trim();
  const url = normalizeUrl(urlInput.value);
  if (!name || !url) {
    formError.textContent = '请填写名称，地址需为 http(s):// 开头的有效 URL';
    formError.hidden = false;
    return;
  }
  await saveHost({ name, url });
  render({ name, url });
  dialog.close();
  probeAndPaint();
});

$('cancel-btn').addEventListener('click', () => dialog.close());
$('add-btn').addEventListener('click', () => openDialog(currentHost));

$('delete-btn').addEventListener('click', async () => {
  if (!currentHost) return;
  if (!window.confirm('确定删除主机？')) return;
  await clearHost();
  render(null);
  dialog.close();
});

async function loadHost() {
  try {
    const r = await Cap.nativePromise('Preferences', 'get', { key: KEY });
    if (r && r.value) {
      try {
        return JSON.parse(r.value);
      } catch { /* 损坏回退默认 */ }
    }
  } catch { /* 首次启动没值 */ }
  await saveHost(DEFAULT_HOST);
  return DEFAULT_HOST;
}

function saveHost(host) {
  return Cap.nativePromise('Preferences', 'set', { key: KEY, value: JSON.stringify(host) });
}

function clearHost() {
  return Cap.nativePromise('Preferences', 'remove', { key: KEY });
}

async function openHost(host) {
  try {
    await Cap.nativePromise('Preferences', 'set', { key: 'qoderwake-current-host', value: host.name });
  } catch { /* 忽略 */ }
  // 必须先清掉本页注册的 backButton 监听再导航：
  // 监听器跨页残留会让远程页的返回键失灵（远程页没有 window.Capacitor）。
  try {
    await Cap.nativePromise('App', 'removeAllListeners');
  } catch { /* 忽略 */ }
  window.location.href = host.url;
}

function registerBackHandler() {
  Cap.nativeCallback('App', 'addListener', { eventName: 'backButton' }, (ev) => {
    if (ev && ev.canGoBack) {
      window.history.back();
    } else {
      Cap.nativePromise('App', 'exitApp');
    }
  });
}

document.addEventListener('DOMContentLoaded', async () => {
  const host = await loadHost();
  render(host);
  probeAndPaint();
  registerBackHandler();
  setInterval(probeAndPaint, 30000);
  document.addEventListener('visibilitychange', () => {
    if (!document.hidden) probeAndPaint();
  });
});