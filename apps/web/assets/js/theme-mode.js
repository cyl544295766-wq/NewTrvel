/* A browser's choice takes precedence over the account's saved default. */
(function () {
  'use strict';

  var key = 'travelos.theme';
  var root = document.documentElement;
  var system = window.matchMedia('(prefers-color-scheme: dark)');
  var accountChoice = null;
  var buttons = [];

  function valid(value) {
    return value === 'light' || value === 'dark' || value === 'system';
  }

  function readChoice() {
    try {
      var value = localStorage.getItem(key);
      if (valid(value)) return value;
      var previous = localStorage.getItem('travelos.loginTheme');
      return previous === 'light' || previous === 'dark' ? previous : null;
    } catch (e) {
      return null;
    }
  }

  var browserChoice = readChoice();

  function preference() {
    return browserChoice || accountChoice;
  }

  function apply() {
    var mode = preference() || 'dark';
    var light = mode === 'light' || (mode === 'system' && !system.matches);
    root.dataset.theme = light ? 'light' : 'dark';
    var nextLabel = light ? '深色' : '浅色';
    buttons.forEach(function (button) {
      button.textContent = nextLabel;
      button.setAttribute('aria-label', '切换为' + nextLabel + '模式');
      button.title = '切换为' + nextLabel + '模式';
    });
  }

  function setPreference(value) {
    if (!valid(value)) return;
    browserChoice = value;
    try { localStorage.setItem(key, value); } catch (e) { /* Still apply for the current page. */ }
    apply();
    window.dispatchEvent(new CustomEvent('travelos:theme-change', { detail: value }));
  }

  function useAccountPreference(value) {
    if (!valid(value)) return;
    accountChoice = value;
    apply();
  }

  function loadAccountPreference() {
    if (browserChoice || document.getElementById('prTheme')
        || typeof window.getCurrentUser !== 'function' || typeof window.apiGet !== 'function') return;
    var user = window.getCurrentUser();
    if (!user || !user.id) return;
    var sessionKey = 'travelos.accountTheme.' + user.id;
    try {
      var cached = sessionStorage.getItem(sessionKey);
      if (valid(cached)) {
        useAccountPreference(cached);
        return;
      }
    } catch (e) { /* Cache is optional. */ }
    window.apiGet('/auth/preferences').then(function (data) {
      var value = data && data.preferences && data.preferences.theme;
      if (!valid(value)) return;
      try { sessionStorage.setItem(sessionKey, value); } catch (e) { /* Cache is optional. */ }
      useAccountPreference(value);
    }).catch(function () { /* Use the current appearance when preferences are unavailable. */ });
  }

  apply();
  window.TravelOSTheme = { preference: preference, setPreference: setPreference, useAccountPreference: useAccountPreference };
  if (system.addEventListener) system.addEventListener('change', apply);
  else if (system.addListener) system.addListener(apply);

  window.addEventListener('storage', function (event) {
    if (event.key !== key && event.key !== null) return;
    browserChoice = readChoice();
    apply();
  });

  document.addEventListener('DOMContentLoaded', function () {
    var navUser = document.querySelector('.topbar .tb-user');
    if (navUser) {
      var toggle = document.createElement('button');
      toggle.type = 'button';
      toggle.className = 'site-theme-toggle';
      toggle.dataset.themeToggle = '';
      navUser.insertBefore(toggle, navUser.querySelector('.side-exit'));
    }
    buttons = Array.from(document.querySelectorAll('[data-theme-toggle]'));
    buttons.forEach(function (button) {
      button.addEventListener('click', function () {
        setPreference(root.dataset.theme === 'light' ? 'dark' : 'light');
      });
    });
    apply();
    loadAccountPreference();
  });
})();
