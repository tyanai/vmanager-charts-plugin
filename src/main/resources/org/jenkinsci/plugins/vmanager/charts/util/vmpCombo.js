/*
 * Shared combobox helpers for the vmanager-charts plugin.
 *
 * Previous revisions of ChartDefinition / MetricDefinition /
 * GroupedRunsChartDefinition each overrode `window.fetch` to capture the
 * JSON response of Jenkins' combobox2 fillUrl call. That is brittle
 * (other plugins on the same page may also wrap `window.fetch`) and the
 * three implementations duplicated a lot of code (paramFromBody, the
 * popover dropdown, the relative-path resolver, ...).
 *
 * This module exposes a small `window.VmpCombo` namespace that the three
 * fields can use to drive a plain <f:textbox> as a combobox without
 * touching any global APIs:
 *
 *   VmpCombo.installCombo(input, {
 *       fillUrl:    '<descriptor URL>/fillFooItems',
 *       depends:    [{ name: 'entityType', resolve: function () { ... } }, ...],
 *       shouldOpen: function (input) { return ...; },   // optional
 *       className:  'vmp-combo-menu vmp-foo-menu',     // optional
 *       minWidth:   320                                // optional
 *   });
 *
 * Items are cached per dependency-value combination, and the popover
 * re-uses a single <div> regardless of how often the user toggles focus.
 *
 * The field's @QueryParameter fill method is invoked via POST (because
 * the Java side annotates these endpoints with @POST for CSRF) with
 * dependency values sent as application/x-www-form-urlencoded in the
 * request body and the Jenkins crumb sent as a header.
 */
(function () {
    if (window.VmpCombo) return;

    /**
     * Resolve a Stapler-style relative path (e.g. "../serverUrl",
     * "../../credentialsId") starting from `fromInput`. Walks up
     * .repeated-chunk / .jenkins-form-item / form ancestors once per
     * "../" segment, then looks for an input/select whose name ends with
     * the leaf segment.
     */
    function findRelative(fromInput, relPath) {
        var parts = String(relPath || '').split('/');
        var name  = parts.pop();
        var node  = fromInput;
        for (var i = 0; i < parts.length; i++) {
            if (parts[i] === '..') {
                node = node && node.closest('.repeated-chunk, .jenkins-form-item, form');
                if (node) node = node.parentElement;
            }
        }
        if (!node) node = document;
        return node.querySelector('[name$="' + name + '"]')
            || document.querySelector('[name$="' + name + '"]');
    }

    function buildQuery(params) {
        var parts = [];
        Object.keys(params).forEach(function (k) {
            parts.push(encodeURIComponent(k) + '=' + encodeURIComponent(params[k] == null ? '' : params[k]));
        });
        return parts.join('&');
    }

    /**
     * Find the Jenkins CSRF crumb header name + value. Jenkins exposes
     * these on the <head> element as data-crumb-header / data-crumb-value
     * (and also on document.body in some core versions).
     */
    function crumbHeaders() {
        var h = {};
        var el = document.head || document.body;
        var name  = el && (el.getAttribute('data-crumb-header'));
        var value = el && (el.getAttribute('data-crumb-value'));
        if (!name || !value) {
            var b = document.body;
            name  = name  || (b && b.getAttribute('data-crumb-header'));
            value = value || (b && b.getAttribute('data-crumb-value'));
        }
        if (name && value) h[name] = value;
        return h;
    }

    /**
     * Fetch a Jenkins fill endpoint as JSON and return a Promise<string[]>.
     * Accepts both {values: [...]} and a bare array, and accepts either
     * plain strings or {name: '...'} objects per item.
     *
     * The descriptor methods are annotated with @POST for CSRF protection,
     * so we send POST with parameters in the form body and include the
     * Jenkins crumb header. Stapler reads @QueryParameter from either the
     * query string or the form-encoded body.
     */
    function fetchItems(url, params) {
        var body = buildQuery(params || {});
        var headers = crumbHeaders();
        headers['Accept']       = 'application/json';
        headers['Content-Type'] = 'application/x-www-form-urlencoded; charset=UTF-8';
        return fetch(url, {
            method: 'POST',
            credentials: 'same-origin',
            headers: headers,
            body: body
        }).then(function (r) {
            if (!r.ok) throw new Error('HTTP ' + r.status);
            return r.json();
        }).then(function (j) {
            var arr = Array.isArray(j) ? j
                    : (j && Array.isArray(j.values)) ? j.values
                    : [];
            return arr.map(function (it) {
                return (typeof it === 'string') ? it
                     : (it && it.name) ? it.name
                     : String(it);
            });
        });
    }

    /**
     * Attach a custom popover dropdown to a textbox. The popover opens
     * on focus / click, filters case-insensitively on every keystroke,
     * and closes on Escape, on blur, or when an item is picked.
     * The dependency values are read live each time the popover opens,
     * so changing a sibling select re-fetches a fresh list.
     */
    function installCombo(input, opts) {
        if (!input || input.__vmpComboAttached) return;
        input.__vmpComboAttached = true;

        var fillUrl    = opts.fillUrl;
        var depends    = opts.depends || [];
        var shouldOpen = opts.shouldOpen || function () { return true; };
        var minWidth   = opts.minWidth || 320;
        var className  = opts.className || 'vmp-combo-menu';

        var cache    = Object.create(null);
        var inflight = null;     // { key, p }
        var menu     = null;

        function depsValues() {
            var v = {};
            depends.forEach(function (d) {
                var el = (typeof d.resolve === 'function') ? d.resolve() : null;
                v[d.name] = el ? (el.value || '') : '';
            });
            return v;
        }
        function depsKey(v) {
            return depends.map(function (d) {
                return d.name + '=' + (v[d.name] || '');
            }).join('|');
        }

        function ensureMenu() {
            if (menu) return menu;
            menu = document.createElement('div');
            menu.className = className;
            document.body.appendChild(menu);
            return menu;
        }
        function hide() { if (menu) menu.style.display = 'none'; }
        function position() {
            if (!menu) return;
            var r = input.getBoundingClientRect();
            menu.style.left  = r.left + 'px';
            menu.style.top   = (r.bottom + 2) + 'px';
            menu.style.width = Math.max(r.width, minWidth) + 'px';
        }

        function renderEmpty(msg) {
            ensureMenu();
            menu.innerHTML = '';
            var d = document.createElement('div');
            d.className = 'vmp-combo-empty';
            d.textContent = msg;
            menu.appendChild(d);
            position();
            menu.style.display = 'block';
        }

        function renderList(items) {
            ensureMenu();
            menu.innerHTML = '';
            var term = (input.value || '').trim().toLowerCase();
            var matches = term
                ? items.filter(function (s) { return s.toLowerCase().indexOf(term) >= 0; })
                : items.slice();
            if (matches.length === 0) {
                var d = document.createElement('div');
                d.className = 'vmp-combo-empty';
                d.textContent = items.length === 0 ? 'No values available' : 'No matches';
                menu.appendChild(d);
            } else {
                matches.slice(0, 500).forEach(function (s) {
                    var it = document.createElement('div');
                    it.className = 'vmp-combo-item';
                    it.textContent = s;
                    it.addEventListener('mousedown', function (ev) {
                        ev.preventDefault();
                        input.value = s;
                        input.dispatchEvent(new Event('input',  { bubbles: true }));
                        input.dispatchEvent(new Event('change', { bubbles: true }));
                        hide();
                    });
                    menu.appendChild(it);
                });
            }
            position();
            menu.style.display = 'block';
        }

        function open() {
            if (!shouldOpen(input)) { hide(); return; }
            var v   = depsValues();
            var key = depsKey(v);
            if (cache[key]) { renderList(cache[key]); return; }
            renderEmpty('Loading\u2026');
            if (inflight && inflight.key === key) {
                inflight.p.then(function (list) {
                    if (key === depsKey(depsValues())) renderList(list);
                });
                return;
            }
            var p = fetchItems(fillUrl, v).then(function (list) {
                cache[key] = list;
                inflight = null;
                if (key === depsKey(depsValues())) renderList(list);
                return list;
            }, function () {
                inflight = null;
                if (key === depsKey(depsValues())) renderEmpty('Failed to load');
                return [];
            });
            inflight = { key: key, p: p };
        }

        function refilter() {
            // Re-render using last-known list without re-hitting the server on every keystroke.
            var v   = depsValues();
            var key = depsKey(v);
            if (cache[key]) renderList(cache[key]);
            else open();
        }

        input.addEventListener('focus', open);
        input.addEventListener('click', open);
        input.addEventListener('input', refilter);
        input.addEventListener('blur',  function () { setTimeout(hide, 180); });
        input.addEventListener('keydown', function (ev) {
            if (ev.key === 'Escape') hide();
        });
        window.addEventListener('scroll', function () {
            if (menu && menu.style.display === 'block') position();
        }, true);
        window.addEventListener('resize', function () {
            if (menu && menu.style.display === 'block') position();
        });
    }

    /**
     * Convenience helper: look up the descriptor fill URL recorded by
     * jelly as a sibling <input type="hidden" class="vmp-fill-meta"
     * data-fill-url="..."/>. Returns null when not found.
     */
    function findFillUrl(input) {
        var wrap = input.closest('.jenkins-form-item, .setting-main') || input.parentElement;
        if (!wrap) return null;
        var meta = wrap.querySelector('input.vmp-fill-meta[data-fill-url]');
        return meta ? meta.getAttribute('data-fill-url') : null;
    }

    window.VmpCombo = {
        installCombo: installCombo,
        findRelative: findRelative,
        fetchItems:   fetchItems,
        findFillUrl:  findFillUrl
    };
})();
