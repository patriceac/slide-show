import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';

const script = readFileSync(new URL('../wwwroot/assets/theme.js', import.meta.url), 'utf8');
function browser({ saved = null, dark = false, blocked = false } = {}) {
  const listeners = {};
  const root = { dataset: {} };
  const select = { value: '', addEventListener: (name, fn) => listeners.select = fn };
  const system = { matches: dark, addEventListener: (name, fn) => listeners.system = fn };
  const storage = new Map(saved ? [['slideshow.theme', saved]] : []);
  let ready = false;
  runInNewContext(script, {
    window: { matchMedia: () => system, addEventListener: (name, fn) => listeners[name] = fn },
    document: {
      documentElement: root,
      querySelector: () => ({ setAttribute: (name, value) => root.metaColor = value }),
      querySelectorAll: () => ready ? [select] : [],
      addEventListener: (name, fn) => listeners[name] = fn,
    },
    localStorage: {
      getItem: key => { if (blocked) throw Error('Storage blocked'); return storage.get(key); },
      setItem: (key, value) => { if (blocked) throw Error('Storage blocked'); storage.set(key, value); },
    },
  });
  const initialTheme = root.dataset.theme;
  ready = true;
  listeners.DOMContentLoaded();
  return {
    root, select, storage, initialTheme,
    choose(value) { select.value = value; listeners.select(); },
    systemDark(value) { system.matches = value; listeners.system(); },
    storageEvent(key, newValue) { listeners.storage({ key, newValue }); },
  };
}

test('System is the default, applies before content loads, and follows live OS changes', () => {
  for (const dark of [false, true]) {
    const page = browser({ dark });
    assert.equal(page.initialTheme, dark ? 'dark' : 'light');
    assert.equal(page.select.value, 'system');
    page.systemDark(!dark);
    assert.equal(page.root.dataset.theme, dark ? 'light' : 'dark');
    assert.equal(page.storage.size, 0);
  }
});

test('Explicit themes persist, override the OS, and stay in sync across windows', () => {
  const page = browser();
  for (const theme of ['dark', 'light']) {
    page.choose(theme);
    page.systemDark(theme === 'light');
    assert.equal(page.root.dataset.theme, theme);
    assert.equal(browser({ saved: page.storage.get('slideshow.theme') }).initialTheme, theme);
  }
  page.choose('system');
  assert.equal(page.root.dataset.theme, 'dark');
  page.storageEvent('slideshow.theme', 'light');
  assert.equal(page.root.dataset.theme, 'light');
  assert.equal(page.select.value, 'light');
  page.storageEvent(null, null);
  assert.equal(page.root.dataset.theme, 'dark');
  assert.equal(page.select.value, 'system');
});

test('Invalid or unavailable storage falls back to System and does not prevent switching', () => {
  for (const options of [{ saved: 'invalid' }, { blocked: true }]) {
    const page = browser({ ...options, dark: true });
    assert.equal(page.initialTheme, 'dark');
    assert.equal(page.select.value, 'system');
    page.choose('light');
    assert.equal(page.root.dataset.theme, 'light');
    assert.equal(page.root.metaColor, '#fafaf9');
  }
});
