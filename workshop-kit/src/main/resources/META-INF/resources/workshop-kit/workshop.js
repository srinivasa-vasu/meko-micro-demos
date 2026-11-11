/*
 * The workshop pages' shell, shared by the demos: the theme toggle, the guard against fast double clicks, the top bar of
 * use-case tabs grouped by domain, and the steps sidebar. Each page keeps its own model (which steps are done, open or
 * running) and hands this the result to draw; nothing here knows a use case.
 */
const WK = (() => {
  const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c]));

  /**
   * The light / dark toggle: the system's choice until the toggle is pressed, then the stored one (per page, by key).
   * {@code onChange} hears every change (an embedded page may follow it).
   */
  function theme(button, key, onChange) {
    try { const t = localStorage.getItem(key); if (t) document.documentElement.dataset.theme = t; } catch (e) { /* system */ }
    const current = () => document.documentElement.dataset.theme
      || (matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
    button.onclick = () => {
      const next = current() === 'dark' ? 'light' : 'dark';
      document.documentElement.dataset.theme = next;
      try { localStorage.setItem(key, next); } catch (e) { /* not kept */ }
      if (onChange) onChange(next);
    };
    return current;
  }

  /** A function that ignores a repeat call within {@code ms}: a double click moves one step, not two. */
  function guard(fn, ms = 400) {
    let last = 0;
    return (...args) => {
      const now = Date.now();
      if (now - last < ms) return;
      last = now;
      return fn(...args);
    };
  }

  /**
   * The top bar: tabs grouped by domain, each group's name on a vertical bar beside its tabs.
   * groups: [{label, tabs: [{key, icon (html), kicker, title, meta (html), selected, busy, tip}]}]
   */
  function tabBar(container, groups, onPick) {
    container.innerHTML = groups.map(g => `<div class="ucgroup ${g.tabs.some(t => t.selected) ? 'on' : ''}" role="presentation">` +
      `<span class="gl">${esc(g.label)}</span>` + g.tabs.map(t =>
        `<button class="uctab ${t.busy ? 'busy' : ''}" role="tab" data-uc="${esc(t.key)}" aria-selected="${!!t.selected}" title="${esc(t.tip || t.title)}">
          <span class="ic" aria-hidden="true">${t.icon || ''}</span>
          <span><span class="kick">${esc(t.kicker || '')}</span><span class="tt">${esc(t.title)}</span>
            ${t.meta ? `<span class="meta">${t.meta}</span>` : ''}</span></button>`).join('') + '</div>').join('');
    container.querySelectorAll('.uctab').forEach(b => b.onclick = () => onPick(b.dataset.uc));
  }

  /** Progress dots for a tab's meta line: one per step, lit when done. */
  const dots = (done, label) => `<span class="dots" aria-label="${esc(label || '')}">${done.map(d => `<i class="${d ? 'on' : ''}"></i>`).join('')}</span>`;

  /**
   * The steps sidebar. steps: [{key, title, sub, done, current, locked, running, skip, tip, mark}]; a locked step is
   * shown but not entered: {@code onPick} hears the click either way, so the page can say why.
   */
  function stepList(container, steps, onPick) {
    container.innerHTML = steps.map((s, n) => {
      const cls = [s.done ? 'done' : '', s.current ? 'current' : '', s.locked ? 'locked' : '', s.running ? 'running' : ''].join(' ');
      const mark = s.mark ?? (s.done ? '✓' : s.locked ? '🔒' : n + 1);
      return `<button class="stp ${cls}" data-k="${esc(s.key ?? n)}" ${s.locked ? 'aria-disabled="true"' : ''} ${s.current ? 'aria-current="step"' : ''} title="${esc(s.tip || '')}">
        <span class="dot">${esc(mark)}</span>
        <span><span class="t">${esc(s.title)}</span><br><span class="sub ${s.skip ? 'skip' : ''}">${esc(s.sub || '')}</span></span></button>`;
    }).join('');
    container.querySelectorAll('.stp').forEach(b => b.onclick = () => onPick(b.dataset.k));
  }

  /**
   * The datapack step, the same on every page: a new datapack or an existing one, the list loading by itself, and the
   * one in use said as such. The page keeps the state (mode, the name typed, the one selected) and acts on the choice.
   * o: {mode ('new' | 'existing'), only ('existing': the list alone), inUse ({id, name} or null), list ([{id, name,
   *     meta, tag: {label, cls, tip}}] or null while loading), choice (an id), name (the name to suggest), before (html
   *     above the choice), busy, onMode(mode), onChoice(id), onName(name), onRefresh(), onCreate(name), onUse({id, name}),
   *     onDelete({id, name}): optional, a Delete button on each listed datapack marked {@code deletable}}
   */
  function datapackPicker(container, o) {
    const mode = o.only || o.mode || (o.inUse ? 'existing' : 'new');
    const dis = o.busy ? 'disabled' : '';
    const picked = o.choice ?? o.inUse?.id;
    const inUse = o.inUse ? `<div class="callout ok">In use: <b>${esc(o.inUse.name)}</b>. Choose another below, or go on.</div>` : '';
    const seg = o.only ? '' : `<div class="seg" role="tablist" style="margin-top:14px">
        <button role="tab" aria-selected="${mode === 'new'}" data-dpm="new">Create a new datapack</button>
        <button role="tab" aria-selected="${mode === 'existing'}" data-dpm="existing">Use an existing one</button></div>`;
    const body = mode === 'new'
      ? `<label class="lbl" for="wkDpName">Name</label><input class="text" id="wkDpName" value="${esc(o.name || '')}" ${dis}>
         <div class="hint">Unique across Meko: lowercase letters, digits and hyphens.</div>
         <div class="row" style="margin-top:12px"><button class="btn primary act" id="wkDpCreate" ${dis}>Create and use</button></div>`
      : o.list == null ? '<p class="hint" style="margin-top:14px">Loading your datapacks…</p>'
      : `<div class="row" style="margin-top:14px;justify-content:space-between;max-width:760px"><span class="small muted">${o.list.length} datapack(s) you can use</span>
           <button class="btn sm ghost" id="wkDpRefresh" ${dis}>Refresh</button></div>
         ${o.list.length ? `<div class="list" style="max-width:760px">${o.list.map(d => `<label class="item"><input type="radio" name="wkDp" value="${esc(d.id)}" data-name="${esc(d.name)}" ${picked === d.id ? 'checked' : ''} ${dis}>
             <b>${esc(d.name)}</b>${o.inUse?.id === d.id ? ' <span class="pill ok">in use</span>' : ''}<span class="m">${esc(d.meta || '')}</span>
             ${d.tag ? `<span class="pill ${esc(d.tag.cls || '')}" title="${esc(d.tag.tip || '')}">${esc(d.tag.label)}</span>` : ''}
             ${o.onDelete && d.deletable ? `<button class="linkbtn" data-del="${esc(d.id)}" data-name="${esc(d.name)}" title="Delete it, and everything in it" ${dis}>Delete</button>` : ''}</label>`).join('')}</div>
           <div class="row" style="margin-top:12px"><button class="btn primary act" id="wkDpUse" ${dis}>Use this datapack</button></div>`
           : '<p class="hint">None yet: create a new one.</p>'}`;
    container.innerHTML = inUse + (o.before || '') + seg + body;
    const $ = id => container.querySelector('#' + id);
    container.querySelectorAll('[data-dpm]').forEach(b => b.onclick = () => o.onMode(b.dataset.dpm));
    container.querySelectorAll('input[name=wkDp]').forEach(i => i.onchange = () => o.onChoice(i.value));
    if ($('wkDpName')) $('wkDpName').oninput = e => o.onName && o.onName(e.target.value);
    if ($('wkDpRefresh')) $('wkDpRefresh').onclick = () => o.onRefresh();
    // Inside the item's label: the click deletes, and neither selects the datapack nor reaches the label.
    container.querySelectorAll('[data-del]').forEach(b => b.onclick = e => { e.preventDefault(); e.stopPropagation(); o.onDelete({id: b.dataset.del, name: b.dataset.name}); });
    if ($('wkDpCreate')) $('wkDpCreate').onclick = () => o.onCreate($('wkDpName').value.trim());
    if ($('wkDpUse')) $('wkDpUse').onclick = () => {
      const r = container.querySelector('input[name=wkDp]:checked');
      o.onUse(r ? {id: r.value, name: r.dataset.name} : null);
    };
    // The list loads by itself: asked for once the existing ones are shown.
    if (mode === 'existing' && o.list == null && o.onRefresh) o.onRefresh();
  }

  /**
   * The page's status line, toned by what it says, so what needs attention stands out: an error, something not there
   * or not ready yet (a warning), something done, or a plain note. Hidden when there is nothing to say.
   */
  function status(el, text, tone) {
    text = text || '';
    const t = tone || (/^(error|could not|failed)\b/i.test(text) ? 'bad'
      : /\b(not (in|there|ready|searchable|known|added|uploaded)|not .* yet|first[:.]|wait for|no datapack|no record|cannot|still )/i.test(text) ? 'warn'
      : /^(done|created|deleted|using|uploaded|added|recorded|picked up|record read|ended|ok)\b/i.test(text) ? 'ok' : 'info');
    el.textContent = text;
    el.className = 'status' + (text ? ' callout ' + t : '');
    el.hidden = !text;
  }

  return {esc, theme, guard, tabBar, dots, stepList, datapackPicker, status};
})();
