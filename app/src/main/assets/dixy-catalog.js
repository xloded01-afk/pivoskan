(function () {
    // Only rendered public catalog fields. No session, cookies or account data is read.
    function text(node) { return node ? (node.textContent || '').replace(/\s+/g, ' ').trim() : ''; }
    function money(node) {
        if (!node) return '';
        return text(node).replace(/(?:руб\.?|₽)/gi, '').trim();
    }
    var addresses = Array.prototype.map.call(document.querySelectorAll('header .address.shop .address__text'), text)
        .filter(function (s) { return s.length > 0; });
    var unique = addresses.filter(function (s, i) { return addresses.indexOf(s) === i; });
    var cards = Array.prototype.map.call(document.querySelectorAll('.listing__wrapper article.card[data-section="Пиво"]'), function (node) {
        var link = node.querySelector('.card__link[href]');
        return {
            id: node.getAttribute('data-id') || '',
            title: text(node.querySelector('.card__title')),
            url: link ? link.href : '',
            priceRub: money(node.querySelector('.card__price-num')),
            oldPriceRub: money(node.querySelector('.card__price-crossed')),
            badges: Array.prototype.map.call(node.querySelectorAll('.badges .badge'), text)
        };
    }).slice(0, 500);
    return JSON.stringify({pageUrl: location.href, address: unique.length === 1 ? unique[0] : '', cards: cards});
})();
