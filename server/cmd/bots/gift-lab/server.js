import http from 'node:http';
import { readFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { verifyInitData, validAttributeID, issueCommandID } from './logic.js';

const token = process.env.GIFT_LAB_BOT_TOKEN || '';
const owners = new Set((process.env.GIFT_LAB_OWNER_IDS || '').split(',').map((v) => v.trim()).filter(Boolean));
const publicUrl = process.env.GIFT_LAB_PUBLIC_URL || '';
const listen = process.env.GIFT_LAB_LISTEN || '127.0.0.1';
const port = Number(process.env.GIFT_LAB_PORT || 2810);
const adminApi = (process.env.GIFT_LAB_ADMIN_API || '').replace(/\/+$/, '');
const adminToken = process.env.GIFT_LAB_ADMIN_TOKEN || '';
const grantUsername = (process.env.GIFT_LAB_GRANT_USERNAME || '').trim().replace(/^@/, '');
const grantUserID = Number(process.env.GIFT_LAB_GRANT_USER_ID || 0);

const html = await readFile(fileURLToPath(new URL('./web/index.html', import.meta.url)));
const js = await readFile(fileURLToPath(new URL('./web/app.js', import.meta.url)));
const css = await readFile(fileURLToPath(new URL('./web/style.css', import.meta.url)));
const lottie = await readFile(fileURLToPath(new URL('./web/lottie.min.js', import.meta.url)));

const recentIssues = new Map();
const ownerCache = { id: 0, at: 0 };

function json(res, status, data) {
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff',
  });
  res.end(JSON.stringify(data));
}

function text(res, status, body, type = 'text/plain; charset=utf-8') {
  res.writeHead(status, { 'Content-Type': type, 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' });
  res.end(body);
}

async function telegram(method, params) {
  const response = await fetch(`https://api.telegram.org/bot${token}/${method}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(params),
    // getUpdates long-polls for 25s, so the HTTP timeout must stay above it.
    signal: AbortSignal.timeout(40000),
  });
  const body = await response.json();
  if (!response.ok || !body.ok) throw new Error(`Telegram API ${method}: ${body.description || response.status}`);
  return body.result;
}

async function adminRequest(method, path, body) {
  if (!adminApi || !adminToken) throw new Error('Gift Lab admin API is not configured');
  const response = await fetch(adminApi + path, {
    method,
    headers: {
      Authorization: `Bearer ${adminToken}`,
      Accept: 'application/json',
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(30000),
  });
  const raw = await response.text();
  let payload = null;
  try {
    payload = raw ? JSON.parse(raw) : null;
  } catch {
    payload = { error: raw.slice(0, 300) };
  }
  if (!response.ok) {
    const message = payload?.error || payload?.message || `admin API ${response.status}`;
    throw new Error(message);
  }
  return payload;
}

function isOwner(userId) {
  return owners.has(String(userId));
}

function authenticate(req) {
  const initData = String(req.headers['x-init-data'] || '');
  const user = verifyInitData(initData, token);
  if (!user || !isOwner(user.id)) return null;
  return user;
}

async function ownerUserID() {
  if (grantUsername) {
    if (ownerCache.id > 0 && Date.now() - ownerCache.at < 300000) return ownerCache.id;
    const result = await adminRequest('POST', '/v1/accounts/resolve-by-username', { username: grantUsername });
    if (!result?.found || !result.user_id) throw new Error(`Получатель @${grantUsername} не найден в DeltaGram`);
    const id = Number(result.user_id);
    ownerCache.id = id;
    ownerCache.at = Date.now();
    return id;
  }
  if (grantUserID > 0) return grantUserID;
  throw new Error('Получатель подарка не настроен');
}

function parseGiftID(value) {
  const id = String(value || '');
  if (!/^[1-9][0-9]{0,18}$/.test(id)) return 0;
  return id;
}

function giftSummary(raw) {
  const total = Number(raw.upgrade_total || 0);
  const issued = Number(raw.upgrade_issued || 0);
  const background = raw.background || {};
  const id = String(raw.gift_id || '');
  const thumbFile = new URL(`./web/thumbs/gifts/${id}.png`, import.meta.url);
  return {
    id,
    title: raw.title || 'Подарок',
    enabled: Boolean(raw.enabled),
    stars: Number(raw.stars || 0),
    upgradeStars: Number(raw.upgrade_stars || 0),
    total,
    issued,
    remaining: Math.max(0, total - issued),
    supply: raw.supply_total ?? null,
    thumb: existsSync(thumbFile) ? `/gift-lab/thumbs/gifts/${id}.png?v=20261128` : '',
    background: {
      center: Number(background.center_color || 0),
      edge: Number(background.edge_color || 0),
      text: Number(background.text_color || 0),
    },
  };
}

async function listGifts() {
  const result = await adminRequest('GET', '/v1/gifts');
  const gifts = (result?.gifts || [])
    .map(giftSummary)
    .filter((gift) => gift.total > 0)
    .sort((a, b) => (b.remaining > 0) - (a.remaining > 0) || a.title.localeCompare(b.title, 'ru'));
  return {
    gifts,
    recipient: grantUsername ? `@${grantUsername}` : grantUserID > 0 ? String(grantUserID) : '',
  };
}

async function collectibles(giftID) {
  const result = await adminRequest('GET', `/v1/gifts/${giftID}/collectibles`);
  if (!result?.found) throw new Error('У этого подарка нет опубликованной коллекции');
  const map = (rows) => (rows || []).map((row) => ({
    id: String(row.id || ''),
    name: row.name || 'Без названия',
    rarity: Number(row.rarity_permille || 0),
    crafted: Boolean(row.crafted),
    backdrop: row.backdrop_id
      ? {
          id: Number(row.backdrop_id),
          center: Number(row.center_color || 0),
          edge: Number(row.edge_color || 0),
          pattern: Number(row.pattern_color || 0),
          text: Number(row.text_color || 0),
        }
      : null,
  }));
  return {
    giftID: String(result.gift_id || giftID),
    total: Number(result.supply_total || 0),
    issued: Number(result.issued || 0),
    upgradeStars: Number(result.upgrade_stars || 0),
    slugPrefix: result.slug_prefix || '',
    models: map(result.models),
    patterns: map(result.patterns),
    backdrops: map(result.backdrops),
  };
}

async function proxyAnimation(res, path) {
  const response = await fetch(adminApi + path, {
    headers: { Authorization: `Bearer ${adminToken}`, Accept: 'application/json' },
    signal: AbortSignal.timeout(30000),
  });
  if (!response.ok) {
    const body = await response.text();
    return json(res, response.status === 404 ? 404 : 502, { error: body.slice(0, 200) || 'Анимация недоступна' });
  }
  const data = await response.text();
  res.writeHead(200, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'private, max-age=300',
    'X-Content-Type-Options': 'nosniff',
  });
  res.end(data);
}

async function issue(body, user) {
  const giftID = parseGiftID(body?.giftID);
  const model = String(body?.model || '');
  const pattern = String(body?.pattern || '');
  const backdrop = String(body?.backdrop || '');
  const requestID = String(body?.requestID || '');
  if (!giftID) throw new Error('Выберите подарок');
  if (!validAttributeID(model) || !validAttributeID(pattern) || !validAttributeID(backdrop)) {
    throw new Error('Выберите фон, модель и символ');
  }
  if (!/^[a-zA-Z0-9-]{8,64}$/.test(requestID)) throw new Error('Повторите попытку');

  const now = Date.now();
  for (const [key, at] of recentIssues) {
    if (now - at > 600000) recentIssues.delete(key);
  }
  const dedupe = `${user.id}:${giftID}:${model}:${pattern}:${backdrop}:${requestID}`;
  if (recentIssues.has(dedupe)) throw new Error('Этот подарок уже выдан');

  const catalog = await collectibles(giftID);
  if (catalog.total > 0 && catalog.issued >= catalog.total) throw new Error('Тираж коллекции уже полностью выпущен');
  if (!catalog.models.some((item) => item.id === model)) throw new Error('Выбранная модель не принадлежит этому подарку');
  if (!catalog.patterns.some((item) => item.id === pattern)) throw new Error('Выбранный символ не принадлежит этому подарку');
  if (!catalog.backdrops.some((item) => item.id === backdrop)) throw new Error('Выбранный фон не принадлежит этому подарку');

  const recipient = await ownerUserID();
  const commandID = issueCommandID(user.id, giftID, model, pattern, backdrop, requestID);
  const result = await adminRequest('POST', '/v1/gifts/give', {
    command_id: commandID,
    actor: `gift-lab:${user.id}`,
    reason: 'Gift Lab: выдача выпущенного коллекционного подарка',
    sender_user_id: 777000,
    user_id: recipient,
    gift_id: Number(giftID),
    upgrade: true,
    allow_disabled: true,
    model_attribute_id: Number(model),
    pattern_attribute_id: Number(pattern),
    backdrop_attribute_id: Number(backdrop),
    message: '',
  });
  recentIssues.set(dedupe, now);
  return {
    ok: true,
    commandID,
    recipient: grantUsername ? `@${grantUsername}` : String(recipient),
    result,
  };
}

const server = http.createServer(async (req, res) => {
  const path = new URL(req.url, 'http://localhost').pathname;
  try {
    if (req.method === 'GET' && (path === '/' || path.endsWith('/gift-lab/'))) {
      res.writeHead(200, {
        'Content-Type': 'text/html; charset=utf-8',
        'Cache-Control': 'no-store',
        'X-Content-Type-Options': 'nosniff',
        'Content-Security-Policy': "default-src 'self'; script-src 'self' https://telegram.org; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; frame-ancestors https://web.telegram.org https://*.telegram.org",
      });
      return res.end(html);
    }
    const asset = path.match(/^(?:\/gift-lab)?\/(app\.js|style\.css|lottie\.min\.js)$/);
    if (req.method === 'GET' && asset) {
      const type = asset[1] === 'app.js'
        ? 'text/javascript; charset=utf-8'
        : asset[1] === 'style.css' ? 'text/css; charset=utf-8' : 'text/javascript; charset=utf-8';
      return text(res, 200, asset[1] === 'app.js' ? js : asset[1] === 'style.css' ? css : lottie, type);
    }

    const thumb = path.match(/^\/gift-lab\/thumbs\/(gifts|models)\/([0-9]+(?:-[0-9]+)?)\.(png|webp)$/);
    if (req.method === 'GET' && thumb) {
      const file = fileURLToPath(new URL(`./web/thumbs/${thumb[1]}/${thumb[2]}.${thumb[3]}`, import.meta.url));
      try {
        const data = await readFile(file);
        res.writeHead(200, {
          'Content-Type': thumb[3] === 'png' ? 'image/png' : 'image/webp',
          'Cache-Control': 'public, max-age=31536000, immutable',
          'X-Content-Type-Options': 'nosniff',
        });
        return res.end(data);
      } catch {
        return json(res, 404, { error: 'Нет превью' });
      }
    }

    const apiPath = path.replace(/^\/gift-lab/, '');
    if (!apiPath.startsWith('/api/')) return json(res, 404, { error: 'Не найдено' });
    const user = authenticate(req);
    if (!user) return json(res, 403, { error: 'Откройте Gift Lab через кнопку бота и только с аккаунта владельца' });

    if (req.method === 'GET' && apiPath === '/api/gifts') {
      return json(res, 200, await listGifts());
    }
    const collectibleMatch = apiPath.match(/^\/api\/gifts\/([1-9][0-9]{0,18})\/collectibles$/);
    if (req.method === 'GET' && collectibleMatch) {
      return json(res, 200, await collectibles(collectibleMatch[1]));
    }
    const giftAnimMatch = apiPath.match(/^\/api\/gifts\/([1-9][0-9]{0,18})\/animation$/);
    if (req.method === 'GET' && giftAnimMatch) {
      return await proxyAnimation(res, `/v1/gifts/${giftAnimMatch[1]}/animation`);
    }
    const attrAnimMatch = apiPath.match(/^\/api\/gifts\/([1-9][0-9]{0,18})\/collectibles\/(model|pattern)\/([1-9][0-9]{0,18})\/animation$/);
    if (req.method === 'GET' && attrAnimMatch) {
      return await proxyAnimation(res, `/v1/gifts/${attrAnimMatch[1]}/collectibles/${attrAnimMatch[2]}/${attrAnimMatch[3]}/animation`);
    }

    if (req.method === 'POST' && apiPath === '/api/issue') {
      if (Number(req.headers['content-length']) > 8000) return json(res, 413, { error: 'Слишком большой запрос' });
      let raw = '';
      for await (const chunk of req) {
        raw += chunk;
        if (raw.length > 8000) return json(res, 413, { error: 'Слишком большой запрос' });
      }
      let body;
      try {
        body = JSON.parse(raw);
      } catch {
        return json(res, 400, { error: 'Некорректный запрос' });
      }
      return json(res, 200, await issue(body, user));
    }

    return json(res, 404, { error: 'Не найдено' });
  } catch (error) {
    console.error('request:', error.message);
    return json(res, 502, { error: error.message || 'Ошибка сервера' });
  }
});

server.listen(port, listen, () => console.log(`Gift Lab listening at ${listen}:${port}`));

if (token && /^https:\/\//.test(publicUrl)) {
  let offset = 0;
  async function poll() {
    while (true) {
      try {
        const updates = await telegram('getUpdates', { offset, timeout: 25, allowed_updates: ['message'] }, AbortSignal.timeout(35000));
        for (const update of updates) {
          offset = update.update_id + 1;
          const msg = update.message;
          if (!msg?.from || msg.chat?.type !== 'private') continue;
          if (!isOwner(msg.from.id)) {
            if (/^\/(start|help|id)(?:\s|$)/.test(msg.text || '')) {
              await telegram('sendMessage', {
                chat_id: msg.chat.id,
                text: `Ваш Telegram ID: ${msg.from.id}\n\nДоступ к Gift Lab открыт только владельцам.`,
              });
            }
            continue;
          }
          if (/^\/(start|help)(?:\s|$)/.test(msg.text || '')) {
            await telegram('sendMessage', {
              chat_id: msg.chat.id,
              text: '🎁 Gift Lab — выдача уже выпущенных коллекционных подарков DeltaGram.\n\nВыберите подарок и коллекцию, настройте фон, модель и символ, затем нажмите «Выдать». Подарок придёт получателю, указанному в настройках Gift Lab.',
              reply_markup: { inline_keyboard: [[{ text: 'Открыть Gift Lab', web_app: { url: publicUrl } }]] },
            });
          }
        }
      } catch (error) {
        console.error('poll:', error.message);
        await new Promise((resolve) => setTimeout(resolve, 3000));
      }
    }
  }
  void poll();
} else {
  console.log('Bot polling disabled: configure token and HTTPS public URL.');
}
