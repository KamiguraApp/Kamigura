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
    function authoredRules() {
        const rules = [];
        function collect(sheet) {
            try {
                for (const rule of sheet.cssRules) {
                    rules.push(rule);
                    if (rule.styleSheet) collect(rule.styleSheet);
                    else if (rule.cssRules) collect(rule);
                }
            } catch (_) { /* An unavailable book stylesheet has no usable declaration. */ }
        }
        for (const sheet of document.styleSheets) if (sheet.ownerNode?.id !== 'novel-slot-fonts') collect(sheet);
        return rules;
    }
    const rules = authoredRules();
    const proseElements = [root, ...root.querySelectorAll('*')].filter(element => !element.closest('svg,style,script'));
    // The wrapper supplies only a fallback; scoped book CSS participates in the normal cascade.
    const authoredVertical = getComputedStyle(root).writingMode.startsWith('vertical');
    const families = value => (value.match(/"[^"]*"|'[^']*'|[^,]+/g) || []).map(name => name.trim().replace(/^["']|["']$/g, ''));
    const embeddedFamilies = new Set(rules.filter(rule => rule.type === CSSRule.FONT_FACE_RULE && /url\(/i.test(rule.style.getPropertyValue('src')))
        .flatMap(rule => families(rule.style.getPropertyValue('font-family'))).map(name => name.toLowerCase()));
    const originalFonts = proseElements.map(element => ({ element, names: families(getComputedStyle(element).fontFamily) }));
    function applyFonts(options) {
        let style = document.getElementById('novel-slot-fonts');
        if (!style) { style = document.createElement('style'); style.id = 'novel-slot-fonts'; document.head.appendChild(style); }
        style.textContent = ['Mincho', 'Gothic'].map(slot => {
            const url = options[slot.toLowerCase()];
            return url ? `@font-face{font-family:"Kamigura${slot}";src:url("${url}");font-display:block;}` : '';
        }).join('\n');
        for (const { element, names } of originalFonts) {
            const embedded = names.filter(name => embeddedFamilies.has(name.toLowerCase()));
            const declared = names.filter(name => name !== 'KamiguraBookDefault');
            const sans = declared.some(name => /sans|gothic|ゴシック/i.test(name));
            const slot = declared.length ? (sans ? 'Gothic' : 'Mincho') : options.defaultSlot;
            const fallback = slot === 'Gothic' ? '"Noto Sans CJK JP",sans-serif' : '"Noto Serif CJK JP",serif';
            readerStyle(element, 'font-family', [...embedded.map(name => JSON.stringify(name)), `"Kamigura${slot}"`, fallback].join(','));
        }
    }
    applyFonts(window.novelFonts || { defaultSlot: 'Mincho' });
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
    let resizeTimer;
    let pagePadding = { left: 0, top: 0, right: 0, bottom: 0 };
    const textNodes = () => {
        const nodes = [], walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
        while (walker.nextNode()) {
            const node = walker.currentNode;
            if (node.textContent.trim() && !node.parentElement.closest('style,script,rt,rp,.visually-hidden')) nodes.push(node);
        }
        return nodes;
    };
    const rects = node => {
        if (node.nodeType === Node.ELEMENT_NODE && node.matches('img,svg')) return Array.from(node.getClientRects());
        const range = document.createRange();
        range.selectNodeContents(node);
        return Array.from(range.getClientRects()).filter(r => r.width > 0 && r.height > 0);
    };
    const visible = r => r.right > pagePadding.left && r.left < width - pagePadding.right &&
        r.bottom > pagePadding.top && r.top < height - pagePadding.bottom;
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
        for (const node of textNodes()) {
            if (rects(node).some(visible)) {
                return path(blockElement(node.parentElement));
            }
        }
        for (const image of root.querySelectorAll('img,svg')) {
            const r = image.getBoundingClientRect();
            if (visible(r)) return path(blockElement(image.parentElement));
        }
        return '//body';
    }
    function blockElement(element) {
        while (element && element !== root) {
            if (!/^(inline|ruby|contents)/.test(getComputedStyle(element).display)) return element;
            element = element.parentElement;
        }
        return root;
    }
    function bookmarkContext(paths) {
        const xpath = anchor(), element = resolve(xpath) || root;
        const text = textNodes().filter(node => element.contains(node))
            .map(node => node.textContent).join('').replace(/\s+/g, ' ').trim();
        const selected = [];
        paths.forEach((path, index) => {
            const target = path ? resolve(path) : root;
            if (target === root ? screen === 0 : target && rects(target).some(visible)) selected.push(index);
        });
        return { anchor: xpath, title: Array.from(text).slice(0, 20).join(''), visible: selected };
    }
    function contentsParts(parts) {
        const reference = textNodes().find(node => rects(node).some(visible)) || resolve(anchor());
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
                const left = Math.max(pagePadding.left, rect.left), top = Math.max(pagePadding.top, rect.top);
                const right = Math.min(innerWidth - pagePadding.right, rect.right), bottom = Math.min(innerHeight - pagePadding.bottom, rect.bottom);
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
        const index = nodes.findIndex(node => rects(node).some(visible));
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
    function resolveLocation(value) {
        try {
            const position = JSON.parse(value), node = textNodes()[position.text];
            if (!node) return null;
            const offset = Math.max(0, Math.min(node.length - 1, position.offset));
            const range = document.createRange();
            range.setStart(node, offset); range.setEnd(node, offset + 1);
            return range;
        } catch (_) { return null; }
    }
    function startRect(target) {
        if (!target) return null;
        if (target instanceof Range) return target.getBoundingClientRect();
        const node = target.nodeType === Node.TEXT_NODE ? target : textNodes().find(node => target.contains(node) && rects(node).length > 0);
        if (!node) return target.getClientRects()[0] || null;
        const range = document.createRange();
        const offset = Math.max(0, node.textContent.search(/\S/u));
        range.setStart(node, offset); range.setEnd(node, Math.min(offset + 1, node.length));
        return range.getBoundingClientRect();
    }
    function show(index) {
        screen = Math.max(0, Math.min(count - 1, index));
        readerStyle(book, 'transform', vertical ? 'translateY(' + (-screen * height) + 'px)' :
            'translateX(' + (-screen * width) + 'px)');
    }
    function locate(element) {
        if (!element || element === root) return 0;
        const rect = startRect(element);
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
    let illustrationOverrides = [];
    function restoreIllustrationLayout() {
        for (const { element, property, value, priority } of illustrationOverrides) readerStyle(element, property, value, priority);
        illustrationOverrides = [];
    }
    function fitIllustrationPage(availableWidth, availableHeight) {
        if (textNodes().some(node => !node.parentElement.closest('svg')) || root.querySelector('video,audio,canvas,iframe')) return;
        const images = Array.from(root.querySelectorAll('img,svg')).filter(image => !image.parentElement.closest('svg'));
        const spacers = images.filter(image => image.matches('img:not(.gaiji)') && image.naturalWidth === 1 && image.naturalHeight === 1);
        const artwork = images.filter(image => !spacers.includes(image));
        if (artwork.length !== 1 || artwork[0].matches('img.gaiji')) return;
        const image = artwork[0], box = image.viewBox?.baseVal, bounds = image.getBoundingClientRect();
        const naturalWidth = image.naturalWidth || box?.width || image.width?.baseVal?.value || 0;
        const naturalHeight = image.naturalHeight || box?.height || image.height?.baseVal?.value || 0;
        // Only substantial artwork is a sheet; icons and authored miniature images keep their layout.
        if (Math.min(naturalWidth, naturalHeight) < 320 || Math.max(bounds.width, bounds.height) <= 128) return;
        const scale = Math.min(availableWidth / naturalWidth, availableHeight / naturalHeight);
        const fittedWidth = naturalWidth * scale, fittedHeight = naturalHeight * scale;
        function override(element, property, value) {
            illustrationOverrides.push({ element, property, value: element.style.getPropertyValue(property),
                priority: element.style.getPropertyPriority(property) });
            readerStyle(element, property, value);
        }
        const ancestors = new Set([root]);
        for (let parent = image.parentElement; parent && parent !== root; parent = parent.parentElement) ancestors.add(parent);
        for (const element of root.querySelectorAll('*')) {
            if (element === image || image.contains(element) || element.matches('style,script')) continue;
            if (!ancestors.has(element)) override(element, 'display', 'none');
            else if (!element.matches('a')) override(element, 'display', 'contents');
            else {
                for (const [property, value] of Object.entries({ display: 'flex', width: fittedWidth + 'px', height: fittedHeight + 'px',
                    'max-width': 'none', 'max-height': 'none', margin: '0', padding: '0', border: '0', position: 'static', float: 'none' })) override(element, property, value);
            }
        }
        // Keep every node and XPath in place, but remove spacer/wrapper boxes from this one sheet's flow.
        for (const [property, value] of Object.entries({ display: 'flex', 'align-items': 'center', 'justify-content': 'center',
            width: availableWidth + 'px', height: availableHeight + 'px', 'writing-mode': 'horizontal-tb',
            margin: '0', padding: '0', border: '0', 'min-width': '0', 'min-height': '0', 'max-width': 'none', 'max-height': 'none',
            'box-sizing': 'border-box', position: 'static', float: 'none' })) override(root, property, value);
        for (const [property, value] of Object.entries({ display: 'block', width: fittedWidth + 'px', height: fittedHeight + 'px',
            'min-width': '0', 'min-height': '0', margin: '0', padding: '0', border: '0', position: 'static', float: 'none' })) override(image, property, value);
    }
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
            const box = image.viewBox?.baseVal;
            const ratio = image.naturalWidth > 0 && image.naturalHeight > 0 ? image.naturalWidth / image.naturalHeight :
                box?.width > 0 && box?.height > 0 ? box.width / box.height : bounds.width / bounds.height;
            // A book's percentage max-size may already have clamped just one axis.
            const clamped = (bounds.height >= availableHeight - .5 || bounds.width >= availableWidth - .5) &&
                Math.abs(bounds.width / bounds.height - ratio) > .005;
            if (scale < 1 || clamped) {
                const fittedWidth = Math.min(bounds.width, bounds.height * ratio, availableWidth, availableHeight * ratio);
                readerStyle(image, 'min-width', '0');
                readerStyle(image, 'min-height', '0');
                readerStyle(image, 'width', fittedWidth + 'px');
                readerStyle(image, 'height', fittedWidth / ratio + 'px');
            }
        }
        fitIllustrationPage(availableWidth, availableHeight);
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
        // This layout consumes the current viewport; an older resize must not undo a later jump.
        clearTimeout(resizeTimer);
        restoreIllustrationLayout();
        readerStyle(book, 'transform', 'none');
        readerStyle(book, 'column-width', '');
        readerStyle(book, 'column-count', '');
        readerStyle(book, 'writing-mode', '');
        readerStyle(book, 'direction', 'ltr');
        // The server scopes the book's body classes onto the fragment root.
        vertical = window.novelWritingMode ?? authoredVertical;
        readerStyle(book, 'writing-mode', vertical ? 'vertical-rl' : 'horizontal-tb');
        readerStyle(root, 'writing-mode', vertical ? 'vertical-rl' : 'horizontal-tb');
        applySpacing();
        width = window.innerWidth;
        height = window.innerHeight;
        const margin = window.novelMargin ?? 20, cutout = window.novelCutout || {};
        const edge = name => Math.max(margin, (cutout[name] || 0) > 0 ? cutout[name] + 8 : 0);
        const left = edge('left'), top = edge('top'), right = edge('right'), bottom = edge('bottom');
        pagePadding = { left, top, right, bottom };
        readerStyle(document.getElementById('novel-viewport'), 'clip-path', `inset(${top}px ${right}px ${bottom}px ${left}px)`);
        readerStyle(book, 'box-sizing', 'border-box');
        readerStyle(book, 'column-fill', 'auto');
        readerStyle(book, 'padding', `${top}px ${right}px ${bottom}px ${left}px`);
        // A complete column plus its gap occupies exactly one viewport in either mode.
        readerStyle(book, 'column-gap', (vertical ? top + bottom : left + right) + 'px');
        readerStyle(book, 'width', width + 'px');
        readerStyle(book, 'height', window.innerHeight + 'px');
        readerStyle(book, 'column-width', (vertical ? window.innerHeight : width) + 'px');
        readerStyle(book, 'column-count', '1');
        fitImages();
        const preciseTarget = resolveLocation(precise);
        const target = preciseTarget || resolve(xpath);
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
        show(last ? count - 1 : locate(target));
        lastLocation = !last && preciseTarget != null ? precise : null;
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
            return layout(xpath);
        },
        setVertical(value) { const xpath = anchor(), precise = lastLocation ?? location(); window.novelWritingMode = value; return layout(xpath, false, precise); },
        setFontSize(value) {
            const xpath = anchor(), precise = lastLocation ?? location();
            applyFontSize(value);
            window.novelFontSize = value;
            return layout(xpath, false, precise);
        },
        setTypography(fontSize, lineSpacing) {
            if (window.novelFontSize === fontSize && window.novelLineSpacing === lineSpacing) return state();
            const xpath = anchor(), precise = lastLocation ?? location();
            window.novelFontSize = fontSize; window.novelLineSpacing = lineSpacing;
            applyFontSize(fontSize);
            return layout(xpath, false, precise);
        },
        setPageLayout(margin, cutout) {
            if (width === innerWidth && height === innerHeight && window.novelMargin === margin &&
                JSON.stringify(window.novelCutout) === JSON.stringify(cutout)) return state();
            const xpath = anchor(), precise = lastLocation ?? location();
            window.novelMargin = margin; window.novelCutout = cutout;
            return layout(xpath, false, precise);
        },
        async setFonts(options) {
            const xpath = anchor(), precise = lastLocation ?? location();
            applyFonts(options);
            void root.offsetHeight;
            await document.fonts.ready;
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
    window.addEventListener('resize', () => {
        const xpath = anchor(), precise = lastLocation;
        clearTimeout(resizeTimer);
        resizeTimer = setTimeout(() => layout(xpath, false, precise), 100);
    });
})();
