import fs from 'node:fs';
import vm from 'node:vm';

/** 브라우저 대신 모듈의 이벤트·DOM 출력만 검사하는 대역이다. 레이아웃 렌더링은 검증하지 않는다. */
export class Node {
  constructor(tag, document) {
    this.tagName = tag; this.document = document; this.children = []; this.attributes = new Map();
    this.events = new Map(); this.dataset = {}; this.style = {}; this.className = '';
    this.hidden = false; this.disabled = false; this.scrollHeight = 200;
    this.rect = { top: 80, bottom: 120, left: 20, width: 270, height: 200 };
    this.classList = {
      contains: value => this.className.split(' ').includes(value),
      add: value => { if (!this.classList.contains(value)) this.className += ' ' + value; },
      remove: value => { this.className = this.className.split(' ').filter(x => x !== value).join(' '); },
      toggle: (value, on = !this.classList.contains(value)) => on ? this.classList.add(value) : this.classList.remove(value)
    };
  }
  get childElementCount() { return this.children.length; }
  get isConnected() { return this.document.root.contains(this); }
  get textContent() { return (this.text || '') + this.children.map(x => x.textContent).join(''); }
  set textContent(value) { this.replaceChildren(); this.text = String(value); }
  get innerHTML() { return this.html || ''; }
  set innerHTML(value) { this.replaceChildren(); this.html = value; }
  /** 교체한 노드는 부모에서 분리해, 지연 이벤트가 현재 표시를 바꾸지 않도록 모의한다. */
  replaceChildren(...children) {
    for (const child of this.children) child.parent = null;
    this.children = []; this.text = ''; this.html = ''; this.append(...children);
  }
  append(...children) { for (const child of children) { child.parent = this; this.children.push(child); } }
  appendChild(child) { this.append(child); }
  remove() { if (this.parent) this.parent.children = this.parent.children.filter(x => x !== this); this.parent = null; }
  contains(child) { return !!child && (child === this || this.children.some(x => x.contains(child))); }
  setAttribute(key, value) { this.attributes.set(key, String(value)); }
  getAttribute(key) { return this.attributes.get(key) ?? null; }
  removeAttribute(key) { this.attributes.delete(key); }
  addEventListener(type, fn) { if (!this.events.has(type)) this.events.set(type, []); this.events.get(type).push(fn); }
  emit(type, event = {}) {
    const e = { target: this, preventDefault() {}, stopPropagation() {}, ...event };
    return Promise.all((this.events.get(type) || []).map(fn => fn(e)));
  }
  /** 포커스 이동에서 실제 모듈이 사용하는 focusout 전파를 재현한다. */
  focus() {
    const previous = this.document.activeElement;
    this.document.activeElement = this;
    for (let node = previous; node; node = node.parent) node.emit('focusout', { target: previous, relatedTarget: this });
  }
  getBoundingClientRect() { return this.rect; }
  /** 이 시험에서 쓰는 태그·클래스·속성 선택자만 지원한다. */
  matches(selector) {
    if (selector.startsWith('#')) {
      const [id, className] = selector.slice(1).split('.');
      return this.id === id && (!className || this.classList.contains(className));
    }
    if (selector.startsWith('.')) return this.classList.contains(selector.slice(1));
    if (selector.startsWith('[')) {
      const key = selector.slice(1, -1).split('=')[0];
      const dataKey = key.replace(/^data-/, '').replace(/-([a-z])/g, (_, c) => c.toUpperCase());
      return this.attributes.has(key) || (key.startsWith('data-') && Object.hasOwn(this.dataset, dataKey));
    }
    return this.tagName === selector;
  }
  /** 자식 트리를 탐색해 실제 모듈이 그린 버튼·메시지를 검사한다. */
  querySelectorAll(selector) {
    if (selector === ':scope > button') return this.children.filter(x => x.tagName === 'button');
    const space = selector.indexOf(' ');
    if (space > 0) return this.querySelectorAll(selector.slice(0, space)).flatMap(x => x.querySelectorAll(selector.slice(space + 1)));
    const descendants = this.children.flatMap(x => [x, ...x.querySelectorAll('*')]);
    return selector === '*' ? descendants : descendants.filter(x => selector.split(',').some(s => x.matches(s.trim())));
  }
  querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
  cloneNode(deep = false) {
    const clone = new Node(this.tagName, this.document);
    clone.className = this.className; clone.dataset = { ...this.dataset }; clone.text = this.text;
    if (deep) clone.append(...this.children.map(x => x.cloneNode(true)));
    return clone;
  }
}

/** 대역 문서·시계와 fetch를 구성한다. 실제 HTTP나 사용자 계정에는 접근하지 않는다. */
export function environment(handler = () => ({})) {
  const events = new Map(), intervals = new Map(), calls = [], alerts = [], navigations = [];
  const document = {
    readyState: 'loading', activeElement: null,
    createElement(tag) { return new Node(tag, document); },
    createTextNode(text) { const node = this.createElement('#text'); node.text = text; return node; },
    addEventListener(type, fn) { if (!events.has(type)) events.set(type, []); events.get(type).push(fn); },
    removeEventListener(type, fn) { events.set(type, (events.get(type) || []).filter(x => x !== fn)); },
    async emit(type, detail, event = {}) { await Promise.all((events.get(type) || []).map(fn => fn({ detail, ...event }))); },
    querySelector(selector) {
      const space = selector.indexOf(' ');
      if (space > 0) return this.querySelector(selector.slice(0, space))?.querySelector(selector.slice(space + 1)) || null;
      return this.root.querySelector(selector);
    },
    querySelectorAll(selector) {
      if (selector === '.landing-nav .nav-item') return this.querySelector('.landing-nav')?.querySelectorAll('.nav-item') || [];
      const space = selector.indexOf(' ');
      if (space > 0) return this.querySelector(selector.slice(0, space))?.querySelectorAll(selector.slice(space + 1)) || [];
      return this.root.querySelectorAll(selector);
    }
  };
  document.root = document.createElement('html'); document.documentElement = document.root;
  document.documentElement.clientWidth = 390; document.documentElement.clientHeight = 700;
  const window = {
    location: { href: 'http://test.invalid/?listingId=5' },
    addEventListener(type, fn) { document.addEventListener('window:' + type, fn); },
    async fetch(path, options = {}) {
      calls.push({ path, options });
      const value = await handler(path, options, calls);
      if (value?.response) return value.response;
      return { ok: true, status: 200, async json() { return { data: value }; } };
    }
  };
  const show = name => {
    navigations.push(name);
    for (const page of document.root.querySelectorAll('.page')) page.classList.toggle('active', page.id === name);
    if (name !== 'detail') window.location.href = 'http://test.invalid/';
    return document.emit('pastport:page-shown', { id: name });
  };
  const context = vm.createContext({
    window, document, URL, URLSearchParams, AbortController, AbortSignal, console, Intl,
    alert: message => alerts.push(message), confirm: () => true, show,
    setInterval(fn) { const id = Symbol(); intervals.set(id, fn); return id; },
    clearInterval(id) { intervals.delete(id); }
  });
  return {
    window, document, intervals, calls, alerts, navigations,
    add(tag, attrs = {}, parent = document.root) {
      const node = document.createElement(tag);
      for (const [key, value] of Object.entries(attrs)) {
        if (key === 'id' || key === 'className') node[key] = value;
        else node.setAttribute(key, value);
      }
      parent.append(node); return node;
    },
    run(file, globals = {}) { Object.assign(context, globals); vm.runInContext(fs.readFileSync('src/main/resources/static/js/' + file, 'utf8'), context, { filename: file }); }
  };
}

/** 응답 완료 순서를 시험에서 제어해, 취소가 제때 전달되지 않는 지연도 재현한다. */
export function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}

/** 이벤트 핸들러에서 시작한 Promise의 후속 처리가 끝나도록 실행권을 넘긴다. */
export async function flush() { for (let i = 0; i < 12; i++) await new Promise(resolve => setImmediate(resolve)); }
