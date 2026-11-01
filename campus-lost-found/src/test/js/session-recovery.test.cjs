const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/app.js'), 'utf8');
const apiSource = source.slice(0, source.indexOf('\nfunction openDialog'));

function response(status, data) {
  return {status, ok: status >= 200 && status < 300, json: async () => data};
}

function clientFor(responses) {
  const calls = [];
  const nodes = new Map();
  const context = {
    URLSearchParams,
    document: {querySelector(selector) {
      if (!nodes.has(selector)) nodes.set(selector, {open: false, textContent: ''});
      return nodes.get(selector);
    }},
    renderAccount() {},
    openDialog(id) { context.document.querySelector('#' + id).open = true; },
    async fetch(url, options) {
      calls.push({url, options});
      assert.ok(responses.length, 'Unexpected extra request');
      const next = responses.shift();
      if (next instanceof Error) throw next;
      return next;
    }
  };
  vm.runInNewContext(apiSource + '\nglobalThis.client = {api, state};', context);
  context.client.state.csrf = {headerName: 'X-CSRF-TOKEN', token: 'old-token'};
  context.client.state.user = {name: 'Mayank'};
  return {...context.client, calls, nodes};
}

const csrfFailure = () => response(403, {code: 'CSRF_INVALID', message: 'Expired token'});
const freshToken = () => response(200, {headerName: 'X-CSRF-TOKEN', token: 'new-token'});

test('refreshes a rejected token once and retains the submitted evidence', async () => {
  const client = clientFor([csrfFailure(), freshToken(), response(201, {status: 'PENDING'})]);
  const body = JSON.stringify({privateDetails: 'A hidden mark inside the bag.'});
  const result = await client.api('/reports/3/claims', {method: 'POST', body});
  assert.equal(result.status, 'PENDING');
  assert.equal(client.calls.length, 3);
  assert.equal(client.calls[1].url, '/api/csrf');
  assert.equal(client.calls[2].options.headers['X-CSRF-TOKEN'], 'new-token');
  assert.equal(client.calls[2].options.body, body);
});

test('prompts for demo re-entry after the session has disappeared', async () => {
  const client = clientFor([csrfFailure(), freshToken(), response(401, {code: 'SIGN_IN_REQUIRED'})]);
  await assert.rejects(client.api('/reports/3/claims', {method: 'POST', body: '{}'}), /Choose a demo student again/);
  assert.equal(client.state.user, null);
  assert.equal(client.state.csrf, null);
  assert.equal(client.nodes.get('#auth-dialog').open, true);
  assert.match(client.nodes.get('#auth-dialog .form-error').textContent, /details have been kept/);
  assert.equal(client.calls.length, 3);
});

test('does not retry permission failures', async () => {
  const client = clientFor([response(403, {code: 'ACCESS_DENIED', message: 'Not allowed'})]);
  await assert.rejects(client.api('/restricted', {method: 'POST', body: '{}'}), /Not allowed/);
  assert.equal(client.calls.length, 1);
});

test('does not repeat a request after a network failure with an unknown outcome', async () => {
  const client = clientFor([new Error('Network disconnected')]);
  await assert.rejects(client.api('/reports', {method: 'POST', body: '{}'}), /Network disconnected/);
  assert.equal(client.calls.length, 1);
});

test('stops after one unsuccessful token refresh', async () => {
  const client = clientFor([csrfFailure(), freshToken(), csrfFailure()]);
  await assert.rejects(client.api('/reports', {method: 'POST', body: '{}'}), /Expired token/);
  assert.equal(client.calls.length, 3);
});
