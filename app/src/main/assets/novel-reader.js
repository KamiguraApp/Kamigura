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
        updateImageMarkers();
    }
    function locate(element) {
        if (!element || element === root) return 0;
        const rect = startRect(element);
        if (!rect) return 0;
        return Math.floor((vertical ? rect.top / height : rect.left / width) + 0.001);
    }
    const svgIllustrationSizes = new Map();
    function svgIllustrationSize(image) {
        const href = originalImageUrls.get(image) || image.href?.baseVal ||
            image.getAttribute('href') || image.getAttributeNS('http://www.w3.org/1999/xlink', 'href');
        if (!href) return null;
        let url;
        try { url = new URL(href, document.baseURI).href; } catch (_) { return null; }
        // The prefetch already read the intrinsic size from the image header; don't load it again.
        const known = resourceSizes[imageUrl(url)]?.size;
        if (known) return { url, width: known[0], height: known[1], ready: Promise.resolve() };
        if (!svgIllustrationSizes.has(url)) {
            const entry = { url, width: 0, height: 0 };
            entry.ready = new Promise(resolve => {
                const probe = new Image();
                const timer = setTimeout(finish, 10000);
                function finish() {
                    clearTimeout(timer);
                    entry.width = probe.naturalWidth; entry.height = probe.naturalHeight;
                    probe.onload = probe.onerror = null;
                    probe.removeAttribute('src');
                    resolve();
                }
                probe.onload = probe.onerror = finish;
                probe.src = url;
            });
            svgIllustrationSizes.set(url, entry);
        }
        return svgIllustrationSizes.get(url);
    }
    function svgIllustrationVisible(image) {
        if (image.closest('.gaiji,.visually-hidden') || waitingImages.has(image)) return false;
        const box = image.getBoundingClientRect();
        if (box.width <= 0 || box.height <= 0 || Math.max(box.width, box.height) < 64 ||
            box.right <= 0 || box.bottom <= 0 || box.left >= innerWidth || box.top >= innerHeight) return false;
        for (let element = image; element && element !== book.parentElement; element = element.parentElement) {
            const style = getComputedStyle(element);
            if (style.display === 'none' || style.visibility === 'hidden' || Number(style.opacity) === 0 ||
                (style.clipPath && style.clipPath !== 'none') || (style.maskImage && style.maskImage !== 'none')) return false;
        }
        return true;
    }
    function clipIllustration(points, box) {
        for (const [axis, edge, greater] of [['x', box.left, true], ['x', box.right, false],
            ['y', box.top, true], ['y', box.bottom, false]]) {
            const output = [];
            for (let index = 0; index < points.length; index++) {
                const a = points[index], b = points[(index + 1) % points.length];
                const insideA = greater ? a[axis] >= edge : a[axis] <= edge;
                const insideB = greater ? b[axis] >= edge : b[axis] <= edge;
                if (insideA) output.push(a);
                if (insideA !== insideB) {
                    const fraction = (edge - a[axis]) / (b[axis] - a[axis]);
                    const intersection = { x: a.x + (b.x - a.x) * fraction, y: a.y + (b.y - a.y) * fraction };
                    intersection[axis] = edge;
                    output.push(intersection);
                }
            }
            points = output;
        }
        return points;
    }
    function svgIllustrationRegion(image) {
        if (!svgIllustrationVisible(image)) return [];
        const intrinsic = svgIllustrationSize(image), matrix = image.getScreenCTM();
        if (!matrix || !intrinsic || Math.min(intrinsic.width, intrinsic.height) < 128) return [];
        const x = image.x.baseVal.value, y = image.y.baseVal.value;
        const w = image.width.baseVal.value, h = image.height.baseVal.value;
        if (w <= 0 || h <= 0) return [];
        const aspect = image.preserveAspectRatio.baseVal;
        let painted = { left: x, top: y, right: x + w, bottom: y + h };
        if (aspect.align !== 1) {
            const scale = (aspect.meetOrSlice === 2 ? Math.max : Math.min)(w / intrinsic.width, h / intrinsic.height);
            const align = aspect.align >= 2 && aspect.align <= 10 ? aspect.align - 2 : 4;
            const left = x + (w - intrinsic.width * scale) * (align % 3) / 2;
            const top = y + (h - intrinsic.height * scale) * Math.floor(align / 3) / 2;
            painted = { left: Math.max(x, left), top: Math.max(y, top),
                right: Math.min(x + w, left + intrinsic.width * scale), bottom: Math.min(y + h, top + intrinsic.height * scale) };
        }
        let points = [[painted.left, painted.top], [painted.right, painted.top],
            [painted.right, painted.bottom], [painted.left, painted.bottom]].map(([x, y]) =>
                ({ x: matrix.a * x + matrix.c * y + matrix.e, y: matrix.b * x + matrix.d * y + matrix.f }));
        // SVG viewports can clip a smaller/transformed image independently of the book viewport.
        for (let svg = image.ownerSVGElement; svg; svg = svg.ownerSVGElement) {
            const style = getComputedStyle(svg);
            if (style.overflow !== 'visible') points = clipIllustration(points, svg.getBoundingClientRect());
        }
        points = clipIllustration(points, { left: 0, top: 0, right: innerWidth, bottom: innerHeight });
        if (points.length < 3) return [];
        const left = Math.min(...points.map(p => p.x)), right = Math.max(...points.map(p => p.x));
        const top = Math.min(...points.map(p => p.y)), bottom = Math.max(...points.map(p => p.y));
        if (right <= left || bottom <= top || Math.max(right - left, bottom - top) < 64) return [];
        const normalized = (value, span) => Math.max(0, Math.min(1, value / span));
        return [{ url: intrinsic.url, left: normalized(left, innerWidth), top: normalized(top, innerHeight),
            right: normalized(right, innerWidth), bottom: normalized(bottom, innerHeight),
            points: points.map(p => [normalized(p.x, innerWidth), normalized(p.y, innerHeight)]) }];
    }
    function illustrationRegions() {
        const images = Array.from(root.querySelectorAll('img')).filter(image => {
            const style = getComputedStyle(image);
            return !image.closest('svg,.visually-hidden') && !image.matches('.gaiji') && image.complete &&
                Math.min(image.naturalWidth, image.naturalHeight) >= 128 && style.visibility !== 'hidden' &&
                style.display !== 'none' && Number(style.opacity) !== 0;
        }).flatMap(image => {
            const box = image.getBoundingClientRect();
            if (box.width <= 0 || box.height <= 0 || Math.max(box.width, box.height) < 64 || box.right <= 0 || box.bottom <= 0 ||
                box.left >= innerWidth || box.top >= innerHeight) return [];
            return [{ url: originalImageUrls.get(image) || image.currentSrc || image.src, left: Math.max(0, box.left) / innerWidth,
                top: Math.max(0, box.top) / innerHeight, right: Math.min(innerWidth, box.right) / innerWidth,
                bottom: Math.min(innerHeight, box.bottom) / innerHeight }];
        });
        return images.concat(Array.from(root.querySelectorAll('svg image')).flatMap(svgIllustrationRegion));
    }
    async function prepareIllustrations() {
        await Promise.all(Array.from(root.querySelectorAll('svg image')).filter(svgIllustrationVisible)
            .map(image => svgIllustrationSize(image)?.ready));
        return illustrationRegions();
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
    const waitingImages = new Map();
    const frozenImages = new Map();
    const originalImageUrls = new WeakMap();
    let resourceSizes = window.novelImageSizes || {};
    // Width / height of the frame kept for an illustration whose size is not known yet.
    const PendingImageAspect = .71;
    let imageRevision = 0, sheetImage = null, imageViewport;
    function imageUrl(source) {
        try {
            const url = new URL(source, document.baseURI);
            url.hash = '';
            return url.href;
        } catch (_) { return ''; }
    }
    function imagePending(image) {
        return waitingImages.get(image)?.done === false || !image.complete;
    }
    function rasterSize(image) {
        if (image.naturalWidth > 0 && image.naturalHeight > 0) return [image.naturalWidth, image.naturalHeight];
        const url = originalImageUrls.get(image) || imageUrl(image.currentSrc || image.src);
        if (resourceSizes[url]?.size) return resourceSizes[url].size;
        const width = Number(image.getAttribute('width')), height = Number(image.getAttribute('height'));
        return width > 0 && height > 0 ? [width, height] : null;
    }
    function updateImageMarkers() {
        for (const [image, entry] of waitingImages) {
            if (!entry.marker) continue;
            const box = image.getBoundingClientRect();
            const show = visible(box) && box.width > 0 && box.height > 0 &&
                getComputedStyle(image).visibility === 'visible';
            entry.marker.hidden = !show;
            if (show) {
                const left = Math.max(box.left, pagePadding.left), right = Math.min(box.right, width - pagePadding.right);
                const top = Math.max(box.top, pagePadding.top), bottom = Math.min(box.bottom, height - pagePadding.bottom);
                entry.marker.style.left = (left + right) / 2 + 'px';
                entry.marker.style.top = (top + bottom) / 2 + 'px';
            }
        }
    }
    function watchImage(image, source, managed, decorate) {
        const marker = decorate ? document.createElement('span') : null;
        if (marker) {
            marker.className = 'novel-image-wait';
            marker.setAttribute('aria-hidden', 'true');
            marker.hidden = true;
            marker.textContent = '...';
            document.getElementById('novel-viewport').appendChild(marker);
            if (image.matches('img')) image.setAttribute('data-novel-pending', '');
        }
        const entry = { marker, done: false, source, managed, retried: false, failed: image.complete === true };
        waitingImages.set(image, entry);
        function finish(success) {
            entry.done = true;
            if (success) {
                image.removeAttribute('data-novel-pending');
                marker?.remove();
                waitingImages.delete(image);
            } else if (marker) marker.textContent = '-';
            requestAnimationFrame(settleImages);
        }
        image.addEventListener('load', () => finish(true), { once: true });
        image.addEventListener('error', () => {
            if (entry.managed && !entry.retried) {
                entry.failed = true;
                requestAnimationFrame(settleImages);
            } else finish(false);
        });
    }
    function retryReadyImages() {
        for (const [image, entry] of waitingImages) {
            if (!entry.managed || !entry.failed || entry.retried || !resourceSizes[entry.source]?.ready) continue;
            // The first response may have been a local no-store placeholder. Use a
            // fresh request URL so Chromium asks the native cache for the body.
            const url = entry.source + (entry.source.includes('?') ? '&' : '?') +
                '_kamigura_art=' + encodeURIComponent(window.novelDocumentId);
            entry.retried = true;
            if (image.matches('img')) image.src = url;
            else image.setAttribute('href', url);
        }
    }
    function restoreImageStyle(image) {
        imageSizeProperties.forEach((property, index) => {
            const saved = imageStyles.get(image)[index];
            readerStyle(image, property, saved.value, saved.priority);
        });
    }
    function fixImageBox(image, width, height) {
        const style = getComputedStyle(image);
        const edges = names => names.reduce((sum, name) => sum + (parseFloat(style[name]) || 0), 0);
        if (style.boxSizing !== 'border-box') {
            width -= edges(['paddingLeft', 'paddingRight', 'borderLeftWidth', 'borderRightWidth']);
            height -= edges(['paddingTop', 'paddingBottom', 'borderTopWidth', 'borderBottomWidth']);
        }
        readerStyle(image, 'width', Math.max(0, width) + 'px');
        readerStyle(image, 'height', Math.max(0, height) + 'px');
    }
    function fitImage(image, availableWidth, availableHeight, dimensions) {
        restoreImageStyle(image);
        if (image.matches('img') && dimensions && !image.naturalWidth) {
            const [naturalWidth, naturalHeight] = dimensions;
            const style = image.computedStyleMap();
            const autoWidth = style.get('width').toString() === 'auto';
            const autoHeight = style.get('height').toString() === 'auto';
            const bounds = image.getBoundingClientRect();
            if (autoWidth) readerStyle(image, 'width', (autoHeight ? naturalWidth : bounds.height * naturalWidth / naturalHeight) + 'px');
            if (autoHeight) readerStyle(image, 'height', (autoWidth ? naturalHeight : bounds.width * naturalHeight / naturalWidth) + 'px');
        }
        const bounds = image.getBoundingClientRect();
        const scale = Math.min(1, availableWidth / bounds.width, availableHeight / bounds.height);
        readerStyle(image, 'max-width', availableWidth + 'px');
        readerStyle(image, 'max-height', availableHeight + 'px');
        readerStyle(image, 'object-fit', 'contain');
        readerStyle(image, 'break-inside', 'avoid');
        const box = image.viewBox?.baseVal;
        const ratio = dimensions ? dimensions[0] / dimensions[1] :
            box?.width > 0 && box?.height > 0 ? box.width / box.height : bounds.width / bounds.height;
        const clamped = (bounds.height >= availableHeight - .5 || bounds.width >= availableWidth - .5) &&
            Math.abs(bounds.width / bounds.height - ratio) > .005;
        if (scale < 1 || clamped) {
            const fittedWidth = Math.min(bounds.width, bounds.height * ratio, availableWidth, availableHeight * ratio);
            readerStyle(image, 'min-width', '0');
            readerStyle(image, 'min-height', '0');
            fixImageBox(image, fittedWidth, fittedWidth / ratio);
        }
    }
    function dimensionsForLayout(image, availableHeight) {
        return image.matches('img') ? rasterSize(image) ||
            (imagePending(image) ? [PendingImageAspect * availableHeight, availableHeight] : null) : null;
    }
    function settleImages() {
        if (!window.novelReader.ready) return;
        retryReadyImages();
        const { availableWidth, availableHeight } = imageViewport;
        let needsLayout = false;
        for (const [image, frame] of frozenImages) {
            const dimensions = rasterSize(image);
            const key = dimensions?.join('x');
            if (!key || key === frame.key) continue;
            frame.key = key;
            let width, height;
            if (image === sheetImage) {
                const scale = Math.min(availableWidth / dimensions[0], availableHeight / dimensions[1]);
                width = dimensions[0] * scale;
                height = dimensions[1] * scale;
                if (Math.min(...dimensions) < 320) needsLayout = true;
            } else {
                // Compare the authored, fitted box without paging or publishing a new location.
                const saved = imageSizeProperties.map(property => [image.style.getPropertyValue(property),
                    image.style.getPropertyPriority(property)]);
                fitImage(image, availableWidth, availableHeight, dimensions);
                ({ width, height } = image.getBoundingClientRect());
                imageSizeProperties.forEach((property, index) => readerStyle(image, property, ...saved[index]));
            }
            if (Math.abs(width - frame.width) > 2 || Math.abs(height - frame.height) > 2) needsLayout = true;
        }
        if (needsLayout) {
            layout(anchor(), false, lastLocation ?? location());
            imageRevision++;
        } else updateImageMarkers();
    }
    let illustrationOverrides = [];
    function restoreIllustrationLayout() {
        for (const { element, property, value, priority } of illustrationOverrides) readerStyle(element, property, value, priority);
        illustrationOverrides = [];
        sheetImage = null;
    }
    function fitIllustrationPage(availableWidth, availableHeight) {
        if (textNodes().some(node => !node.parentElement.closest('svg')) || root.querySelector('video,audio,canvas,iframe')) return;
        const images = Array.from(root.querySelectorAll('img,svg')).filter(image => !image.parentElement.closest('svg'));
        const spacers = images.filter(image => image.matches('img:not(.gaiji)') &&
            rasterSize(image)?.every(value => value === 1));
        const artwork = images.filter(image => !spacers.includes(image));
        if (artwork.length !== 1 || artwork[0].matches('img.gaiji')) return;
        const image = artwork[0], box = image.viewBox?.baseVal, bounds = image.getBoundingClientRect();
        const raster = image.matches('img') ? rasterSize(image) || (imagePending(image) ? [PendingImageAspect * 1000, 1000] : null) : null;
        const naturalWidth = raster?.[0] || box?.width || image.width?.baseVal?.value || 0;
        const naturalHeight = raster?.[1] || box?.height || image.height?.baseVal?.value || 0;
        // Only substantial artwork is a sheet; icons and authored miniature images keep their layout.
        if (Math.min(naturalWidth, naturalHeight) < 320 || Math.max(bounds.width, bounds.height) <= 128) return;
        sheetImage = image;
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
        imageViewport = { availableWidth, availableHeight };
        frozenImages.clear();
        for (const image of root.querySelectorAll('img,svg')) {
            if (image.matches('img.gaiji') || image.parentElement.closest('svg')) continue;
            if (!imageStyles.has(image)) imageStyles.set(image, imageSizeProperties.map(property => ({
                value: image.style.getPropertyValue(property), priority: image.style.getPropertyPriority(property)
            })));
            fitImage(image, availableWidth, availableHeight, dimensionsForLayout(image, availableHeight));
            if (image.matches('img') && imagePending(image)) {
                const bounds = image.getBoundingClientRect();
                fixImageBox(image, bounds.width, bounds.height);
                frozenImages.set(image, { key: rasterSize(image)?.join('x') });
            }
        }
        fitIllustrationPage(availableWidth, availableHeight);
        for (const [image, frame] of frozenImages) {
            const { width, height } = image.getBoundingClientRect();
            Object.assign(frame, { width, height });
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
        illustrations: illustrationRegions, prepareIllustrations,
        imageUpdate(sizes, revision) {
            resourceSizes = sizes;
            settleImages();
            return { pending: Array.from(waitingImages.values()).some(entry => !entry.done),
                revision: imageRevision, position: revision === imageRevision ? null : state() };
        },
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
    const embedded = [];
    for (const image of root.querySelectorAll('img,svg image')) {
        const source = image.currentSrc || image.src || image.href?.baseVal || '';
        if (!source) continue;
        const url = imageUrl(source), managed = Object.prototype.hasOwnProperty.call(resourceSizes, url);
        const decorate = !image.closest('.gaiji,.visually-hidden');
        if (image.complete && (!managed || image.naturalWidth)) continue;
        if (source.startsWith('data:') && image.matches('img')) embedded.push(image.decode().catch(() => {}));
        else if (!source.startsWith('data:') && (decorate || managed)) {
            if (managed) originalImageUrls.set(image, url);
            watchImage(image, url, managed, decorate);
        }
    }
    Promise.race([
        Promise.all([document.fonts.ready, ...embedded]),
        new Promise(resolve => setTimeout(resolve, 10000))
    ]).then(() => requestAnimationFrame(() => layout(window.novelInitialAnchor, window.novelOpenLast, window.novelInitialLocation)));
    window.addEventListener('resize', () => {
        if (!window.novelReader.ready) return;
        const xpath = anchor(), precise = lastLocation;
        clearTimeout(resizeTimer);
        resizeTimer = setTimeout(() => layout(xpath, false, precise), 100);
    });
})();
