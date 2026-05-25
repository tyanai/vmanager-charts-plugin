/*
 * UI helpers for the ChartDefinition config form:
 *
 *  1. The "vPlan" textbox becomes a custom popover combobox (driven by
 *     VmpCombo.installCombo) — but ONLY when "vPlan Type" is set to DB.
 *     In FILE / unset modes the input behaves like a plain textbox so the
 *     user can type a path freely.
 *
 *  2. When the user switches vPlan Type to DB, the previously typed
 *     value is cleared so the freshly fetched DB list is what they see.
 *
 *  3. A separate IIFE further down hooks Save / Apply to block form
 *     submission when required fields on any chart row are empty.
 */
(function () {
    if (window.__vmpVPlanPatched2) return;
    window.__vmpVPlanPatched2 = true;

    function rowOf(input) {
        return input.closest('.repeated-chunk') || input.closest('.vmp-chart-row') || document;
    }

    function isDbMode(input) {
        var sel = rowOf(input).querySelector('select[name$="vPlanType"]');
        return sel && sel.value === 'DB';
    }

    function attachVPlanCombo(input) {
        if (input.__vmpVPlanAttached) return;
        var fillUrl = (window.VmpCombo && VmpCombo.findFillUrl)
                ? VmpCombo.findFillUrl(input) : null;
        if (!fillUrl) return;
        input.__vmpVPlanAttached = true;
        VmpCombo.installCombo(input, {
            fillUrl: fillUrl,
            className: 'vmp-combo-menu vmp-vplan-menu',
            shouldOpen: isDbMode,
            depends: [
                { name: 'vPlanType',     resolve: function () { return rowOf(input).querySelector('select[name$="vPlanType"]'); } },
                { name: 'serverUrl',     resolve: function () { return VmpCombo.findRelative(input, '../serverUrl');     } },
                { name: 'credentialsId', resolve: function () { return VmpCombo.findRelative(input, '../credentialsId'); } }
            ]
        });
    }

    function attachVPlanTypeSelect(sel) {
        if (sel.__vmpVPlanTypeSetup) return;
        sel.__vmpVPlanTypeSetup = true;
        var chunk = sel.closest('.repeated-chunk') || document;
        var input = chunk.querySelector('input.vmp-combo-vplan, input[name$="vPlanPath"]');
        sel.addEventListener('change', function () {
            if (sel.value === 'DB' && input && input.value) {
                // Clear so the user sees the freshly fetched DB list.
                input.value = '';
                input.dispatchEvent(new Event('input',  { bubbles: true }));
                input.dispatchEvent(new Event('change', { bubbles: true }));
            }
        });
    }

    if (typeof Behaviour !== 'undefined' && typeof Behaviour.specify === 'function') {
        Behaviour.specify('input.vmp-combo-vplan',
                          'vmp-combo-vplan', 0, attachVPlanCombo);
        Behaviour.specify('select[name$="vPlanType"]',
                          'vmp-vplan-type', 0, attachVPlanTypeSelect);
    } else {
        document.addEventListener('DOMContentLoaded', function () {
            document.querySelectorAll('input.vmp-combo-vplan').forEach(attachVPlanCombo);
            document.querySelectorAll('select[name$="vPlanType"]').forEach(attachVPlanTypeSelect);
        });
    }
})();

// Block save when any Chart Title is empty, or any Max Builds is missing/invalid.
(function () {
    if (window.__vmpChartTitleSubmitHooked) return;
    window.__vmpChartTitleSubmitHooked = true;

    function clearErr(input, cls) {
        input.style.outline = '';
        var wrap = input.closest('.jenkins-form-item, .setting-main') || input.parentElement;
        if (wrap) {
            var err = wrap.querySelector('.' + cls);
            if (err) err.remove();
        }
    }

    function showErr(input, cls, msg) {
        input.style.outline = '2px solid var(--error-color, #c33)';
        var wrap = input.closest('.jenkins-form-item, .setting-main') || input.parentElement;
        if (!wrap) return;
        // Avoid duplicating Jenkins' own descriptor error (rendered by
        // doCheckTitle / doCheckGroupByAttribute / doCheckMaxBuilds): if
        // Jenkins already shows a validation message for this field, don't
        // append our own — it would look like the same error printed twice.
        if (wrap.querySelector('div.error, .jenkins-form-error, .error')) return;
        if (wrap.querySelector('.' + cls)) return;
        var e = document.createElement('div');
        e.className = cls;
        e.textContent = msg;
        wrap.appendChild(e);
    }

    function isValidMaxBuilds(v) {
        if (v == null) return false;
        var s = String(v).trim();
        if (s === '') return false;
        if (!/^\d+$/.test(s)) return false;
        var n = parseInt(s, 10);
        return n >= 0;
    }

    document.addEventListener('input', function (ev) {
        var t = ev.target;
        if (!t || (t.tagName !== 'INPUT' && t.tagName !== 'SELECT')) return;
        var name = t.name || '';
        if (/(^|\.)title$/.test(name)
                && t.closest('.repeated-chunk') && (t.value || '').trim()) {
            clearErr(t, 'vmp-chart-title-err');
        }
        if (/(^|\.)maxBuilds$/.test(name)
                && t.closest('.repeated-chunk') && isValidMaxBuilds(t.value)) {
            clearErr(t, 'vmp-chart-maxbuilds-err');
        }
        if (/(^|\.)groupByAttribute$/.test(name)
                && t.closest('.repeated-chunk') && (t.value || '').trim()) {
            clearErr(t, 'vmp-chart-groupby-err');
        }
    }, true);

    function findForm() {
        return document.querySelector('form[name="config"]')
            || document.querySelector('#main-panel form')
            || document.querySelector('form');
    }

    // Shared validator: returns the list of invalid inputs (with inline
    // errors shown as a side effect). Used by both the form-submit hook
    // (Save) and the Apply-button click hook (Apply submits via XHR and
    // never fires the form's submit event).
    //
    // Handles two kinds of chart rows:
    //   - Legacy ChartDefinition rows: identified by a vPlanType select.
    //     Required: title, maxBuilds.
    //   - GroupedRunsChartDefinition rows: identified by a groupByAttribute
    //     combobox input. Required: title, groupByAttribute.
    function validateChartRows() {
        var bad = [];
        Array.from(document.querySelectorAll('.repeated-chunk')).forEach(function (chunk) {
            var isLegacy  = !!chunk.querySelector('select[name$="vPlanType"]');
            var groupByInp = chunk.querySelector('input[name$=".groupByAttribute"]');
            var isGrouped = !!groupByInp;
            if (!isLegacy && !isGrouped) return;

            var titleInp = chunk.querySelector('input[type="text"][name$=".title"]');
            if (titleInp && titleInp.offsetParent !== null
                    && !(titleInp.value || '').trim()) {
                showErr(titleInp, 'vmp-chart-title-err', 'Chart title is required.');
                bad.push(titleInp);
            }
            if (isLegacy) {
                // Max Builds may be rendered as <input type="text"> (legacy) or
                // <input type="number"> (f:number) — match both.
                var mbInp = chunk.querySelector('input[name$=".maxBuilds"]');
                if (mbInp && mbInp.offsetParent !== null
                        && !isValidMaxBuilds(mbInp.value)) {
                    showErr(mbInp, 'vmp-chart-maxbuilds-err',
                            'Max Builds is required (0 for unlimited).');
                    bad.push(mbInp);
                }
            }
            if (isGrouped) {
                if (groupByInp.offsetParent !== null
                        && !(groupByInp.value || '').trim()) {
                    showErr(groupByInp, 'vmp-chart-groupby-err',
                            'Group-by attribute is required.');
                    bad.push(groupByInp);
                }
            }
        });
        return bad;
    }

    function focusFirstInvalid(bad) {
        if (bad.length === 0) return;
        bad[0].scrollIntoView({ block: 'center', behavior: 'smooth' });
        setTimeout(function () { bad[0].focus(); }, 100);
    }

    function hookForm() {
        var form = findForm();
        if (!form || form.__vmpChartTitleHooked) return;
        form.__vmpChartTitleHooked = true;
        form.addEventListener('submit', function (ev) {
            // A ChartDefinition row is identified by a .repeated-chunk that
            // contains a vPlanType select. Within each such visible row, both
            // .title and .maxBuilds are required.
            var bad = validateChartRows();
            if (bad.length === 0) return;
            ev.preventDefault();
            ev.stopImmediatePropagation();
            focusFirstInvalid(bad);
        }, true); // capture phase
    }

    // Jenkins' Apply button posts the form via XHR without firing the form's
    // own 'submit' event, so the hookForm() listener above never sees it.
    // Intercept the click in the capture phase BEFORE Jenkins' own handler
    // runs, and abort if validation fails.
    document.addEventListener('click', function (ev) {
        var t = ev.target;
        if (!t) return;
        var btn = t.closest && t.closest('button[name="Apply"], input[name="Apply"]');
        if (!btn) return;
        var bad = validateChartRows();
        if (bad.length === 0) return;
        ev.preventDefault();
        ev.stopImmediatePropagation();
        focusFirstInvalid(bad);
    }, true); // capture phase

    new MutationObserver(hookForm).observe(document.body, { childList: true, subtree: true });
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', hookForm);
    } else {
        hookForm();
    }
})();
        
