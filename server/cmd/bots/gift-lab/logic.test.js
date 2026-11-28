import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHmac } from 'node:crypto';
import { issueCommandID, validAttributeID, verifyInitData } from './logic.js';

test('accepts only positive decimal attribute ids', () => {
  assert.equal(validAttributeID('3692'), true);
  assert.equal(validAttributeID('0'), false);
  assert.equal(validAttributeID('-1'), false);
  assert.equal(validAttributeID('<script>'), false);
  assert.equal(validAttributeID(3692), false);
});

test('issue command id is stable and scope-bound', () => {
  const a = issueCommandID(5299713400, '86', '3692', '11540', '4021', 'abcd1234');
  const b = issueCommandID(5299713400, '86', '3692', '11540', '4021', 'abcd1234');
  const c = issueCommandID(5299713400, '86', '3693', '11540', '4021', 'abcd1234');
  assert.equal(a, b);
  assert.notEqual(a, c);
  assert.match(a, /^giftlab-[0-9a-f]{32}$/);
});

test('checks Telegram signature, user and freshness', () => {
  const token = 'test:token';
  const now = 1_700_000_000_000;
  const fields = `auth_date=1700000000&user=${encodeURIComponent('{"id":42}')}`;
  const secret = createHmac('sha256', 'WebAppData').update(token).digest();
  const hash = createHmac('sha256', secret).update('auth_date=1700000000\nuser={"id":42}').digest('hex');
  assert.equal(verifyInitData(`${fields}&hash=${hash}`, token, now).id, 42);
  assert.equal(verifyInitData(`${fields}&hash=${hash}`, 'wrong', now), null);
  assert.equal(verifyInitData(`${fields}&hash=${hash}`, token, now + 3_700_000), null);
  assert.equal(verifyInitData(`${fields}&hash=${hash}&user=evil`, token, now), null);
});
