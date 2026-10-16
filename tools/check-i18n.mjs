// The English table covers the German texts of the web pages.
//
// Uses the same matching as static/i18n.js: exact lines, {} patterns, and lines
// joined with " · " or ", " translated piece by piece. Run in CI:
//
//     node tools/check-i18n.mjs

import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const STATIC = join(dirname(dirname(fileURLToPath(import.meta.url))), "viewer", "static");
const EDGE = /^([\s,:;·–]*)([\s\S]*?)([\s,.:;·–]*)$/;
const core = (s) => EDGE.exec(s)[2];
const squash = (s) => s.replace(/\s+/g, " ");
const escape = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

function load(lang = "en") {
  const exact = new Map(), patterns = [], keys = [];
  for (const line of readFileSync(join(STATIC, "i18n", lang + ".tsv"), "utf8").split("\n")) {
    if (!line || line[0] === "#" || !line.includes("\t")) continue;
    const tab = line.indexOf("\t");
    const de = core(squash(line.slice(0, tab))), en = line.slice(tab + 1);
    if (!de) continue;
    keys.push([de, en]);
    if (de.includes("{}")) {
      const rx = new RegExp("^" + de.split("{}").map(escape).join("([\\s\\S]*?)") + "$");
      patterns.push([rx, de.replaceAll("{}", "").length]);
    } else exact.set(de, en);
  }
  patterns.sort((a, b) => b[1] - a[1]);
  return { exact, patterns, keys };
}

const { exact: EXACT, patterns: PATTERNS, keys: KEYS } = load();
// Same in both languages: key names, short labels, names
const NEUTRAL = /^(?:[A-Z0-9]{1,4}|Esc|Shift|Tab|WASD|Shader|Stashes|Webmatica|live|\.[a-z]+)$/;

// True if i18n.js would find a translation (or nothing needs one).
function translated(s) {
  if (!/[A-Za-zÄÖÜäöü]/.test(s) || NEUTRAL.test(s.trim())) return true;
  const c = core(squash(s));
  if (!c) return true;
  if (EXACT.has(c) || PATTERNS.some(([rx]) => rx.test(c))) return true;
  for (const sep of [" · ", ", "]) {
    if (c.includes(sep) && c.split(sep).every(translated)) return true;
  }
  return false;
}

const ENTITIES = { amp: "&", lt: "<", gt: ">", quot: '"', apos: "'", nbsp: " " };
const decode = (s) => s.replace(/&(#x[0-9a-f]+|#\d+|[a-z]+);/gi, (m, e) =>
  e[0] !== "#" ? ENTITIES[e.toLowerCase()] ?? m
    : String.fromCodePoint(e[1] === "x" || e[1] === "X" ? parseInt(e.slice(2), 16) : +e.slice(1)));

// Text nodes and translated attributes, skipping script/style/code and data-noi18n.
const SKIP = new Set(["script", "style", "code", "textarea"]);
const RAW = new Set(["script", "style", "textarea"]);
const ATTRS = new Set(["title", "placeholder", "aria-label", "alt"]);
const VOID = new Set(["br", "img", "input", "meta", "link", "hr", "source", "wbr"]);

function pageTexts(name) {
  const html = readFileSync(join(STATIC, name), "utf8");
  const stack = [], out = [];
  const token = /<!--[\s\S]*?-->|<!\w[^>]*>|<\/([a-zA-Z][\w-]*)\s*>|<([a-zA-Z][\w-]*)((?:[^>"']|"[^"]*"|'[^']*')*)>|[^<]+|</g;
  for (let m; (m = token.exec(html)); ) {
    const [all, end, start, rest] = m;
    const hidden = stack.length > 0 && stack[stack.length - 1];
    if (start) {
      const tag = start.toLowerCase(), attrs = [];
      for (const a of rest.matchAll(/([^\s=/"']+)(?:\s*=\s*("[^"]*"|'[^']*'|[^\s"'>]+))?/g)) {
        const v = a[2] === undefined ? null : a[2].replace(/^["']|["']$/g, "");
        attrs.push([a[1].toLowerCase(), v === null ? null : decode(v)]);
      }
      const skip = SKIP.has(tag) || attrs.some(([k]) => k === "data-noi18n") || hidden;
      if (!skip) for (const [k, v] of attrs) if (ATTRS.has(k) && v) out.push(v);
      if (!VOID.has(tag)) stack.push(Boolean(skip));
      if (RAW.has(tag) && !rest.trimEnd().endsWith("/")) {
        const close = html.toLowerCase().indexOf("</" + tag, token.lastIndex);
        token.lastIndex = close < 0 ? html.length : close;
      }
    } else if (end) {
      if (!VOID.has(end.toLowerCase()) && stack.length) stack.pop();
    } else if (all[0] !== "<" || all === "<") {
      const text = decode(all).trim();
      if (!hidden && text) out.push(text);
    }
  }
  return out;
}

const failures = [];
for (const page of ["index.html", "editor.html"]) {
  const missing = [...new Set(pageTexts(page).filter((t) => !translated(t)))].sort();
  if (missing.length) failures.push(`${page}: add these lines to static/i18n/en.tsv\n  ` + missing.join("\n  "));
}
const seen = new Map(), clash = [];
for (const [de, en] of KEYS) {
  const e = core(en);
  if (seen.has(de) && seen.get(de) !== e) clash.push(`${de}: ${seen.get(de)} / ${e}`);
  if (!seen.has(de)) seen.set(de, e);
}
if (clash.length) failures.push("conflicting duplicates in en.tsv:\n  " + clash.join("\n  "));
const count = (s) => s.split("{}").length - 1;
const bad = KEYS.filter(([de, en]) => count(de) !== count(en)).map(([de]) => de);
if (bad.length) failures.push("{} placeholders differ:\n  " + bad.join("\n  "));

if (process.argv.includes("--dump")) console.log(JSON.stringify(["index.html", "editor.html"].map(pageTexts)));
if (failures.length) {
  console.error(failures.join("\n\n"));
  process.exit(1);
}
console.log(`i18n ok: ${KEYS.length} lines, index.html and editor.html covered`);
