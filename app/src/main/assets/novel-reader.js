/* Independent multicol/XPath pagination; no Kavita Web Reader code is used.
 * Kavita appends scoped book CSS after our wrapper styles. Kamigura-owned declarations
 * go through readerStyle as inline !important; restoring book styles uses their saved priority.
 */
(() => {
    const book = document.querySelector('.book-content');
    const root = book.firstElementChild;
    function readerStyle(element, property, value, priority = 'important') {
        if (value === '') element.style.removeProperty(property);
        else element.style.setProperty(property, value, priority);
    }
    function applyFontSize(percent) {
        readerStyle(book, 'font-size', (percent * 0.16) + 'px');
    }
    applyFontSize(window.novelFontSize ?? 100);
    const svgColors = new WeakMap();
    const svgPaintProperties = ['color', 'fill', 'stroke', '-webkit-text-fill-color',
        '-webkit-text-stroke-color', 'text-decoration-color', 'text-emphasis-color', 'text-shadow'];
    function setColors(background, text) {
        // Inline SVG illustrations may use inherited currentColor; freeze their original paint.
        readerStyle(book, 'color', '#101010');
        for (const element of root.querySelectorAll('svg,svg *')) {
            if (!svgColors.has(element)) {
                const style = getComputedStyle(element);
                svgColors.set(element, svgPaintProperties.map(property => style.getPropertyValue(property)));
            }
        }
        for (const element of root.querySelectorAll('svg,svg *')) {
            svgPaintProperties.forEach((property, index) =>
                readerStyle(element, property, svgColors.get(element)[index]));
        }
        readerStyle(document.documentElement, '--novel-text-color', text);
        for (const element of [document.documentElement, document.body, book, ...root.querySelectorAll('*'), root]) {
            if (element.closest('img,svg,video,canvas')) continue;
            readerStyle(element, 'color', text);
            readerStyle(element, '-webkit-text-fill-color', text);
            readerStyle(element, '-webkit-text-stroke-color', text);
            readerStyle(element, 'text-decoration-color', text);
            readerStyle(element, 'text-emphasis-color', text);
            readerStyle(element, 'text-shadow', 'none');
            readerStyle(element, 'background-color',
                element === document.documentElement || element === document.body || element === book ? background : 'transparent');
        }
    }
    setColors(window.novelColors.background, window.novelColors.text);
    let screen = 0, count = 1, vertical = false, width = 1, height = 1;
    const textNodes = () => {
        const nodes = [], walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
        while (walker.nextNode()) {
            const node = walker.currentNode;
            if (node.textContent.trim() && !node.parentElement.closest('style,script,rt,rp,.visually-hidden')) nodes.push(node);
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
            const text = /^text\(\)\[(\d+)\]$/.exec(part);
            if (text && element) {
                element = Array.from(element.childNodes).filter(n => n.nodeType === Node.TEXT_NODE)[Number(text[1]) - 1];
                continue;
            }
            const match = /^([a-z][a-z0-9-]*)\[(\d+)\]$/.exec(part);
            if (!match || !element || !element.children) return null;
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
    function bookmarkContext(paths) {
        const xpath = anchor(), element = resolve(xpath) || root;
        const text = textNodes().filter(node => element.contains(node))
            .map(node => node.textContent).join('').replace(/\s+/g, ' ').trim();
        const visible = [];
        paths.forEach((path, index) => {
            const target = path ? resolve(path) : root;
            if (target === root ? screen === 0 : target && rects(target).some(r =>
                r.right > 0 && r.left < width && r.bottom > 0 && r.top < height)) visible.push(index);
        });
        return { anchor: xpath, title: Array.from(text).slice(0, 20).join(''), visible };
    }
    function contentsParts(parts) {
        const reference = textNodes().find(node => rects(node).some(r =>
            r.right > 25 && r.left < width - 25 && r.bottom > 25 && r.top < height - 25)) || resolve(anchor());
        return parts.filter(part => {
            const target = document.getElementById(part);
            return target && root.contains(target) && reference &&
                (target === reference || target.contains(reference) ||
                    (target.compareDocumentPosition(reference) & Node.DOCUMENT_POSITION_FOLLOWING) !== 0);
        });
    }
    function linkRegions() {
        const regions = [];
        for (const link of root.querySelectorAll('a[href],a[kavita-page],a[kavita-note-ref]')) {
            const style = getComputedStyle(link);
            if (style.visibility !== 'visible' || style.pointerEvents === 'none' || link.closest('.visually-hidden')) continue;
            for (const rect of link.getClientRects()) {
                const left = Math.max(0, rect.left), top = Math.max(0, rect.top);
                const right = Math.min(innerWidth, rect.right), bottom = Math.min(innerHeight, rect.bottom);
                if (right <= left || bottom <= top) continue;
                regions.push({ left: left / innerWidth, top: top / innerHeight, right: right / innerWidth,
                    bottom: bottom / innerHeight, page: link.getAttribute('kavita-page'),
                    part: link.getAttribute('kavita-part'), href: link.getAttribute('href'), note: link.hasAttribute('kavita-note-ref') });
            }
        }
        return regions;
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
        readerStyle(book, 'transform', vertical ? 'translateY(' + (-screen * height) + 'px)' :
            'translateX(' + (-screen * width) + 'px)');
    }
    function locate(element) {
        if (!element || element === root) return 0;
        const rect = element.nodeType === Node.TEXT_NODE ? rects(element)[0] : element.getClientRects()[0];
        if (!rect) return 0;
        return Math.floor((vertical ? rect.top / height : rect.left / width) + 0.001);
    }
    function state() {
        // Reflow may reveal an earlier page start; don't walk backward on each reflow.
        if (lastLocation == null) lastLocation = location();
        return { screen, count, vertical, anchor: anchor(), location: lastLocation, links: linkRegions() };
    }
    let spacingOverrides = [];
    const imageStyles = new WeakMap();
    const imageSizeProperties = ['width', 'height', 'min-width', 'min-height', 'max-width', 'max-height',
        'object-fit', 'break-inside'];
    function fitImages() {
        const style = getComputedStyle(book);
        const availableWidth = Math.max(1, book.clientWidth - parseFloat(style.paddingLeft) - parseFloat(style.paddingRight));
        const availableHeight = Math.max(1, book.clientHeight - parseFloat(style.paddingTop) - parseFloat(style.paddingBottom));
        for (const image of root.querySelectorAll('img,svg')) {
            if (image.matches('img.gaiji') || image.parentElement.closest('svg')) continue;
            if (!imageStyles.has(image)) imageStyles.set(image, imageSizeProperties.map(property => ({
                value: image.style.getPropertyValue(property), priority: image.style.getPropertyPriority(property)
            })));
            imageSizeProperties.forEach((property, index) => {
                const saved = imageStyles.get(image)[index];
                readerStyle(image, property, saved.value, saved.priority);
            });
            const bounds = image.getBoundingClientRect();
            const scale = Math.min(1, availableWidth / bounds.width, availableHeight / bounds.height);
            // Percentage limits have no useful block-size reference in fragmented book content.
            readerStyle(image, 'max-width', availableWidth + 'px');
            readerStyle(image, 'max-height', availableHeight + 'px');
            readerStyle(image, 'object-fit', 'contain');
            readerStyle(image, 'break-inside', 'avoid');
            if (scale < 1) {
                const box = image.viewBox?.baseVal;
                const ratio = image.naturalWidth > 0 && image.naturalHeight > 0 ? image.naturalWidth / image.naturalHeight :
                    box?.width > 0 && box?.height > 0 ? box.width / box.height : bounds.width / bounds.height;
                const fittedWidth = Math.min(bounds.width, bounds.height * ratio, availableWidth, availableHeight * ratio);
                readerStyle(image, 'min-width', '0');
                readerStyle(image, 'min-height', '0');
                readerStyle(image, 'width', fittedWidth + 'px');
                readerStyle(image, 'height', fittedWidth / ratio + 'px');
            }
        }
    }
    function applySpacing() {
        for (const saved of spacingOverrides) {
            readerStyle(saved.element, 'line-height', saved.value, saved.priority);
        }
        spacingOverrides = [];
        const factor = (window.novelLineSpacing ?? 100) / 100;
        if (factor === 1) return;
        const elements = [root, ...root.querySelectorAll('*')].filter(element =>
            !element.matches('style,script,img,svg,ruby,rb,rt,rp') && !element.closest('svg,rt,rp'));
        const prose = textNodes();
        // Measure all authored values first so inherited spacing is never multiplied twice.
        const heights = elements.map(element => {
            const style = getComputedStyle(element);
            let pixels = parseFloat(style.lineHeight);
            if (!Number.isFinite(pixels)) {
                const probe = document.createElement('span');
                for (const [property, value] of Object.entries({ position: 'fixed', top: '-10000px', display: 'block',
                    visibility: 'hidden', 'white-space': 'nowrap', font: style.font, 'line-height': 'normal',
                    'writing-mode': style.writingMode })) readerStyle(probe, property, value);
                // Use this element's actual prose so fallback-font metrics are preserved.
                probe.textContent = prose.find(node => element.contains(node))?.textContent || 'M';
                document.body.appendChild(probe);
                const bounds = probe.getBoundingClientRect();
                pixels = style.writingMode.startsWith('vertical') ? bounds.width : bounds.height;
                probe.remove();
            }
            return pixels / parseFloat(style.fontSize);
        });
        spacingOverrides = elements.map((element, index) => {
            const saved = { element, value: element.style.getPropertyValue('line-height'),
                priority: element.style.getPropertyPriority('line-height') };
            readerStyle(element, 'line-height', String(heights[index] * factor));
            return saved;
        });
    }
    function layout(xpath, last = false, precise = null) {
        readerStyle(book, 'transform', 'none');
        readerStyle(book, 'column-width', '');
        readerStyle(book, 'column-count', '');
        readerStyle(book, 'writing-mode', '');
        readerStyle(book, 'direction', 'ltr');
        // The server scopes the book's body classes onto the fragment root.
        vertical = window.novelWritingMode == null ? getComputedStyle(root).writingMode.startsWith('vertical') : window.novelWritingMode;
        readerStyle(book, 'writing-mode', vertical ? 'vertical-rl' : 'horizontal-tb');
        readerStyle(root, 'writing-mode', vertical ? 'vertical-rl' : 'horizontal-tb');
        applySpacing();
        width = window.innerWidth;
        height = window.innerHeight;
        const margin = width * (window.novelMargin ?? 15) / 100;
        readerStyle(book, 'box-sizing', 'border-box');
        readerStyle(book, 'column-fill', 'auto');
        readerStyle(book, 'padding', '20px ' + margin + 'px 40px');
        // A complete column plus its gap occupies exactly one viewport in either mode.
        readerStyle(book, 'column-gap', (vertical ? 60 : margin * 2) + 'px');
        readerStyle(book, 'width', width + 'px');
        readerStyle(book, 'height', window.innerHeight + 'px');
        readerStyle(book, 'column-width', (vertical ? window.innerHeight : width) + 'px');
        readerStyle(book, 'column-count', '1');
        fitImages();
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
        const preciseScreen = locateLocation(precise);
        show(last ? count - 1 : (preciseScreen ?? locate(resolve(xpath))));
        lastLocation = !last && preciseScreen != null ? precise : null;
        window.novelReader.ready = true;
        return state();
    }
    window.novelReader = {
        ready: false, state, layout, setColors, bookmarkContext, contentsParts,
        reflow() { return layout(anchor(), false, lastLocation); },
        turn(delta) {
            const target = screen + delta;
            if (target < 0 || target >= count) return { boundary: delta };
            show(target); lastLocation = null; return state();
        },
        jump(xpath) {
            const target = xpath ? resolve(xpath) : root;
            if (!target || !root.contains(target)) return { error: 'Reading position is no longer available.' };
            readerStyle(book, 'transform', 'none');
            show(locate(target)); lastLocation = null; return state();
        },
        setVertical(value) { const xpath = anchor(), precise = lastLocation ?? location(); window.novelWritingMode = value; return layout(xpath, false, precise); },
        setFontSize(value) {
            const xpath = anchor(), precise = lastLocation ?? location();
            applyFontSize(value);
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
