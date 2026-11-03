// Offline regression checks. Run: node app/src/test/DixyCityScanScriptTest.js
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const script = fs.readFileSync(path.join(__dirname, '../main/assets/DixyCityScan.js'), 'utf8');

function fixture(incomplete = false) {
    let now = 0, page = 1, count = 1, changedAt = Infinity, clicks = 0;
    const jobs = [];
    const more = {style: {display: 'block'}, disabled: false, click() {
        clicks++;
        context.location.href = 'https://dixy.ru/catalog/alkogolnye-napitki/pivo/?page=2';
        changedAt = now + 4500; // URL changes long before fillProducts.
    }};
    const context = {
        window: {}, URLSearchParams, AbortController, FormData,
        fetch: () => new Promise(() => {}),
        location: {origin: 'https://dixy.ru', pathname: '/catalog/alkogolnye-napitki/pivo/', search: '',
            href: 'https://dixy.ru/catalog/alkogolnye-napitki/pivo/'},
        Date: {now: () => now}, setTimeout: fn => { jobs.push(fn); return jobs.length; }, clearTimeout() {},
        getComputedStyle: () => ({display: more.style.display}),
        document: {
            body: {innerText: ''},
            querySelector(selector) {
                if (selector === '.more-orders') return more;
                if (selector === '.listing-pagination a.active[data-page]') return {dataset: {page: String(page)}};
                return null;
            },
            querySelectorAll(selector) {
                if (selector.startsWith('header')) return [{textContent: 'Москва, Ленина, 2'}];
                return Array.from({length: count}, (_, i) => ({getAttribute: () => String(i + 1)}));
            },
            getElementById: () => ({textContent: JSON.stringify({offers: {offerCount: incomplete ? 3 : 2}})})
        }
    };
    vm.runInNewContext(script, context);
    const api = context.window.__dixyCityScan;
    api.scan('Москва, Ленина, 2', () => JSON.stringify({address: 'Москва, Ленина, 2',
        cards: Array.from({length: count}, (_, i) => ({id: String(i + 1), priceRub: '100'}))}));
    return {api, clicks: () => clicks, async tick() {
        now += 450;
        if (now >= changedAt) { page = 2; count = 2; more.style.display = 'none'; }
        const job = jobs.shift(); if (job) job();
        await Promise.resolve(); await Promise.resolve(); await Promise.resolve();
        return JSON.parse(api.status());
    }};
}

(async () => {
    const scan = fixture();
    let state;
    for (let i = 0; i < 8; i++) state = await scan.tick();
    assert.equal(scan.clicks(), 1);
    assert.equal(state.status, 'pending', 'URL push must never certify a loaded page');
    for (let i = 0; i < 25 && state.status === 'pending'; i++) state = await scan.tick();
    assert.equal(state.status, 'done');
    assert.equal(JSON.parse(state.value).cards.length, 2);

    const incomplete = fixture(true);
    for (let i = 0; i < 35; i++) state = await incomplete.tick();
    assert.equal(state.status, 'error');
    assert.match(state.message, /Неполный каталог/);

    const cancelled = fixture();
    cancelled.api.cancel();
    for (let i = 0; i < 20; i++) await cancelled.tick();
    assert.equal(cancelled.clicks(), 0, 'Cancelled operations must not paginate later');

    const replaced = fixture();
    for (let i = 0; i < 8; i++) await replaced.tick();
    replaced.api.cancel();
    replaced.api.select({id: 'next', latitude: 55, longitude: 37, address: 'Москва, Ленина, 3'});
    for (let i = 0; i < 25; i++) state = await replaced.tick();
    assert.equal(state.status, 'pending', 'Old scan must not finish or overwrite the new selection');
    assert.equal(state.loaded, 0, 'Old scan must not publish progress into the next operation');
    assert.equal(replaced.clicks(), 1, 'Old scan must never click after replacement');
    console.log('DixyCityScan: 4 offline script regression checks passed');
})().catch(error => { console.error(error); process.exitCode = 1; });
