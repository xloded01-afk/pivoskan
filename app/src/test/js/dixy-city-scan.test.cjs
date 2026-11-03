// Offline integration checks of the production page controller, with a simulated retailer DOM.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('app/src/main/assets/DixyCityScan.js', 'utf8');

function fixture({total = 2, initial = 2, wrongAddress = false, duplicate = false, stuck = false} = {}) {
    let time = 0, timerId = 0, clicks = 0, page = 1, count = initial;
    const timers = new Map();
    const address = 'Московская обл, Балашиха, Гагарина, 30';
    const later = (fn, ms) => { const id = ++timerId; timers.set(id, {at: time + ms, fn}); return id; };
    const more = {style: {}, disabled: false, click() {
        clicks++;
        // The site's URL changes first. Active page/cards must still be awaited.
        context.location.href = 'https://dixy.ru/catalog/alkogolnye-napitki/pivo/?page=2';
        context.location.search = '?page=2';
        if (!stuck) later(() => { page = 2; count = total; }, 2000);
    }};
    const document = {
        body: {innerText: 'Каталог пива'},
        querySelector(selector) {
            if (selector.includes('captcha')) return null;
            if (selector.includes('a.active')) return {dataset: {page: String(page)}};
            if (selector === '.more-orders') { more.style.display = count === total ? 'none' : 'block'; return more; }
            return null;
        },
        querySelectorAll(selector) {
            if (selector.startsWith('header')) return [{textContent: wrongAddress ? 'Москва, Валовая, 20' : address}];
            return Array.from({length: count}, (_, i) => ({getAttribute: () => String(duplicate ? 1 : i)}));
        },
        getElementById() { return {textContent: JSON.stringify({offers: {offerCount: total}})}; }
    };
    const context = {window: {}, document, location: {origin: 'https://dixy.ru', pathname: '/catalog/alkogolnye-napitki/pivo/', search: '', href: 'https://dixy.ru/catalog/alkogolnye-napitki/pivo/'},
        Date: {now: () => time}, setTimeout: later, clearTimeout: id => timers.delete(id),
        AbortController, FormData, URLSearchParams, getComputedStyle: node => node.style, fetch: () => { throw Error('Unexpected network'); }};
    vm.runInNewContext(source, context);
    const api = context.window.__dixyCityScan;
    const extract = () => JSON.stringify({pageUrl: context.location.href,
        address: wrongAddress ? 'Москва, Валовая, 20' : address,
        cards: Array.from({length: count}, (_, i) => ({id: String(duplicate ? 1 : i), priceRub: '109.90'}))});
    async function tick() {
        time += 450;
        const due = [...timers].filter(([, t]) => t.at <= time);
        for (const [id, t] of due) { timers.delete(id); t.fn(); }
        for (let i = 0; i < 8; i++) await Promise.resolve();
    }
    async function finish() {
        for (let i = 0; i < 450; i++) {
            await tick(); const state = JSON.parse(api.status());
            if (state.status === 'done' || state.status === 'error') return state;
        }
        throw Error('Controller did not terminate');
    }
    return {api, address, extract, tick, finish, clicks: () => clicks};
}

(async () => {
    let f = fixture(); f.api.scan(f.address, f.extract);
    let result = await f.finish(); assert.equal(result.status, 'done'); assert.equal(f.clicks(), 0);
    f = fixture({total: 4, initial: 2}); f.api.scan(f.address, f.extract);
    result = await f.finish(); assert.equal(result.status, 'done'); assert.equal(f.clicks(), 1);
    assert.equal(JSON.parse(result.value).cards.length, 4);
    f = fixture({wrongAddress: true}); f.api.scan(f.address, f.extract);
    result = await f.finish(); assert.equal(result.status, 'error'); assert.equal(f.clicks(), 0);
    f = fixture({duplicate: true}); f.api.scan(f.address, f.extract);
    result = await f.finish(); assert.equal(result.status, 'error');
    f = fixture({total: 4, initial: 2, stuck: true}); f.api.scan(f.address, f.extract);
    result = await f.finish(); assert.equal(result.status, 'error'); assert.equal(f.clicks(), 1);
    f = fixture({total: 501}); f.api.scan(f.address, f.extract);
    result = await f.finish(); assert.equal(result.status, 'error');
    // A cancelled old task must not mutate a subsequent task's status.
    f = fixture(); f.api.scan(f.address, f.extract); f.api.cancel();
    f.api.scan(f.address, f.extract); result = await f.finish(); assert.equal(result.status, 'done');
    console.log('7 Dixy page-controller scenarios passed (offline simulated DOM)');
})().catch(error => { console.error(error); process.exitCode = 1; });
