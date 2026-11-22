const $ = selector => document.querySelector(selector);
const state = {
  user: null,
  view: 'browse',
  kind: 'ALL',
  reports: [],
  csrf: null,
  claimId: null,
  editId: null,
  deleteId: null,
  options: null,
  myReportIds: new Set()
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
  const response = await fetch('/api/csrf', {cache: 'no-store'});
  if (!response.ok) throw new Error('Could not start a session. Refresh the page and try again.');
  state.csrf = await response.json();
}

function requestDemoLogin() {
  state.user = null;
  state.csrf = null;
  renderAccount();
  $('#auth-dialog .form-error').textContent = 'Your session ended. Choose your demo student again. Your form details have been kept.';
  if (!$('#auth-dialog').open) openDialog('auth-dialog');
}

async function api(path, options = {}, csrfRetried = false) {
  const headers = {...options.headers};
  const write = options.method && options.method !== 'GET';
  if (options.body && !(options.body instanceof URLSearchParams)) {
    headers['Content-Type'] = 'application/json';
  }
  if (write) {
    if (!state.csrf) await loadToken();
    headers[state.csrf.headerName] = state.csrf.token;
  }
  const response = await fetch('/api' + path, {...options, headers});
  if (response.status === 204) return null;
  const data = await response.json().catch(() => ({message: 'Could not read the server response.'}));
  if (response.status === 403 && data.code === 'CSRF_INVALID' && write && !csrfRetried) {
    await loadToken();
    return api(path, options, true);
  }
  if (response.status === 401) {
    requestDemoLogin();
    throw new Error('Your session ended. Choose a demo student again, then submit the form. Your details have been kept.');
  }
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

async function refreshMyReportIds() {
  if (!state.user) {
    state.myReportIds = new Set();
    return;
  }
  try {
    const mine = await api('/me/reports');
    state.myReportIds = new Set(mine.map(r => r.id));
  } catch {
    state.myReportIds = new Set();
  }
}

function renderAccount() {
  const container = $('#account');
  container.replaceChildren();
  if (!state.user) {
    const button = element('button', 'button secondary', 'Demo login');
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
      state.myReportIds = new Set();
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

function syncSubtypes(categorySelectId, subtypeSelectId, preferredValue = null) {
  const catSelect = $('#' + categorySelectId);
  const subSelect = $('#' + subtypeSelectId);
  if (!catSelect || !subSelect || !state.options) return;
  const category = catSelect.value;
  const subtypes = (state.options.subtypesByCategory && state.options.subtypesByCategory[category]) || [];
  subSelect.replaceChildren(...subtypes.map(s => {
    const opt = element('option', '', label(s));
    opt.value = s;
    return opt;
  }));
  if (preferredValue && subtypes.includes(preferredValue)) {
    subSelect.value = preferredValue;
  } else if (subtypes.length > 0) {
    subSelect.value = subtypes[0];
  }
}

function openReportDialog() {
  if (!requireLogin()) return;
  const form = $('#report-form');
  form.reset();
  if (state.options && state.options.categories && state.options.categories.length > 0) {
    $('#report-category').value = state.options.categories[0];
  }
  syncSubtypes('report-category', 'report-subtype');
  form.querySelector('.form-error').textContent = '';
  openDialog('report-dialog');
}

function startClaim(report) {
  if (!requireLogin()) return;
  if (!report || report.kind !== 'FOUND' || report.status !== 'OPEN') return;
  if (state.user && state.myReportIds && state.myReportIds.has(report.id)) return;
  state.claimId = report.id;
  $('#claim-item').textContent = `${report.title} — Report #${report.id}`;
  $('#claim-form').reset();
  $('#claim-form .form-error').textContent = '';
  openDialog('claim-dialog');
}

function startEdit(report) {
  if (!requireLogin()) return;
  state.editId = report.id;
  $('#edit-title-input').value = report.title;
  $('#edit-category').value = report.category;
  syncSubtypes('edit-category', 'edit-subtype', report.subtype);
  $('#edit-location').value = report.location;
  $('#edit-date').value = report.eventDate;
  $('#edit-description').value = report.description;
  $('#edit-form .form-error').textContent = '';
  openDialog('edit-dialog');
}

function confirmDelete(report) {
  if (!requireLogin()) return;
  state.deleteId = report.id;
  $('#confirm-text').textContent = `Are you sure you want to remove "${report.title}" (Report #${report.id})? It will no longer appear on the noticeboard or receive new matches.`;
  openDialog('confirm-dialog');
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
    element('span', '', label(report.category) + (report.subtype ? ' • ' + label(report.subtype) : '')),
    element('span', '', `Report #${report.id}`)
  );
  body.append(heading, metadata, element('p', 'description', report.description));
  const actions = element('div', 'report-actions');
  actions.append(element('span', 'report-status', label(report.status)));

  const isOpen = report.status === 'OPEN';
  const isMine = state.user && state.myReportIds && state.myReportIds.has(report.id);

  if (action === 'mine' && isOpen) {
    const matchBtn = element('button', 'link-button', 'Find matches');
    matchBtn.onclick = () => loadMatches(report.id);
    const editBtn = element('button', 'link-button', 'Edit');
    editBtn.onclick = () => startEdit(report);
    const deleteBtn = element('button', 'link-button danger-link', 'Delete');
    deleteBtn.onclick = () => confirmDelete(report);
    actions.append(matchBtn, editBtn, deleteBtn);
  } else if (action === 'claim' && report.kind === 'FOUND' && isOpen && !isMine) {
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
  container.replaceChildren(...reports.map(report => renderReport(report, state.view === 'mine' ? 'mine' : 'claim')));
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
    const isCancelled = claim.report && claim.report.status === 'CANCELLED';
    const row = renderReport(claim.report, 'none');
    const badgeText = isCancelled ? 'Report cancelled by owner' : 'Pending — not approved';
    const badgeClass = isCancelled ? 'badge lost' : 'badge';
    row.querySelector('.report-actions').replaceChildren(element('span', badgeClass, badgeText));
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
    ? "These claims haven't been approved. Staff approval and collection will be added later."
    : "Anyone can read these reports. Don't post contact details or proof that an item is yours.";
  showEmpty($('#cards'), 'Loading reports...');
  $('#cards').setAttribute('aria-busy', 'true');
  $('#count').textContent = '';
  try {
    await refreshMyReportIds();
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
      const row = renderReport(match.report, 'claim');
      row.prepend(element('p', 'match-summary', `Match score: ${match.score}/100 (${match.classification || 'Match'}). ${match.reason}`));
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
$('#open-report').onclick = openReportDialog;
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
$('#report-category').onchange = () => syncSubtypes('report-category', 'report-subtype');
$('#edit-category').onchange = () => syncSubtypes('edit-category', 'edit-subtype');

$('#confirm-cancel').onclick = () => $('#confirm-dialog').close();
$('#confirm-delete').onclick = async () => {
  if (!state.deleteId) return;
  try {
    await api(`/reports/${state.deleteId}`, {method: 'DELETE'});
    $('#confirm-dialog').close();
    state.deleteId = null;
    await refreshMyReportIds();
    await setView('mine');
    showMessage('Report removed.');
  } catch (error) {
    showMessage(error.message);
    $('#confirm-dialog').close();
  }
};

handleForm('auth-form', async data => {
  await api('/auth/demo', {method: 'POST', body: JSON.stringify(data)});
  await loadToken();
  state.user = await api('/me');
  $('#auth-dialog').close();
  renderAccount();
  await refreshMyReportIds();
  await setView('mine');
  const hasDraft = $('#report-dialog').open || $('#claim-dialog').open || $('#edit-dialog').open;
  for (const id of ['report-dialog', 'claim-dialog', 'edit-dialog']) {
    if ($('#' + id).open) $('#' + id + ' .form-error').textContent = '';
  }
  showMessage('Using ' + state.user.name + (hasDraft ? '. Your form details are still here. Submit the form when ready.' : '.'));
});

handleForm('report-form', async data => {
  const result = await api('/reports', {method: 'POST', body: JSON.stringify(data)});
  $('#report-dialog').close();
  selectKind('ALL');
  $('#search').value = '';
  $('#category-filter').value = '';
  await refreshMyReportIds();
  await setView('mine');
  showMessage(result.possibleDuplicates.length
    ? 'Report saved. Similar reports already exist: ' + result.possibleDuplicates.map(report => '#' + report.id).join(', ') + '. Please check them before submitting another report.'
    : 'Report saved. Select Find matches to check for related reports.');
});

handleForm('edit-form', async data => {
  await api(`/reports/${state.editId}`, {method: 'PUT', body: JSON.stringify(data)});
  $('#edit-dialog').close();
  await refreshMyReportIds();
  await setView('mine');
  showMessage('Report updated.');
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
    state.options = await api('/options');
    const accounts = await api('/auth/demo-accounts');
    for (const account of accounts) {
      const option = element('option', '', account.name);
      option.value = account.key;
      $('#demo-account').append(option);
    }
    for (const [id, values] of [
      ['report-category', state.options.categories],
      ['edit-category', state.options.categories],
      ['category-filter', state.options.categories],
      ['report-location', state.options.locations],
      ['edit-location', state.options.locations]
    ]) {
      const select = $('#' + id);
      if (!select) continue;
      for (const value of values) {
        const option = element('option', '', label(value));
        option.value = value;
        select.append(option);
      }
    }
    syncSubtypes('report-category', 'report-subtype');
    syncSubtypes('edit-category', 'edit-subtype');
    const now = new Date();
    const today = [now.getFullYear(), String(now.getMonth() + 1).padStart(2, '0'), String(now.getDate()).padStart(2, '0')].join('-');
    $('#report-form [name=eventDate]').max = today;
    $('#edit-form [name=eventDate]').max = today;
    const response = await fetch('/api/me');
    if (response.ok) {
      state.user = await response.json();
      await refreshMyReportIds();
    }
    renderAccount();
    await setView('browse');
  } catch (error) {
    showEmpty($('#cards'), 'Could not load the reports. ' + error.message);
  }
}

initialise();
