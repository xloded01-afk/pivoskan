(function () {
    'use strict';
    if (window.__dixyCityScan) return;
    var state = {status: 'idle', page: 0, loaded: 0};
    var cancelled = false, controller = null, operation = 0;
    function fail(message) { throw new Error(message); }
    function check() {
        if (cancelled) fail('Сканирование отменено');
        if (location.origin !== 'https://dixy.ru') fail('Переход за пределы официального сайта');
        var text = (document.body ? document.body.innerText : '').slice(0, 12000);
        if (/access denied|доступ ограничен|подтвердите.{0,30}(?:робот|человек)|проверка браузера|captcha/i.test(text))
            fail('Сайт ограничил доступ; сканирование остановлено');
        var challenge = document.querySelector('iframe[src*="captcha"], .g-recaptcha, .smart-captcha');
        if (challenge && challenge.getBoundingClientRect().height > 0) fail('Сайт запросил проверку; сканирование остановлено');
    }
    function norm(s) { return s.toLowerCase().replace(/ё/g, 'е').replace(/[\s\u00a0]+/g, ' ').trim(); }
    function sleep() { return new Promise(function (resolve) { setTimeout(resolve, 450); }); }
    async function request(path, options) {
        check();
        var token = operation, localController = new AbortController();
        controller = localController;
        var timer = setTimeout(function () { localController.abort(); }, 30000);
        try {
            var response = await fetch(path, Object.assign({credentials: 'include', signal: localController.signal, redirect: 'error'}, options));
            if (token !== operation) fail('Запрос отменён');
            if (!response.ok) fail('Официальный сайт: HTTP ' + response.status);
            var body = await response.text();
            if (token !== operation) fail('Запрос отменён');
            if (body.length > 8 * 1024 * 1024) fail('Ответ превышает безопасный предел');
            if (/^\s*</.test(body) || /captcha|access denied/i.test(body.slice(0, 1000))) fail('Сайт вернул проверку вместо данных');
            check();
            return body;
        } finally { clearTimeout(timer); if (controller === localController) controller = null; }
    }
    function launch(task) {
        var token = ++operation;
        cancelled = false;
        state = {status: 'pending', page: 0, loaded: 0};
        task().then(function (value) { if (!cancelled && token === operation) state = {status: 'done', value: value}; })
            .catch(function (e) { if (token === operation) state = {status: 'error', message: String(e.message || e)}; });
    }
    function inspect(extract, expected) {
        check();
        var snapshot = JSON.parse(extract());
        if (!snapshot.address || norm(snapshot.address) !== norm(expected)) fail('Адрес в шапке не совпадает с выбранным магазином');
        var nodes = Array.from(document.querySelectorAll('.listing__wrapper article.card[data-id]'));
        var ids = nodes.map(function (n) { return n.getAttribute('data-id'); });
        var active = document.querySelector('.listing-pagination a.active[data-page]');
        var more = document.querySelector('.more-orders');
        var countNode = document.getElementById('hdJsonScript'), total = null;
        try { total = Number(JSON.parse(countNode.textContent).offers.offerCount); } catch (_) {}
        return {snapshot: snapshot, count: nodes.length, ids: ids,
            fingerprint: JSON.stringify(snapshot.cards),
            page: active ? Number(active.dataset.page) : null,
            total: total, more: more,
            last: !!more && more.style.display === 'none'};
    }
    window.__dixyCityScan = {
        cancel: function () { cancelled = true; operation++; if (controller) controller.abort(); },
        status: function () { return JSON.stringify(state); },
        discover: function () { launch(function () { return request('/ajax/stores-json.php', {method: 'GET'}); }); },
        select: function (shop) {
            launch(async function () {
                var form = new FormData();
                form.append('action', 'saveStore'); form.append('isDelivery', 'false');
                form.append('store_id', shop.id); form.append('coords', shop.latitude + ',' + shop.longitude);
                form.append('address', shop.address);
                var body = await request('/ajax/ajax.php', {method: 'POST', body: form});
                // The subsequent fresh header is the authoritative acceptance check.
                if (/"(?:success|status)"\s*:\s*(?:false|"error")/i.test(body)) fail('Сайт не принял выбор магазина');
                return 'selected';
            });
        },
        scan: function (expected, extract) {
            launch(async function () {
                var token = operation;
                var deadline = Date.now() + 180000, wanted = 1, prior = null, stable = '', stableAt = 0, clicks = 0;
                while (Date.now() < deadline) {
                    await sleep();
                    if (token !== operation) fail('Сканирование отменено');
                    check();
                    if (location.pathname !== '/catalog/alkogolnye-napitki/pivo/' ||
                        Array.from(new URLSearchParams(location.search).keys()).some(function (key) { return key !== 'page'; }))
                        fail('Раздел или фильтры изменились; полнота каталога не подтверждена');
                    // A missing address while the new document initializes is not a wrong address.
                    var headers = document.querySelectorAll('header .address.shop .address__text');
                    if (!headers.length || !headers[0].textContent.trim()) continue;
                    var x = inspect(extract, expected);
                    state.page = x.page || wanted; state.loaded = x.count;
                    if (x.total === null || !Number.isInteger(x.total) || x.total <= 0 || x.total > 500) {
                        if (x.total > 500) fail('Каталог превышает предел 500 карточек; магазин не сохранён');
                        continue;
                    }
                    if (!x.more || !x.count || x.snapshot.cards.length === 0) continue;
                    if (x.page !== null ? x.page !== wanted : wanted !== 1) continue;
                    if (prior !== null && (x.count <= prior.count || x.fingerprint === prior.fingerprint)) continue;
                    var fingerprint = JSON.stringify([x.page, x.total, x.last, x.ids, x.fingerprint]);
                    if (stable !== fingerprint) { stable = fingerprint; stableAt = Date.now(); continue; }
                    if (Date.now() - stableAt < 1600) continue;
                    if (new Set(x.ids).size !== x.count) fail('Повторяющиеся карточки; полнота не подтверждена');
                    if (x.last) {
                        if (x.count !== x.total) fail('Неполный каталог: ' + x.count + ' из ' + x.total);
                        // A final reread verifies both the header and the exact card set being returned.
                        var final = inspect(extract, expected);
                        if (final.fingerprint !== x.fingerprint || final.count !== x.count) continue;
                        return JSON.stringify(final.snapshot);
                    }
                    if (x.count >= x.total) fail('Противоречивое состояние пагинации');
                    if (++clicks > 80) fail('Превышен предел страниц; магазин не сохранён');
                    if (x.more.disabled || getComputedStyle(x.more).display === 'none') fail('Кнопка следующей страницы недоступна');
                    prior = x; wanted++; stable = ''; stableAt = 0;
                    // Only the public site's normal event handler requests the next page.
                    x.more.click();
                }
                fail('Истекло время ожидания полного каталога');
            });
        }
    };
})();
