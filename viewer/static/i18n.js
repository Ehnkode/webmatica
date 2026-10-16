/* UI language switch: the UI is written in German. For English, every text
 * (text nodes, title, placeholder, aria-label, alert/confirm/prompt) is
 * translated when it appears, using static/i18n/<lang>.tsv. */
(function () {
  const KEY = 'webmatica.lang';
  let lang = null;
  try { lang = localStorage.getItem(KEY); } catch (e) { /* ignore */ }
  if (lang !== 'de' && lang !== 'en') {
    // No choice saved yet: follow the game language, English if it is unknown
    lang = 'en';
    try {
      const x = new XMLHttpRequest();
      x.open('GET', '/api/lang', false);
      x.send();
      if (x.status === 200 && /^de/i.test(JSON.parse(x.responseText).game || '')) lang = 'de';
    } catch (e) { /* ignore */ }
  }

  const I18N = window.I18N = {
    lang,
    locale: lang === 'de' ? 'de-DE' : 'en-US',
    t: (s) => s,
    set(l) {
      try { localStorage.setItem(KEY, l); } catch (e) { /* ignore */ }
      location.reload();
    },
  };
  document.documentElement.lang = lang;
  if (lang === 'de') return;

  const exact = new Map();
  const patterns = [];
  // Edges: whitespace and punctuation don't count in the comparison
  const EDGE = /^([\s,:;·–]*)([\s\S]*?)([\s,.:;·–]*)$/;
  const norm = (s) => s.replace(/\s+/g, ' ');
  try {
    const x = new XMLHttpRequest();
    x.open('GET', '/static/i18n/' + lang + '.tsv', false);
    x.send();
    for (const line of x.responseText.split('\n')) {
      if (!line || line[0] === '#') continue;
      const tab = line.indexOf('\t');
      if (tab < 0) continue;
      const de = norm(line.slice(0, tab)).match(EDGE)[2];
      const en = line.slice(tab + 1).replace(/\r$/, '');
      if (!de) continue;
      if (de.includes('{}')) {
        const re = new RegExp('^' + de.split('{}').map((p) => p.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('([\\s\\S]*?)') + '$');
        patterns.push([re, en, de.replace(/\{\}/g, '').length]);
      } else exact.set(de, en.match(EDGE)[2]);
    }
    patterns.sort((a, b) => b[2] - a[2]);
  } catch (e) { console.warn('i18n', e); }

  const cache = new Map();
  function core(s) {
    if (exact.has(s)) return exact.get(s);
    for (const [re, en] of patterns) {
      const m = s.match(re);
      if (!m) continue;
      let i = 1;
      return en.match(EDGE)[2].replace(/\{\}/g, () => t(m[i++] || ''));
    }
    return null;
  }
  function t(s) {
    if (!s || !/[A-Za-zÄÖÜäöü]/.test(s)) return s;
    if (s.trim().includes('\n')) return s.split('\n').map(t).join('\n');
    const m = norm(s).match(EDGE);
    if (!m[2]) return s;
    let r = cache.get(m[2]);
    if (r === undefined) {
      r = core(m[2]);
      // composed lines piece by piece
      for (const sep of [' · ', ', ']) {
        if (r !== null || !m[2].includes(sep)) continue;
        const parts = m[2].split(sep);
        const tr = parts.map((p) => t(p));
        r = tr.some((p, k) => p !== parts[k]) ? tr.join(sep) : null;
      }
      if (cache.size > 20000) cache.clear();
      cache.set(m[2], r);
    }
    if (r === null) return s;
    const lead = s.match(/^\s*/)[0], trail = s.match(/\s*$/)[0];
    const tail = m[3].trim();   // keep the space in front of a trailing " · "
    return lead + m[1].trim() + (m[1].trim() ? ' ' : '') + r + (tail && /^\s/.test(m[3]) ? ' ' : '') + tail + trail;
  }
  // multi-line messages (confirm) line by line
  const tLines = (s) => (typeof s === 'string' ? s.split('\n').map(t).join('\n') : s);
  I18N.t = t;

  const ATTRS = ['title', 'placeholder', 'aria-label', 'alt'];
  const SKIP = new Set(['SCRIPT', 'STYLE', 'TEXTAREA', 'CODE']);
  function text(n) {
    const p = n.parentNode;
    if (!p || SKIP.has(p.nodeName) || (p.closest && p.closest('[data-noi18n]'))) return;
    const v = n.nodeValue, r = t(v);
    if (r !== v) n.nodeValue = r;
  }
  function attrs(el) {
    for (const a of ATTRS) {
      const v = el.getAttribute(a);
      if (v) { const r = t(v); if (r !== v) el.setAttribute(a, r); }
    }
  }
  function walk(root) {
    if (root.nodeType === 3) { text(root); return; }
    if (root.nodeType !== 1 || SKIP.has(root.nodeName) || root.hasAttribute('data-noi18n')) return;
    attrs(root);
    const w = document.createTreeWalker(root, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT);
    let n;
    while ((n = w.nextNode())) {
      if (n.nodeType === 3) text(n); else attrs(n);
    }
  }
  new MutationObserver((list) => {
    for (const m of list) {
      if (m.type === 'childList') m.addedNodes.forEach(walk);
      else if (m.type === 'characterData') text(m.target);
      else if (m.type === 'attributes') attrs(m.target);
    }
  }).observe(document.documentElement, { childList: true, subtree: true, characterData: true, attributes: true, attributeFilter: ATTRS });
  walk(document.documentElement);

  const _alert = window.alert, _confirm = window.confirm, _prompt = window.prompt;
  window.alert = (m) => _alert.call(window, tLines(m));
  window.confirm = (m) => _confirm.call(window, tLines(m));
  window.prompt = (m, d) => _prompt.call(window, tLines(m), d);
})();
