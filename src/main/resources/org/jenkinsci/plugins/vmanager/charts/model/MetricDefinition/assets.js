/*
 * UI helpers for the MetricDefinition config form:
 *
 *  1. "Attribute" — custom popover combobox driven by VmpCombo.installCombo
 *     (no window.fetch override). The textbox in config.jelly carries class
 *     .vmp-combo-attr; a sibling hidden <input class="vmp-fill-meta"
 *     data-fill-url="..."> records the descriptor's fillAttributeNameItems
 *     URL. Items are re-fetched whenever the row's entityType changes.
 *
 *  2. "Entity Level" select — toggles per-entity conditional fields and
 *     clears the Attribute value when the user switches entity (so the
 *     dropdown doesn't show stale options from the previous entity).
 *
 *  3. "Nickname" validation — duplicates within a chart are flagged; if
 *     the same attribute is picked more than once in a chart, every row
 *     must supply a nickname so series can be told apart.
 *
 *  4. Form-submit hook — blocks Save when any visible Attribute is empty
 *     or when any nickname problem is detected.
 */
(function () {
    if (window.__vmpComboPatched) return;
    window.__vmpComboPatched = true;

    function attachAll() {
        document.querySelectorAll('input.vmp-combo-attr').forEach(function (input) {
            if (input.__vmpAttached) return;
            var fillUrl = (window.VmpCombo && VmpCombo.findFillUrl)
                    ? VmpCombo.findFillUrl(input) : null;
            if (!fillUrl) return;
            input.__vmpAttached = true;
            VmpCombo.installCombo(input, {
                fillUrl: fillUrl,
                className: 'vmp-combo-menu vmp-attr-menu',
                depends: [
                    { name: 'entityType',    resolve: function () { return rowOf(input).querySelector('select[name$=".entityType"]'); } },
                    { name: 'serverUrl',     resolve: function () { return VmpCombo.findRelative(input, '../../serverUrl');     } },
                    { name: 'credentialsId', resolve: function () { return VmpCombo.findRelative(input, '../../credentialsId'); } }
                ]
            });
            // Clear inline error styling as soon as the user types something.
            input.addEventListener('input', function () {
                if ((input.value || '').trim()) clearFieldError(input);
            });
        });
        setupEntitySelects();
        setupNicknameValidation();
        hookFormSubmit();
    }

    function rowOf(input) {
        return input.closest('.repeated-chunk') || input.closest('.vmp-metric-row') || document;
    }

    function setupEntitySelects() {
        document.querySelectorAll('select[name$=".entityType"]').forEach(function (sel) {
            if (sel.__vmpEntitySetup) return;
            // Only apply when this select lives in the same row as our cond divs.
            var row = sel.closest('.repeated-chunk') || (function () {
                var p = sel;
                for (var n = 0; n < 10; n++) {
                    p = p.parentElement;
                    if (!p) return null;
                    if (p.querySelector && p.querySelector('.vmp-entity-cond')) return p;
                }
                return null;
            }());
            if (!row) return;
            sel.__vmpEntitySetup = true;

            var condDivs = Array.from(row.querySelectorAll('.vmp-entity-cond'));
            var attrInput = row.querySelector('input.vmp-combo-attr')
                         || row.querySelector('input[name$=".attributeName"]');

            function update(clearAttr) {
                var val = sel.value;
                condDivs.forEach(function (div) {
                    // data-vmp-entity may be a single value or a comma-separated
                    // list (e.g. "VPLAN_LEVEL,COVERAGE_LEVEL") for fields shared
                    // across entity types.
                    var allowed = (div.dataset.vmpEntity || '')
                        .split(',').map(function (s) { return s.trim(); });
                    div.style.display = allowed.indexOf(val) >= 0 ? '' : 'none';
                });
                if (clearAttr && attrInput) {
                    // Set silently — no 'input' event, so the dropdown won't open
                    // showing the previous entity's stale option list.
                    attrInput.value = '';
                    clearFieldError(attrInput);
                }
            }
            sel.addEventListener('change', function () { update(true); });
            update(false);
        });
    }

    var attachTimer;
    function scheduleAttach() {
        clearTimeout(attachTimer);
        attachTimer = setTimeout(attachAll, 50);
    }

    function clearFieldError(inp) {
        inp.style.outline = '';
        var wrap = inp.closest('.jenkins-form-item, .setting-main') || inp.parentElement;
        if (wrap) {
            var err = wrap.querySelector('.vmp-attr-err');
            if (err) err.remove();
        }
    }

    function showFieldError(inp, msg) {
        inp.style.outline = '2px solid var(--error-color, #c33)';
        var wrap = inp.closest('.jenkins-form-item, .setting-main') || inp.parentElement;
        if (!wrap) return;
        var err = wrap.querySelector('.vmp-attr-err');
        if (!err) {
            err = document.createElement('div');
            err.className = 'vmp-attr-err';
            err.style.color = 'var(--error-color, #c33)';
            err.style.fontSize = '0.8rem';
            err.style.marginTop = '2px';
            wrap.appendChild(err);
        }
        err.textContent = msg || 'Attribute name is required.';
    }

    // ── Nickname uniqueness ────────────────────────────────────────────
    // The nickname distinguishes between two metrics that pick the same
    // attribute. It must be unique within a single chart. We compute the
    // chart scope by walking up to the outermost .repeated-chunk ancestor
    // (the metric chunk's grand-ancestor of the same class) and group
    // nickname inputs by that scope.
    function chartScopeOf(input) {
        var chunks = [];
        var p = input.parentElement;
        while (p) {
            if (p.classList && p.classList.contains('repeated-chunk')) chunks.push(p);
            p = p.parentElement;
        }
        // Outermost wins; if only one (no chart-level repeatable wrapper) use it.
        return chunks.length ? chunks[chunks.length - 1] : document.body;
    }

    function metricChunkOf(input) {
        // The metric's own row is the innermost .repeated-chunk ancestor.
        return input.closest('.repeated-chunk');
    }

    function attrValueOf(metricChunk) {
        if (!metricChunk) return '';
        var a = metricChunk.querySelector('input.vmp-combo-attr[name$=".attributeName"]')
             || metricChunk.querySelector('input[name$=".attributeName"]');
        return a ? (a.value || '').trim() : '';
    }

    function validateNicknamesIn(scope) {
        var inputs = Array.from(scope.querySelectorAll('input.vmp-nickname'));

        // Pass 1: clear any previous errors so we can recompute from scratch.
        inputs.forEach(clearFieldError);

        // Pass 2: flag duplicate nicknames within this chart.
        var byVal = {};
        inputs.forEach(function (inp) {
            var v = (inp.value || '').trim().toLowerCase();
            if (!v) return;
            (byVal[v] = byVal[v] || []).push(inp);
        });
        Object.keys(byVal).forEach(function (v) {
            var group = byVal[v];
            if (group.length > 1) {
                group.forEach(function (inp) {
                    showFieldError(inp, 'Nickname must be unique within this chart.');
                });
            }
        });

        // Pass 3: when the same attribute is picked more than once in this
        // chart, every occurrence MUST have a nickname (so the chart series
        // can be told apart). Flag the rows that are missing one.
        var byAttr = {};
        inputs.forEach(function (inp) {
            var attr = attrValueOf(metricChunkOf(inp));
            if (!attr) return;
            var key = attr.toLowerCase();
            (byAttr[key] = byAttr[key] || []).push(inp);
        });
        Object.keys(byAttr).forEach(function (k) {
            var group = byAttr[k];
            if (group.length < 2) return;
            group.forEach(function (inp) {
                if (!(inp.value || '').trim()) {
                    showFieldError(inp,
                        'Nickname is required: this attribute is used more than once in this chart.');
                }
            });
        });
    }

    function collectAllNicknameProblems() {
        var bad = [];
        var seenScopes = [];
        document.querySelectorAll('input.vmp-nickname').forEach(function (inp) {
            if (inp.offsetParent === null) return;
            var scope = chartScopeOf(inp);
            if (seenScopes.indexOf(scope) >= 0) return;
            seenScopes.push(scope);
            var inputs = Array.from(scope.querySelectorAll('input.vmp-nickname'));

            // Duplicate nicknames
            var byVal = {};
            inputs.forEach(function (i2) {
                var v = (i2.value || '').trim().toLowerCase();
                if (!v) return;
                (byVal[v] = byVal[v] || []).push(i2);
            });
            Object.keys(byVal).forEach(function (v) {
                if (byVal[v].length > 1) byVal[v].forEach(function (x) { bad.push(x); });
            });

            // Duplicate attributes with missing nicknames
            var byAttr = {};
            inputs.forEach(function (i2) {
                var attr = attrValueOf(metricChunkOf(i2));
                if (!attr) return;
                var key = attr.toLowerCase();
                (byAttr[key] = byAttr[key] || []).push(i2);
            });
            Object.keys(byAttr).forEach(function (k) {
                var grp = byAttr[k];
                if (grp.length < 2) return;
                grp.forEach(function (i2) {
                    if (!(i2.value || '').trim()) bad.push(i2);
                });
            });
        });
        return bad;
    }

    function setupNicknameValidation() {
        document.querySelectorAll('input.vmp-nickname').forEach(function (inp) {
            if (inp.__vmpNickHooked) return;
            inp.__vmpNickHooked = true;
            var handler = function () { validateNicknamesIn(chartScopeOf(inp)); };
            inp.addEventListener('input', handler);
            inp.addEventListener('blur',  handler);
        });
        // Also re-validate every chart scope when an attribute combobox
        // value changes — picking the same attribute as a sibling row must
        // immediately demand a nickname.
        document.querySelectorAll('input.vmp-combo-attr[name$=".attributeName"]').forEach(function (a) {
            if (a.__vmpAttrNickHooked) return;
            a.__vmpAttrNickHooked = true;
            var handler = function () {
                var scope = chartScopeOf(a);
                if (scope) validateNicknamesIn(scope);
            };
            a.addEventListener('input',  handler);
            a.addEventListener('change', handler);
            a.addEventListener('blur',   handler);
        });
    }

    function hookFormSubmit() {
        var form = document.querySelector('form[name="config"]')
                || document.querySelector('#main-panel form')
                || document.querySelector('form');
        if (!form || form.__vmpSubmitHooked) return;
        form.__vmpSubmitHooked = true;
        form.addEventListener('submit', function (ev) {
            var bad = Array.from(
                document.querySelectorAll('input.vmp-combo-attr')
            ).filter(function (inp) {
                return /attributeName$/.test(inp.name || '')
                    && inp.offsetParent !== null
                    && !(inp.value || '').trim();
            });
            // Re-validate nicknames across all charts; mark dups + missing.
            var nickProblems = collectAllNicknameProblems();
            if (bad.length === 0 && nickProblems.length === 0) return;
            ev.preventDefault();
            ev.stopImmediatePropagation();
            bad.forEach(showFieldError);
            nickProblems.forEach(function (inp) { validateNicknamesIn(chartScopeOf(inp)); });
            var first = bad[0] || nickProblems[0];
            if (first) {
                first.scrollIntoView({ block: 'center', behavior: 'smooth' });
                setTimeout(function () { first.focus(); }, 100);
            }
        }, true); // capture phase — runs before Jenkins' own submit handler
    }

    // Watch for dynamically added repeatable rows.
    new MutationObserver(scheduleAttach).observe(document.body, { childList: true, subtree: true });
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', scheduleAttach);
    } else {
        scheduleAttach();
    }
}());
        
