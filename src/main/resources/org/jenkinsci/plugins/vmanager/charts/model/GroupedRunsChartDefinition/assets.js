/*
 * UI helpers for the GroupedRunsChartDefinition config form:
 *
 *  1. "Group-by attribute" — a custom popover combobox driven by
 *     VmpCombo.installCombo (no window.fetch override). The textbox in
 *     config.jelly carries class .vmp-combo-groupby and a sibling hidden
 *     <input class="vmp-fill-meta" data-fill-url="..."> recording the
 *     descriptor's fillGroupByAttributeItems URL.
 *
 *  2. "Statuses" — a multi-select popover backed by a CSV-valued
 *     textbox. Unrelated to VmpCombo; rendered against a hidden button
 *     placed in config.jelly so that Jenkins' repeatable cloning produces
 *     exactly one button per row.
 */
(function () {
    if (window.__vmpGroupedRunsComboPatched) return;
    window.__vmpGroupedRunsComboPatched = true;

    function attachGroupByCombo(input) {
        if (input.__vmpGroupedAttached) return;
        var fillUrl = (window.VmpCombo && VmpCombo.findFillUrl)
                ? VmpCombo.findFillUrl(input) : null;
        if (!fillUrl) return;  // metadata input not rendered yet
        input.__vmpGroupedAttached = true;
        VmpCombo.installCombo(input, {
            fillUrl: fillUrl,
            className: 'vmp-combo-menu vmp-grouped-combo-menu',
            minWidth: 360,
            depends: [
                { name: 'serverUrl',     resolve: function () { return VmpCombo.findRelative(input, '../serverUrl');     } },
                { name: 'credentialsId', resolve: function () { return VmpCombo.findRelative(input, '../credentialsId'); } }
            ]
        });
    }

    if (typeof Behaviour !== 'undefined' && typeof Behaviour.specify === 'function') {
        Behaviour.specify('input.vmp-combo-groupby',
                          'vmp-combo-groupby', 0, attachGroupByCombo);
    } else {
        document.addEventListener('DOMContentLoaded', function () {
            document.querySelectorAll('input.vmp-combo-groupby').forEach(attachGroupByCombo);
        });
    }

    // ─────────────────────────────────────────────────────────────────────
    // Multi-select dropdown for the Statuses field.
    //
    // The underlying input is a textbox that stores a CSV (e.g.
    // "passed,failed"). For each such input we hide it and render a
    // pill-style button that opens a popover with one checkbox per status
    // (the full list is in a sibling hidden input ".vmp-status-filters-all"
    // serialized as Java's List.toString(): "[a, b, c]"). Selecting boxes
    // updates the CSV on the textbox in real time so form submission picks
    // up the user's choice.
    // ─────────────────────────────────────────────────────────────────────

    function parseAllStatuses(raw) {
        if (!raw) return [];
        // Java's AbstractCollection.toString() format: "[a, b, c]"
        var s = String(raw).trim();
        if (s.startsWith('[') && s.endsWith(']')) s = s.substring(1, s.length - 1);
        return s.split(',').map(function (x) { return x.trim(); })
                .filter(function (x) { return x.length > 0; });
    }

    function csvSelected(input) {
        return (input.value || '').split(',')
                .map(function (s) { return s.trim().toLowerCase(); })
                .filter(function (s) { return s.length > 0; });
    }

    function installStatusMultiSelect(btn, input, allEl) {
        var all = parseAllStatuses(allEl ? allEl.value : '');
        if (all.length === 0) {
            // Fallback so the widget still renders something usable.
            all = ['running','finished','other','waiting','stopped','passed','failed'];
        }

        // Hide the raw textbox; we drive its value from the popover.
        input.style.display = 'none';

        function updateLabel() {
            var sel = csvSelected(input);
            var label = sel.length === 0 ? 'All statuses'
                      : sel.length === all.length ? 'All statuses'
                      : sel.length <= 2 ? sel.join(', ')
                      : sel.length + ' selected';
            btn.innerHTML = '<span>' + escapeHtml(label) + '</span><span style="opacity:0.6;">\u25BE</span>';
        }
        function escapeHtml(s) {
            return String(s).replace(/[&<>]/g, function (c) {
                return c === '&' ? '&amp;' : c === '<' ? '&lt;' : '&gt;';
            });
        }
        updateLabel();

        var menu = null;
        function ensureMenu() {
            if (menu) return menu;
            menu = document.createElement('div');
            menu.className = 'vmp-status-menu';
            document.body.appendChild(menu);
            return menu;
        }
        function hideMenu() { if (menu) menu.style.display = 'none'; }
        function position() {
            if (!menu) return;
            var r = btn.getBoundingClientRect();
            menu.style.left = r.left + 'px';
            menu.style.top  = (r.bottom + 2) + 'px';
            menu.style.width = Math.max(r.width, 200) + 'px';
        }

        function render() {
            ensureMenu();
            var sel = csvSelected(input);
            menu.innerHTML = '';
            // Header row with select-all / clear shortcuts.
            var hdr = document.createElement('div');
            hdr.className = 'vmp-status-menu-header';
            var allBtn = document.createElement('a');
            allBtn.href = '#'; allBtn.textContent = 'Select all';
            allBtn.className = 'vmp-status-menu-link';
            allBtn.addEventListener('click', function (e) {
                e.preventDefault();
                input.value = all.join(',');
                input.dispatchEvent(new Event('change', { bubbles: true }));
                updateLabel(); render();
            });
            var noneBtn = document.createElement('a');
            noneBtn.href = '#'; noneBtn.textContent = 'Clear';
            noneBtn.className = 'vmp-status-menu-link';
            noneBtn.addEventListener('click', function (e) {
                e.preventDefault();
                input.value = '';
                input.dispatchEvent(new Event('change', { bubbles: true }));
                updateLabel(); render();
            });
            hdr.appendChild(allBtn); hdr.appendChild(noneBtn);
            menu.appendChild(hdr);

            all.forEach(function (status) {
                var row = document.createElement('label');
                row.className = 'vmp-status-menu-row';

                var cb = document.createElement('input');
                cb.type = 'checkbox';
                cb.checked = sel.indexOf(status) >= 0;
                cb.addEventListener('change', function () {
                    var current = csvSelected(input);
                    if (cb.checked) {
                        if (current.indexOf(status) < 0) current.push(status);
                    } else {
                        current = current.filter(function (s) { return s !== status; });
                    }
                    // Preserve canonical ALL_STATUSES order in the CSV.
                    var ordered = all.filter(function (s) { return current.indexOf(s) >= 0; });
                    input.value = ordered.join(',');
                    input.dispatchEvent(new Event('change', { bubbles: true }));
                    updateLabel();
                });

                var txt = document.createElement('span');
                txt.textContent = status;
                row.appendChild(cb); row.appendChild(txt);
                menu.appendChild(row);
            });

            position();
            menu.style.display = 'block';
        }

        btn.addEventListener('click', function (e) {
            e.preventDefault();
            if (menu && menu.style.display === 'block') hideMenu();
            else render();
        });
        document.addEventListener('mousedown', function (ev) {
            if (!menu) return;
            if (menu.style.display !== 'block') return;
            if (btn.contains(ev.target) || menu.contains(ev.target)) return;
            hideMenu();
        });
        window.addEventListener('scroll', function () {
            if (menu && menu.style.display === 'block') position();
        }, true);
        window.addEventListener('resize', function () {
            if (menu && menu.style.display === 'block') position();
        });
    }

    // The Statuses trigger button is rendered directly by config.jelly so
    // that Jenkins' repeatable cloning produces exactly one button per row
    // (avoiding the double-render caused by attaching to a hidden template
    // input via MutationObserver). We use Behaviour.specify so the handler
    // is automatically (re-)attached to every <button.vmp-status-filters-btn>
    // including freshly cloned ones after "Add Chart".
    function attachStatusFilterButton(btn) {
        if (btn.__vmpStatusAttached) return;
        var wrap = btn.closest('.jenkins-form-item, .setting-main, .repeated-chunk')
                || btn.parentElement;
        if (!wrap) return;
        var input = wrap.querySelector('input.vmp-status-filters');
        var allEl = wrap.querySelector('.vmp-status-filters-all');
        if (!input || !allEl) return;
        btn.__vmpStatusAttached = true;
        installStatusMultiSelect(btn, input, allEl);
    }

    if (typeof Behaviour !== 'undefined' && typeof Behaviour.specify === 'function') {
        Behaviour.specify('button.vmp-status-filters-btn',
                          'vmp-status-filters-btn', 0,
                          attachStatusFilterButton);
    } else {
        // Fallback if Behaviour isn't available for some reason.
        document.addEventListener('DOMContentLoaded', function () {
            document.querySelectorAll('button.vmp-status-filters-btn')
                    .forEach(attachStatusFilterButton);
        });
    }
})();