const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/app.js'), 'utf8');
// Exclude the automatic initialise() call at the bottom when executing in unit tests
const appSource = source.replace(/initialise\(\);?\s*$/, '') + '\nglobalThis.app = {state, syncSubtypes, openReportDialog, startEdit, confirmDelete};';

function mockElement(tag = 'div') {
  const children = [];
  const classList = new Set();
  const attributes = new Map();
  return {
    tagName: tag.toUpperCase(),
    value: '',
    textContent: '',
    hidden: false,
    open: false,
    dataset: {},
    classList: {
      add: cls => classList.add(cls),
      remove: cls => classList.delete(cls),
      toggle: (cls, force) => force ? classList.add(cls) : classList.delete(cls),
      contains: cls => classList.has(cls)
    },
    setAttribute: (k, v) => attributes.set(k, String(v)),
    getAttribute: k => attributes.get(k),
    removeAttribute: k => attributes.delete(k),
    append: (...items) => { children.push(...items); },
    replaceChildren: (...items) => {
      children.length = 0;
      children.push(...items);
    },
    get children() { return children; },
    querySelector: () => mockElement(),
    querySelectorAll: () => [],
    reset() {
      if (this.tagName === 'SELECT' && children.length > 0) {
        this.value = children[0].value;
      }
    },
    showModal() { this.open = true; },
    close() { this.open = false; }
  };
}

function createDOMContext() {
  const elements = new Map();
  function getEl(sel) {
    if (!elements.has(sel)) {
      const tag = sel.includes('form') ? 'form' : sel.includes('select') || sel.includes('category') || sel.includes('subtype') || sel.includes('location') ? 'select' : 'div';
      elements.set(sel, mockElement(tag));
    }
    return elements.get(sel);
  }

  const document = {
    querySelector: sel => getEl(sel),
    querySelectorAll: () => [],
    createElement: tag => mockElement(tag)
  };

  const context = {
    document,
    URLSearchParams,
    FormData: class { constructor(f) {} },
    fetch: async () => ({ ok: true, json: async () => ({}) })
  };

  return { context, elements, getEl };
}

test('syncSubtypes populates matching subtypes and resets on category change', () => {
  const { context, getEl } = createDOMContext();
  vm.runInNewContext(appSource, context);
  const { app } = context;

  // Set mock options in state
  app.state.options = {
    categories: ['ELECTRONICS', 'BAGS', 'BOOKS'],
    subtypesByCategory: {
      ELECTRONICS: ['LAPTOP', 'PHONE', 'OTHER_ELECTRONICS'],
      BAGS: ['BACKPACK', 'WALLET', 'OTHER_BAG'],
      BOOKS: ['TEXTBOOK', 'NOTEBOOK', 'OTHER_BOOK']
    },
    locations: ['LIBRARY', 'CAFETERIA']
  };

  const catSelect = getEl('#report-category');
  const subSelect = getEl('#report-subtype');

  // Initial category: ELECTRONICS
  catSelect.value = 'ELECTRONICS';
  app.syncSubtypes('report-category', 'report-subtype');
  assert.equal(subSelect.children.length, 3);
  assert.equal(subSelect.value, 'LAPTOP');

  // Change category to BAGS
  catSelect.value = 'BAGS';
  app.syncSubtypes('report-category', 'report-subtype');
  assert.equal(subSelect.children.length, 3);
  assert.equal(subSelect.value, 'BACKPACK');
  assert.deepEqual(subSelect.children.map(c => c.value), ['BACKPACK', 'WALLET', 'OTHER_BAG']);
});

test('openReportDialog resets category and synchronizes subtypes without leaving stale BAGS state', () => {
  const { context, getEl } = createDOMContext();
  vm.runInNewContext(appSource, context);
  const { app } = context;

  app.state.user = { name: 'Mayank' };
  app.state.options = {
    categories: ['ELECTRONICS', 'BAGS', 'BOOKS'],
    subtypesByCategory: {
      ELECTRONICS: ['LAPTOP', 'PHONE', 'OTHER_ELECTRONICS'],
      BAGS: ['BACKPACK', 'WALLET', 'OTHER_BAG']
    },
    locations: ['LIBRARY']
  };

  const form = getEl('#report-form');
  const catSelect = getEl('#report-category');
  const subSelect = getEl('#report-subtype');
  const dialog = getEl('#report-dialog');

  // User previously interacted and chose BAGS / BACKPACK
  catSelect.value = 'BAGS';
  app.syncSubtypes('report-category', 'report-subtype', 'BACKPACK');
  assert.equal(catSelect.value, 'BAGS');
  assert.equal(subSelect.value, 'BACKPACK');

  // User closes dialog and re-opens report dialog
  dialog.close();
  app.openReportDialog();

  // Category and subtype must both be reset to default ELECTRONICS / LAPTOP
  assert.equal(catSelect.value, 'ELECTRONICS');
  assert.equal(subSelect.value, 'LAPTOP');
  assert.deepEqual(subSelect.children.map(c => c.value), ['LAPTOP', 'PHONE', 'OTHER_ELECTRONICS']);
  assert.equal(dialog.open, true);
});

test('edit report dialog initializes with report category/subtype and updates on change', () => {
  const { context, getEl } = createDOMContext();
  vm.runInNewContext(appSource, context);
  const { app } = context;

  app.state.user = { name: 'Mayank' };
  app.state.options = {
    categories: ['ELECTRONICS', 'BAGS'],
    subtypesByCategory: {
      ELECTRONICS: ['LAPTOP', 'PHONE', 'OTHER_ELECTRONICS'],
      BAGS: ['BACKPACK', 'WALLET', 'OTHER_BAG']
    },
    locations: ['LIBRARY']
  };

  const catSelect = getEl('#edit-category');
  const subSelect = getEl('#edit-subtype');
  const dialog = getEl('#edit-dialog');

  const report = {
    id: 42,
    title: 'Blue Wallet',
    category: 'BAGS',
    subtype: 'WALLET',
    location: 'LIBRARY',
    eventDate: '2026-11-20',
    description: 'A blue leather wallet'
  };

  app.startEdit(report);
  assert.equal(app.state.editId, 42);
  assert.equal(catSelect.value, 'BAGS');
  assert.equal(subSelect.value, 'WALLET');
  assert.equal(dialog.open, true);

  // User changes edit category to ELECTRONICS
  catSelect.value = 'ELECTRONICS';
  app.syncSubtypes('edit-category', 'edit-subtype');
  assert.equal(subSelect.value, 'LAPTOP');
  assert.deepEqual(subSelect.children.map(c => c.value), ['LAPTOP', 'PHONE', 'OTHER_ELECTRONICS']);
});
