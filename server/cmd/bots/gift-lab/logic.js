import { createHash, createHmac, timingSafeEqual } from 'node:crypto';

const ATTRIBUTE_ID = /^[1-9][0-9]{0,18}$/;

export function validAttributeID(value) {
  return typeof value === 'string' && ATTRIBUTE_ID.test(value);
}

export function issueCommandID(userID, giftID, modelID, patternID, backdropID, requestID) {
  const digest = createHash('sha256')
    .update(`${userID}:${giftID}:${modelID}:${patternID}:${backdropID}:${requestID}`)
    .digest('hex')
    .slice(0, 32);
  return `giftlab-${digest}`;
}

export function verifyInitData(initData, token, now = Date.now()) {
  if (typeof initData !== 'string' || !token || initData.length > 8192) return null;
  const params = new URLSearchParams(initData);
  const hash = params.get('hash');
  if (!hash || !/^[0-9a-f]{64}$/i.test(hash) || [...params.keys()].some((key) => params.getAll(key).length !== 1)) return null;
  const check = [...params.entries()].filter(([key]) => key !== 'hash')
    .sort(([a], [b]) => a.localeCompare(b)).map(([key, value]) => `${key}=${value}`).join('\n');
  const secret = createHmac('sha256', 'WebAppData').update(token).digest();
  const expected = createHmac('sha256', secret).update(check).digest();
  if (!timingSafeEqual(expected, Buffer.from(hash, 'hex'))) return null;
  const authDate = Number(params.get('auth_date'));
  if (!Number.isSafeInteger(authDate) || Math.abs(now / 1000 - authDate) > 3600) return null;
  try {
    const user = JSON.parse(params.get('user') || '');
    if (!Number.isSafeInteger(user.id) || user.id <= 0) return null;
    return user;
  } catch {
    return null;
  }
}
