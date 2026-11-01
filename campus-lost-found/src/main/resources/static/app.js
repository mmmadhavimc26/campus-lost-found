const $ = selector => document.querySelector(selector);
const state = {
  user: null,
  view: 'browse',
  kind: 'ALL',
  reports: [],
  csrf: null,
  register: false,
  claimId: null
};

function label(value) {
  return value.toLowerCase().split('_').map(word => word[0].toUpperCase() + word.slice(1)).join(' ');
}

function element(tag, className, text) {
  const result = document.createElement(tag);
  if (className) result.className = className;
  if (text !== undefined) result.textContent = text;
  return result;
}

function total(number, noun) {
  return `${number} ${noun}${number === 1 ? '' : 's'}`;
}

function showMessage(text) {
  $('#message-text').textContent = text;
  $('#message').hidden = false;
}

async function loadToken() {
  const response = await fetch('/api/csrf');
  if (!response.ok) throw new Error('Could not start a session. Refresh the page and try again.');
  state.csrf = await response.json();
}

async function api(path, options = {}) {
  const headers = {...options.headers};
  if (options.body && !(options.body instanceof URLSearchParams)) {
    headers['Content-Type'] = 'application/json';
  }
  if (options.method && options.method !== 'GET') {
    if (!state.csrf) await loadToken();
    headers[state.csrf.headerName] = state.csrf.token;
  }
  const response = await fetch('/api' + path, {...options, headers});
  if (response.status === 204) return null;
  const data = await response.json().catch(() => ({message: 'Could not read the server response.'}));
  if (!response.ok) throw new Error(data.message || `Request failed (${response.status}).`);
  return data;
}

function openDialog(id) {
  $('#' + id).showModal();
}

function requireLogin() {
  if (state.user) return true;
  openDialog('auth-dialog');
  return false;
}

function renderAccount() {
  const container = $('#account');
  container.replaceChildren();
  if (!state.user) {
    const button = element('button', 'button secondary', 'Sign in');
    button.onclick = () => openDialog('auth-dialog');
    container.append(button);
    return;
  }
  container.append(element('span', '', state.user.name));
  const button = element('button', 'link-button', 'Sign out');
  button.onclick = async () => {
    try {
      await api('/auth/logout', {method: 'POST'});
      state.user = null;
      await loadToken();
      renderAccount();
      await setView('browse');
      showMessage('You have signed out.');
    } catch (error) {
      showMessage(error.message);
    }
  };
  container.append(button);
}

function startClaim(report) {
  if (!requireLogin()) return;
  state.claimId = report.id;
  $('#claim-item').textContent = `${report.title} — Report #${report.id}`;
  $('#claim-form').reset();
  $('#claim-form .form-error').textContent = '';
  openDialog('claim-dialog');
}

function renderReport(report, action = 'claim') {
  const row = element('article', 'report');
  const body = element('div', 'report-body');
  const heading = element('div', 'report-heading');
  heading.append(
    element('span', 'badge' + (report.kind === 'LOST' ? ' lost' : ''), label(report.kind)),
    element('h3', '', report.title)
  );
  const metadata = element('div', 'report-meta');
  metadata.append(
    element('span', '', label(report.location)),
    element('span', '', report.eventDate),
    element('span', '', label(report.category)),
    element('span', '', `Report #${report.id}`)
  );
  body.append(heading, metadata, element('p', 'description', report.description));
  const actions = element('div', 'report-actions');
  actions.append(element('span', 'report-status', label(report.status)));
  if (action === 'matches') {
    const button = element('button', 'link-button', 'Find matches');
    button.onclick = () => loadMatches(report.id);
    actions.append(button);
  } else if (action === 'claim' && report.kind === 'FOUND') {
    const button = element('button', 'link-button', 'Claim item');
    button.onclick = () => startClaim(report);
    actions.append(button);
  }
  row.append(body, actions);
  return row;
}

function showEmpty(container, text) {
  container.replaceChildren(element('p', 'empty', text));
}

function renderReports() {
  const query = $('#search').value.trim().toLowerCase();
  const category = $('#category-filter').value;
  const reports = state.reports.filter(report =>
    (state.kind === 'ALL' || report.kind === state.kind) &&
    (!category || report.category === category) &&
    (!query || [report.title, report.description, label(report.location)].some(value => value.toLowerCase().includes(query)))
  );
  const container = $('#cards');
  container.replaceChildren(...reports.map(report => renderReport(report, state.view === 'mine' ? 'matches' : 'claim')));
  if (!reports.length) {
    const text = state.reports.length
      ? 'No reports match these filters. Try a different search or category.'
      : state.view === 'mine' ? 'You have not submitted any reports.' : 'No items have been reported yet.';
    showEmpty(container, text);
  }
  $('#count').textContent = total(reports.length, 'report');
}

function renderClaims(claims) {
  const rows = claims.map(claim => {
    const row = renderReport(claim.report, 'none');
    row.querySelector('.report-actions').replaceChildren(element('span', 'badge', 'Pending — not approved'));
    const details = element('div', 'report-extra');
    details.append(element('p', 'private-label', 'Your private identifying details'), element('div', 'private', claim.privateDetails));
    row.append(details);
    return row;
  });
  $('#cards').replaceChildren(...rows);
  $('#count').textContent = total(claims.length, 'claim');
  if (!claims.length) showEmpty($('#cards'), 'You have not submitted any claims. You can claim an item from a found report.');
}

function selectKind(kind) {
  state.kind = kind;
  for (const button of document.querySelectorAll('[data-kind]')) {
    const selected = button.dataset.kind === kind;
    button.classList.toggle('active', selected);
    button.setAttribute('aria-pressed', String(selected));
  }
}

async function setView(view) {
  if (view !== 'browse' && !requireLogin()) return;
  if (state.view !== view) {
    selectKind('ALL');
    $('#search').value = '';
    $('#category-filter').value = '';
  }
  state.view = view;
  for (const button of document.querySelectorAll('[data-view]')) {
    const selected = button.dataset.view === view;
    button.classList.toggle('active', selected);
    if (selected) button.setAttribute('aria-current', 'page');
    else button.removeAttribute('aria-current');
  }
  $('#filters').hidden = view === 'claims';
  $('#section-title').textContent = {browse: 'Recent reports', mine: 'My reports', claims: 'My claims'}[view];
  $('#board-note').textContent = view === 'claims'
    ? 'A pending claim is not approval to collect an item. Staff review and collection records are not available yet.'
    : 'Reports are public. Keep contact details and proof of ownership out of the description.';
  showEmpty($('#cards'), 'Loading reports...');
  $('#cards').setAttribute('aria-busy', 'true');
  $('#count').textContent = '';
  try {
    if (view === 'claims') renderClaims(await api('/me/claims'));
    else {
      state.reports = await api(view === 'mine' ? '/me/reports' : '/reports');
      renderReports();
    }
  } catch (error) {
    showEmpty($('#cards'), error.message);
  } finally {
    $('#cards').setAttribute('aria-busy', 'false');
  }
}

async function loadMatches(id) {
  const container = $('#matches-list');
  showEmpty(container, 'Checking for matches...');
  openDialog('matches-dialog');
  try {
    const matches = await api(`/reports/${id}/matches`);
    const rows = matches.map(match => {
      const row = renderReport(match.report);
      row.prepend(element('p', 'match-summary', `Match score: ${match.score}/100. ${match.reason}`));
      return row;
    });
    container.replaceChildren(...rows);
    if (!matches.length) showEmpty(container, 'No possible matches found. Your report is still open; check again when more items have been reported.');
  } catch (error) {
    showEmpty(container, error.message);
  }
}

function handleForm(id, submit) {
  const form = $('#' + id);
  form.onsubmit = async event => {
    event.preventDefault();
    const button = form.querySelector('button[type=submit]');
    button.disabled = true;
    form.querySelector('.form-error').textContent = '';
    try {
      await submit(Object.fromEntries(new FormData(form)));
    } catch (error) {
      form.querySelector('.form-error').textContent = error.message;
    } finally {
      button.disabled = false;
    }
  };
}

$('#open-auth').onclick = () => openDialog('auth-dialog');
$('#open-report').onclick = () => {
  if (!requireLogin()) return;
  $('#report-form').reset();
  $('#report-form .form-error').textContent = '';
  openDialog('report-dialog');
};
$('#dismiss-message').onclick = () => { $('#message').hidden = true; };
for (const button of document.querySelectorAll('[data-close]')) {
  button.onclick = () => $('#' + button.dataset.close).close();
}
for (const button of document.querySelectorAll('[data-view]')) {
  button.onclick = () => setView(button.dataset.view);
}
for (const button of document.querySelectorAll('[data-kind]')) {
  button.onclick = () => { selectKind(button.dataset.kind); renderReports(); };
}
$('#search').oninput = renderReports;
$('#category-filter').onchange = renderReports;

$('#toggle-auth').onclick = () => {
  state.register = !state.register;
  $('#name-label').hidden = !state.register;
  $('#name-label input').required = state.register;
  $('#password-hint').hidden = !state.register;
  $('#auth-form [name=password]').minLength = state.register ? 10 : 1;
  $('#auth-form [name=password]').autocomplete = state.register ? 'new-password' : 'current-password';
  $('#auth-title').textContent = state.register ? 'Create an account' : 'Sign in';
  $('#auth-submit').textContent = state.register ? 'Create account' : 'Sign in';
  $('#toggle-auth').textContent = state.register ? 'Back to sign in' : 'Create an account';
  $('#auth-error').textContent = '';
};

handleForm('auth-form', async data => {
  const email = data.email.trim().toLowerCase();
  if (state.register) await api('/auth/register', {method: 'POST', body: JSON.stringify({...data, email})});
  await api('/auth/login', {method: 'POST', body: new URLSearchParams({username: email, password: data.password})});
  await loadToken();
  state.user = await api('/me');
  $('#auth-dialog').close();
  $('#auth-form').reset();
  renderAccount();
  await setView('mine');
  showMessage('Signed in.');
});

handleForm('report-form', async data => {
  const result = await api('/reports', {method: 'POST', body: JSON.stringify(data)});
  $('#report-dialog').close();
  selectKind('ALL');
  $('#search').value = '';
  $('#category-filter').value = '';
  await setView('mine');
  showMessage(result.possibleDuplicates.length
    ? 'Report saved. Similar reports already exist: ' + result.possibleDuplicates.map(report => '#' + report.id).join(', ') + '. Please check them before submitting another report.'
    : 'Report saved. Select Find matches to check for related reports.');
});

handleForm('claim-form', async data => {
  const result = await api(`/reports/${state.claimId}/claims`, {method: 'POST', body: JSON.stringify(data)});
  $('#claim-dialog').close();
  $('#matches-dialog').close();
  await setView('claims');
  showMessage(result.message);
});

async function initialise() {
  try {
    await loadToken();
    const options = await api('/options');
    for (const [id, values] of [
      ['report-category', options.categories],
      ['category-filter', options.categories],
      ['report-location', options.locations]
    ]) {
      for (const value of values) {
        const option = element('option', '', label(value));
        option.value = value;
        $('#' + id).append(option);
      }
    }
    const now = new Date();
    $('#report-form [name=eventDate]').max = [now.getFullYear(), String(now.getMonth() + 1).padStart(2, '0'), String(now.getDate()).padStart(2, '0')].join('-');
    const response = await fetch('/api/me');
    if (response.ok) state.user = await response.json();
    renderAccount();
    await setView('browse');
  } catch (error) {
    showEmpty($('#cards'), 'Could not load the reports. ' + error.message);
  }
}

initialise();
