/* Independent multicol/XPath pagination; no Kavita Web Reader code is used. */
(() => {
    const book = document.querySelector('.book-content');
    const root = book.firstElementChild;
    let screen = 0, count = 1, vertical = false, width = 1, height = 1;
    const textNodes = () => {
        const nodes = [], walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
        while (walker.nextNode()) {
            const node = walker.currentNode;
            if (node.textContent.trim() && !node.parentElement.closest('style,script,rt,rp')) nodes.push(node);
        }
        return nodes;
    };
    const rects = node => {
        const range = document.createRange();
        range.selectNodeContents(node);
        return Array.from(range.getClientRects()).filter(r => r.width > 0 && r.height > 0);
    };
    function path(element) {
        if (!element || element === root) return '//body';
        if (element.id && !element.id.includes('"')) return 'id("' + element.id + '")';
        const parts = [];
        while (element && element !== root) {
            const tag = element.tagName.toLowerCase();
            const siblings = Array.from(element.parentElement.children).filter(e => e.tagName === element.tagName);
            parts.unshift(tag + '[' + (siblings.indexOf(element) + 1) + ']');
            element = element.parentElement;
        }
        return '//body/' + parts.join('/');
    }
    function resolve(xpath) {
        if (!xpath) return null;
        if (xpath.startsWith('id(')) {
            try { return document.evaluate(xpath, document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue; }
            catch (_) { return null; }
        }
        if (!xpath.startsWith('//body')) return null;
        let element = root;
        for (const part of xpath.slice(6).split('/').filter(Boolean)) {
            const match = /^([a-z][a-z0-9-]*)\[(\d+)\]$/.exec(part);
            if (!match || !element) return null;
            element = Array.from(element.children).filter(e => e.tagName.toLowerCase() === match[1])[Number(match[2]) - 1];
        }
        return element || null;
    }
    function anchor() {
        const height = window.innerHeight;
        for (const node of textNodes()) {
            if (rects(node).some(r => r.right > 25 && r.left < width - 25 && r.bottom > 25 && r.top < height - 25)) {
                return path(bodyElement(node.parentElement));
            }
        }
        for (const image of root.querySelectorAll('img,svg')) {
            const r = image.getBoundingClientRect();
            if (r.right > 0 && r.left < width && r.bottom > 0 && r.top < height) return path(bodyElement(image));
        }
        return '//body';
    }
    function bodyElement(element) {
        while (element && element !== root && element.parentElement !== root) element = element.parentElement;
        return element;
    }
    let lastLocation = null;
    function location() {
        const nodes = textNodes();
        const index = nodes.findIndex(node => rects(node).some(r => r.right > 25 && r.left < width - 25 &&
            r.bottom > 25 && r.top < height - 25));
        if (index < 0) return null;
        const node = nodes[index];
        let low = 0, high = node.length;
        while (low < high) {
            const middle = Math.floor((low + high) / 2), range = document.createRange();
            range.setStart(node, middle); range.setEnd(node, Math.min(node.length, middle + 1));
            const rect = range.getBoundingClientRect();
            const coordinate = vertical ? rect.top : rect.left;
            if (coordinate < 0) low = middle + 1; else high = middle;
        }
        return JSON.stringify({ text: index, offset: low });
    }
    function locateLocation(value) {
        try {
            const position = JSON.parse(value), node = textNodes()[position.text];
            if (!node) return null;
            const offset = Math.max(0, Math.min(node.length - 1, position.offset));
            const range = document.createRange();
            range.setStart(node, offset); range.setEnd(node, offset + 1);
            const rect = range.getBoundingClientRect();
            return Math.floor((vertical ? rect.top / height : rect.left / width) + 0.001);
        } catch (_) { return null; }
    }
    function show(index) {
        screen = Math.max(0, Math.min(count - 1, index));
        book.style.transform = vertical ? 'translateY(' + (-screen * height) + 'px)' :
            'translateX(' + (-screen * width) + 'px)';
    }
    function locate(element) {
        if (!element || element === root) return 0;
        const rect = element.getClientRects()[0];
        if (!rect) return 0;
        return Math.floor((vertical ? rect.top / height : rect.left / width) + 0.001);
    }
    function state() {
        lastLocation = location();
        return { screen, count, vertical, anchor: anchor(), location: lastLocation };
    }
    let spacingOverrides = [];
    function applySpacing() {
        for (const saved of spacingOverrides) {
            if (saved.value) saved.element.style.setProperty('line-height', saved.value, saved.priority);
            else saved.element.style.removeProperty('line-height');
        }
        spacingOverrides = [];
        const factor = (window.novelLineSpacing ?? 100) / 100;
        if (factor === 1) return;
        const elements = [root, ...root.querySelectorAll('*')].filter(element =>
            !element.matches('style,script,img,svg,ruby,rb,rt,rp') && !element.closest('svg,rt,rp'));
        // Measure all authored values first so inherited spacing is never multiplied twice.
        const heights = elements.map(element => {
            const style = getComputedStyle(element);
            let pixels = parseFloat(style.lineHeight);
            if (!Number.isFinite(pixels)) {
                const probe = document.createElement('span');
                Object.assign(probe.style, { position: 'fixed', top: '-10000px', display: 'block',
                    visibility: 'hidden', whiteSpace: 'nowrap', font: style.font, lineHeight: 'normal' });
                probe.textContent = 'M\u6e2c';
                document.body.appendChild(probe);
                pixels = probe.getBoundingClientRect().height;
                probe.remove();
            }
            return pixels / parseFloat(style.fontSize);
        });
        spacingOverrides = elements.map((element, index) => {
            const saved = { element, value: element.style.getPropertyValue('line-height'),
                priority: element.style.getPropertyPriority('line-height') };
            element.style.setProperty('line-height', String(heights[index] * factor), 'important');
            return saved;
        });
    }
    function layout(xpath, last = false, precise = null) {
        book.style.transform = '';
        book.style.columnWidth = '';
        book.style.columnCount = '';
        book.style.writingMode = '';
        book.style.direction = 'ltr';
        // The server scopes the book's body classes onto the fragment root.
        vertical = window.novelWritingMode == null ? getComputedStyle(root).writingMode.startsWith('vertical') : window.novelWritingMode;
        book.style.writingMode = vertical ? 'vertical-rl' : 'horizontal-tb';
        root.style.writingMode = vertical ? 'vertical-rl' : 'horizontal-tb';
        applySpacing();
        width = window.innerWidth;
        height = window.innerHeight;
        const margin = width * (window.novelMargin ?? 15) / 100;
        book.style.padding = '20px ' + margin + 'px 40px';
        // A complete column plus its gap occupies exactly one viewport in either mode.
        book.style.columnGap = (vertical ? 60 : margin * 2) + 'px';
        book.style.width = width + 'px';
        book.style.height = window.innerHeight + 'px';
        book.style.columnWidth = (vertical ? window.innerHeight : width) + 'px';
        book.style.columnCount = '1';
        // CSS multicol follows the inline axis: vertical column sets continue below.
        let maximum = vertical ? height : width;
        for (const node of textNodes()) for (const r of rects(node)) {
            maximum = Math.max(maximum, vertical ? r.bottom : r.right);
        }
        for (const image of root.querySelectorAll('img,svg')) {
            const r = image.getBoundingClientRect();
            maximum = Math.max(maximum, vertical ? r.bottom : r.right);
        }
        count = Math.max(1, Math.ceil(maximum / (vertical ? height : width) - 0.01));
        show(last ? count - 1 : (locateLocation(precise) ?? locate(resolve(xpath))));
        window.novelReader.ready = true;
        return state();
    }
    window.novelReader = {
        ready: false, state, layout,
        turn(delta) {
            const target = screen + delta;
            if (target < 0 || target >= count) return { boundary: delta };
            show(target); return state();
        },
        jump(xpath) { book.style.transform = ''; show(locate(resolve(xpath))); return state(); },
        setVertical(value) { const xpath = anchor(), precise = location(); window.novelWritingMode = value; return layout(xpath, false, precise); },
        setFontSize(value) {
            const xpath = anchor(), precise = location();
            book.style.fontSize = (value * 0.16) + 'px';
            return layout(xpath, false, precise);
        },
        path, resolve
    };
    const imageReady = Array.from(root.querySelectorAll('img')).map(image => image.complete ? Promise.resolve() :
        new Promise(resolve => { image.onload = resolve; image.onerror = resolve; }));
    Promise.race([
        Promise.all([document.fonts.ready, ...imageReady]),
        new Promise(resolve => setTimeout(resolve, 10000))
    ]).then(() => requestAnimationFrame(() => layout(window.novelInitialAnchor, window.novelOpenLast, window.novelInitialLocation)));
    let resizeTimer;
    window.addEventListener('resize', () => {
        const xpath = anchor(), precise = lastLocation;
        clearTimeout(resizeTimer);
        resizeTimer = setTimeout(() => layout(xpath, false, precise), 100);
    });
})();
