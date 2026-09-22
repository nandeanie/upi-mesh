/* UPI Mesh dashboard — everything shown here comes from the Spring Boot API.
 * No accounts, devices, balances or results are hardcoded in this file. */
(() => {
  'use strict';

  // ───────────────────────── helpers ─────────────────────────
  const $  = (s, r = document) => r.querySelector(s);
  const $$ = (s, r = document) => Array.from(r.querySelectorAll(s));
  const sleep = ms => new Promise(r => setTimeout(r, ms));
  const esc = v => String(v ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#039;' }[c]));

  const inr2 = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', minimumFractionDigits: 2, maximumFractionDigits: 2 });
  const inr0 = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', minimumFractionDigits: 0, maximumFractionDigits: 2 });
  const money  = n => inr2.format(Number(n) || 0);
  const moneyS = n => inr0.format(Number(n) || 0);
  const shortHash = h => (h && h.length > 16) ? h.slice(0, 8) + '…' + h.slice(-6) : (h || '—');
  const timeOf = iso => iso ? new Date(iso).toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit', second: '2-digit' }) : '—';
  const nameOf = vpa => { const a = S.accounts.find(x => x.vpa === vpa); return a ? a.holderName : String(vpa).split('@')[0]; };

  const store = {
    get(k) { try { return sessionStorage.getItem(k) || ''; } catch { return ''; } },
    set(k, v) { try { sessionStorage.setItem(k, v); } catch { /* private mode */ } },
    del(k) { try { sessionStorage.removeItem(k); } catch { /* ignore */ } }
  };

  // ───────────────────────── state ─────────────────────────
  const S = {
    accounts: [], devices: [], audit: null, txs: [], events: [],
    cacheSize: 0, health: null, latency: 0, online: null,
    apiKey: store.get('upi.apiKey'),
    busy: false,
    flow: { flushed: false },
    inspector: null, lastFlush: null, serverKey: '',
    prevCounts: {}, prevBalances: {}, deltas: {}, lastEventId: 0, firstLoad: true,
    ledgerFilter: 'ALL', ledgerQuery: ''
  };

  // ───────────────────────── API layer ─────────────────────────
  class ApiError extends Error { constructor(msg, status) { super(msg); this.status = status; } }

  async function api(path, { method = 'GET', body, auth = false } = {}) {
    const headers = { 'Content-Type': 'application/json' };
    if (auth) {
      if (!S.apiKey) {
        const k = await askKey('This action needs the bridge API key.');
        if (!k) throw new ApiError('API key required', 401);
      }
      headers.Authorization = 'Bearer ' + S.apiKey;
    }
    let res;
    try {
      res = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
    } catch (e) {
      throw new ApiError('Cannot reach the backend', 0);
    }
    let data = null;
    try { data = await res.json(); } catch { /* empty body */ }
    if (res.status === 401) {
      S.apiKey = ''; store.del('upi.apiKey');
      throw new ApiError('Invalid API key — set it with the “API key” button', 401);
    }
    if (!res.ok) throw new ApiError((data && (data.error || data.hint)) || res.statusText || 'Request failed', res.status);
    return data;
  }

  // ───────────────────────── toasts / modals ─────────────────────────
  function toast(msg, kind = '') {
    const el = document.createElement('div');
    el.className = 'toast ' + kind;
    el.textContent = msg;
    $('#toasts').appendChild(el);
    setTimeout(() => el.remove(), kind === 'error' ? 6000 : 3500);
  }

  const openModal  = id => $('#' + id).classList.add('open');
  const closeModal = id => $('#' + id).classList.remove('open');

  let keyResolver = null;
  function askKey(message) {
    $('#keyMsg').textContent = message || 'Kept only for this browser tab.';
    $('#apiKeyInput').value = '';
    openModal('keyModal');
    setTimeout(() => $('#apiKeyInput').focus(), 30);
    return new Promise(resolve => { keyResolver = resolve; });
  }
  function finishKey(save) {
    const v = $('#apiKeyInput').value.trim();
    closeModal('keyModal');
    if (save && v) { S.apiKey = v; store.set('upi.apiKey', v); toast('API key saved for this tab', 'ok'); }
    if (keyResolver) { keyResolver(save && v ? v : ''); keyResolver = null; }
  }

  // ───────────────────────── loading ─────────────────────────
  let chain = Promise.resolve(), pending = 0;
  function refresh() {
    pending++;
    chain = chain.then(doRefresh).finally(() => { pending--; });
    return chain;
  }

  async function doRefresh() {
    const t0 = performance.now();
    try {
      const [health, accounts, mesh, audit, events] = await Promise.all([
        api('/api/health'), api('/api/accounts'), api('/api/mesh/state'), api('/api/audit'), api('/api/events?limit=40')
      ]);
      S.latency = Math.round(performance.now() - t0);
      S.health = health; S.accounts = accounts; S.devices = mesh.devices; S.cacheSize = mesh.idempotencyCacheSize;
      S.audit = audit.summary; S.txs = audit.recentTransactions; S.events = events;
      setOnline(true);
      renderAll();
    } catch (e) {
      setOnline(false);
    }
  }

  function setOnline(ok) {
    S.online = ok;
    $('#sideStatus').classList.toggle('offline', !ok);
    $('#systemPill').classList.toggle('offline', !ok);
    $('#offlineBanner').classList.toggle('show', !ok);
    $$('.dot.pulse').forEach(d => d.classList.toggle('off', !ok));
    $('#systemText').textContent = ok ? `System online · ${S.latency} ms` : 'Backend offline';
    $('#sideStatusText').textContent = ok ? 'Backend connected' : 'Backend offline';
    $('#sideStatusSub').textContent = ok ? `${S.health.service} · Spring Boot` : 'Retrying…';
  }

  // ───────────────────────── rendering ─────────────────────────
  function renderAll() {
    renderStats(); renderAccounts(); renderFormOptions(); renderTopology(); renderDevices();
    renderStepper(); renderLedger(); renderEvents(); renderAudit(); renderFlush(); renderInspector();
    S.firstLoad = false;
  }

  function renderStats() {
    const a = S.audit;
    $('#stSettled').textContent = a.totalSettled;
    $('#stDupes').textContent   = a.duplicatesDropped;
    $('#stVolume').textContent  = moneyS(a.volumeSettled);
    $('#stCache').textContent   = S.cacheSize;
  }

  function renderAudit() {
    const a = S.audit;
    $('#auSettled').textContent = a.totalSettled;
    $('#auRejected').textContent = a.totalRejected;
    $('#auInvalid').textContent = a.totalInvalid;
    $('#auCache').textContent = a.idempotencyCacheSize;
  }


  function renderAccounts() {
    const box = $('#accountCards');
    if (!S.accounts.length) { box.innerHTML = '<div class="card empty">No accounts found.</div>'; return; }
    S.accounts.forEach(a => {
      const prev = S.prevBalances[a.vpa], cur = Number(a.balance);
      if (prev !== undefined && prev !== cur) S.deltas[a.vpa] = { amt: cur - prev, fresh: true };
    });
    box.innerHTML = S.accounts.map(a => {
      const d = S.deltas[a.vpa];
      const delta = d ? `<div class="delta ${d.amt >= 0 ? 'up' : 'down'}">${d.amt >= 0 ? '+' : '−'}${moneyS(Math.abs(d.amt))} last change</div>` : '<div class="delta"></div>';
      return `<div class="card account ${d && d.fresh ? 'flash' : ''}">
        <div class="avatar">${esc(a.holderName.charAt(0).toUpperCase())}</div>
        <div class="name">${esc(a.holderName)}</div><div class="vpa">${esc(a.vpa)}</div>
        <div class="amt">${money(a.balance)}</div>${delta}</div>`;
    }).join('');
    S.accounts.forEach(a => { S.prevBalances[a.vpa] = Number(a.balance); });
    Object.values(S.deltas).forEach(d => { d.fresh = false; });
  }

  function fillSelect(sel, opts) {
    const sig = JSON.stringify(opts);
    if (sel.dataset.sig === sig) return;
    const cur = sel.value;
    sel.innerHTML = opts.map(o => `<option value="${esc(o.value)}">${esc(o.label)}</option>`).join('');
    sel.dataset.sig = sig;
    if (opts.some(o => o.value === cur)) sel.value = cur;
  }

  function renderFormOptions() {
    const accOpts = S.accounts.map(a => ({ value: a.vpa, label: `${a.holderName} · ${moneyS(a.balance)}` }));
    const snd = $('#sender'), rcv = $('#receiver'), dev = $('#startDevice');
    const firstFill = !snd.options.length;
    fillSelect(snd, accOpts); fillSelect(rcv, accOpts);
    if (firstFill && S.accounts.length > 1) rcv.value = S.accounts[1].vpa;
    if (snd.value === rcv.value && S.accounts.length > 1) rcv.value = S.accounts.find(a => a.vpa !== snd.value).vpa;

    const devFirst = !dev.options.length;
    fillSelect(dev, S.devices.map(d => ({ value: d.deviceId, label: `${d.deviceId} (${d.hasInternet ? '4G' : 'Bluetooth'})` })));
    if (devFirst) { const off = S.devices.find(d => !d.hasInternet); if (off) dev.value = off.deviceId; }
  }

  function renderDevices() {
    const box = $('#devices');
    if (!S.devices.length) { box.innerHTML = '<div class="empty">No devices reported.</div>'; return; }
    box.innerHTML = S.devices.map(d => `
      <div class="device ${d.hasInternet ? 'bridge' : ''}">
        <span aria-hidden="true">${d.hasInternet ? '🌐' : '📱'}</span>
        <div><div class="name">${esc(d.deviceId)}</div>${d.packetIds.length ? `<div class="ids">${d.packetIds.map(esc).join(', ')}</div>` : ''}</div>
        <div><span class="tag ${d.hasInternet ? 'g' : ''}">${d.hasInternet ? '4G' : 'BT'}</span></div>
        <div style="display:flex;gap:10px;align-items:center">
          <button class="btn small" type="button" data-toggle="${esc(d.deviceId)}" data-enabled="${d.hasInternet ? '0' : '1'}">${d.hasInternet ? 'Take offline' : 'Give 4G'}</button>
          <span class="count ${d.packetCount ? 'has' : ''}" title="Packets held">${d.packetCount}</span>
        </div>
      </div>`).join('');
  }

  function renderTopology() {
    const devs = S.devices, n = devs.length, box = $('#topo');
    if (!n) { box.innerHTML = '<div class="empty">No devices.</div>'; return; }
    const W = 640, H = 360, cx = 320, cy = 175, rx = 235, ry = 112;
    const start = $('#startDevice').value;
    const pos = devs.map((d, i) => { const a = -Math.PI / 2 + 2 * Math.PI * i / n; return { x: cx + rx * Math.cos(a), y: cy + ry * Math.sin(a) }; });

    let edges = '';
    for (let i = 0; i < n; i++) for (let j = i + 1; j < n; j++) {
      const br = devs[i].hasInternet || devs[j].hasInternet;
      edges += `<line class="edge ${br ? 'bridge' : ''}" x1="${pos[i].x.toFixed(1)}" y1="${pos[i].y.toFixed(1)}" x2="${pos[j].x.toFixed(1)}" y2="${pos[j].y.toFixed(1)}"/>`;
    }
    const nodes = devs.map((d, i) => {
      const { x, y } = pos[i];
      const grew = !S.firstLoad && d.packetCount > (S.prevCounts[d.deviceId] || 0);
      const cls = ['node', d.hasInternet ? 'bridge' : '', d.packetCount ? 'holding' : '', d.deviceId === start ? 'start' : '', grew ? 'pulse' : ''].join(' ');
      const icon = d.hasInternet
        ? `<circle class="ico" cx="${x}" cy="${y}" r="9"/><ellipse class="ico" cx="${x}" cy="${y}" rx="4" ry="9"/><path class="ico" d="M${x - 9} ${y}h18"/>`
        : `<rect class="ico" x="${x - 6}" y="${y - 10}" width="12" height="20" rx="2.5"/><path class="ico" d="M${x - 2} ${y + 6}h4"/>`;
      const badge = d.packetCount ? `<g class="badge"><circle cx="${x + 20}" cy="${y - 20}" r="10"/><text x="${x + 20}" y="${y - 16}">${d.packetCount}</text></g>` : '';
      return `<g class="${cls}" data-id="${esc(d.deviceId)}" tabindex="0" role="button" aria-label="${esc(d.deviceId)}, ${d.hasInternet ? '4G bridge' : 'offline'}, ${d.packetCount} packets. Activate to send from this phone.">
        <circle class="halo" cx="${x}" cy="${y}" r="26"/><circle class="ring" cx="${x}" cy="${y}" r="33"/>
        <circle class="body" cx="${x}" cy="${y}" r="26"/>${icon}${badge}
        <text x="${x}" y="${y + 48}">${esc(d.deviceId.replace(/^phone-/, ''))}</text>
        <text class="sub" x="${x}" y="${y + 62}">${d.hasInternet ? '4G bridge' : 'Bluetooth'}</text></g>`;
    }).join('');
    box.innerHTML = `<svg viewBox="0 0 ${W} ${H}" role="img" aria-label="Mesh network with ${n} devices">${edges}${nodes}</svg>`;
    devs.forEach(d => { S.prevCounts[d.deviceId] = d.packetCount; });
  }

  function computeStage() {
    const held = S.devices.some(d => d.packetCount > 0);
    const bridgeHolds = S.devices.some(d => d.hasInternet && d.packetCount > 0);
    if (S.flow.flushed && held) return 3;
    if (bridgeHolds) return 2;
    if (held) return 1;
    return 0;
  }

  function renderStepper() {
    const stage = computeStage();
    $$('#stepper .step').forEach(el => {
      const i = Number(el.dataset.step);
      const done = i < stage || stage === 3;
      el.classList.toggle('done', done);
      el.classList.toggle('active', i === stage && stage < 3);
      el.querySelector('.bubble').textContent = done ? '✓' : '';
    });
  }

  function pill(outcome) {
    const label = { DUPLICATE_DROPPED: 'DUPLICATE DROPPED' }[outcome] || outcome;
    return `<span class="status s-${esc(outcome)}">${esc(label)}</span>`;
  }

  function summarize(results) {
    const c = {}; results.forEach(r => { c[r.outcome] = (c[r.outcome] || 0) + 1; });
    const parts = [];
    if (c.SETTLED)           parts.push(`${c.SETTLED} settled`);
    if (c.REJECTED)          parts.push(`${c.REJECTED} rejected`);
    if (c.INVALID)           parts.push(`${c.INVALID} invalid`);
    if (c.DUPLICATE_DROPPED) parts.push(`${c.DUPLICATE_DROPPED} duplicate${c.DUPLICATE_DROPPED > 1 ? 's' : ''} dropped`);
    return { counts: c, text: parts.join(' · ') };
  }

  function renderFlush() {
    const box = $('#flushResults'), r = S.lastFlush;
    if (!r) { box.innerHTML = '<div class="empty">No flush yet. Inject a packet, run gossip, then flush.</div>'; return; }
    if (!r.length) {
      box.innerHTML = '<div class="callout warn">No online device is holding a packet, so nothing was uploaded. Run a gossip round so the packet reaches a phone with 4G — or give a phone 4G in <b>Mesh devices</b>.</div>';
      return;
    }
    const sm = summarize(r);
    const kind = sm.counts.INVALID ? 'bad' : (sm.counts.REJECTED ? 'warn' : (sm.counts.SETTLED ? 'ok' : 'info'));
    box.innerHTML = `<div class="callout ${kind}" style="margin:0 0 10px">${esc(sm.text)}</div>
      <div class="scroll-x"><table class="table"><thead><tr><th>Bridge</th><th>Packet</th><th>Outcome</th><th class="num">Hops</th><th>Detail</th></tr></thead><tbody>
      ${r.map(x => `<tr ${x.transactionId > 0 ? `class="click" data-tx="${x.transactionId}"` : ''}>
        <td>${esc(x.bridgeNode)}</td><td class="mono">${esc(x.packetId)}</td><td>${pill(x.outcome)}</td>
        <td class="num">${esc(x.hopCount)}</td><td>${esc(x.reason || (x.outcome === 'DUPLICATE_DROPPED' ? 'already claimed' : x.transactionId > 0 ? `tx #${x.transactionId}` : '—'))}</td></tr>`).join('')}
      </tbody></table></div>`;
  }

  function renderInspector() {
    const box = $('#inspector'), p = S.inspector;
    if (!p) { box.innerHTML = '<div class="empty">Inject a packet to inspect it.</div>'; return; }
    const decision = S.lastFlush && S.lastFlush.find(x => x.packetId === p.packetId.slice(0, 8));
    box.innerHTML = `<dl class="kv">
        <dt>Packet ID</dt><dd class="mono">${esc(p.packetId)}</dd>
        <dt>Injected at</dt><dd>${esc(p.injectedAt)}</dd>
        <dt>TTL</dt><dd>${esc(p.ttl)} hops</dd>
        <dt>Ciphertext</dt><dd>${esc(p.ciphertextLength)} base64 chars</dd></dl>
      <div class="cipher" style="margin-top:10px">${esc(p.ciphertextPreview)}</div>
      <div class="callout info">Relaying phones see only this opaque blob plus the TTL. The sender, receiver and amount are inside the encrypted payload — only the backend can read them.</div>
      ${decision ? `<div class="callout ${decision.outcome === 'SETTLED' ? 'ok' : 'warn'}">Backend decision: ${pill(decision.outcome)} ${esc(decision.reason || '')}</div>` : ''}`;
  }

  function filteredTxs() {
    const q = S.ledgerQuery.trim().toLowerCase();
    return S.txs.filter(t => (S.ledgerFilter === 'ALL' || t.status === S.ledgerFilter) &&
      (!q || t.senderVpa.toLowerCase().includes(q) || t.receiverVpa.toLowerCase().includes(q)));
  }

  function renderLedger() {
    const list = filteredTxs();
    $('#ledgerCount').textContent = S.txs.length ? `${list.length} of ${S.txs.length} most recent` : 'Nothing recorded yet';
    $$('#ledgerFilters button').forEach(b => b.classList.toggle('on', b.dataset.f === S.ledgerFilter));
    const box = $('#ledgerBox');
    if (!S.txs.length) { box.innerHTML = '<div class="empty">No transactions yet. Inject a packet and run a flush.</div>'; return; }
    if (!list.length) { box.innerHTML = '<div class="empty">No transactions match this filter.</div>'; return; }
    box.innerHTML = `<table class="table"><thead><tr><th>#</th><th>From → To</th><th class="num">Amount</th><th>Status</th><th class="hide-m">Bridge</th><th class="num hide-m">Hops</th><th>Time</th></tr></thead><tbody>
      ${list.map(t => `<tr class="click" data-id="${t.id}"><td class="mono">${t.id}</td>
        <td><b>${esc(nameOf(t.senderVpa))}</b> → ${esc(nameOf(t.receiverVpa))}</td>
        <td class="num">${money(t.amount)}</td><td>${pill(t.status)}</td>
        <td class="hide-m">${esc(t.bridgeNodeId)}</td><td class="num hide-m">${t.hopCount}</td><td>${timeOf(t.settledAt)}</td></tr>`).join('')}</tbody></table>`;
  }

  const EV_ICON = { SETTLED: '✓', REJECTED: '!', INVALID: '✕', ATTACK: '⚠', DUPLICATE: '⧉', INJECT: '↑', GOSSIP: '⇄', FLUSH: '↓', RESET: '↺', DEVICE: '●' };

  function renderEvents() {
    const box = $('#events');
    if (!S.events.length) { box.innerHTML = '<div class="empty">Waiting for activity…</div>'; return; }
    const seen = S.lastEventId;
    box.innerHTML = S.events.map(e => `<div class="event ev-${esc(e.type)} ${!S.firstLoad && e.id > seen ? 'ev-new' : ''}">
      <div class="ev-icon">${EV_ICON[e.type] || '•'}</div>
      <div><div class="ev-msg">${esc(e.message)}</div><div class="ev-time">${esc(e.type)} · ${timeOf(e.at)}</div></div></div>`).join('');
    S.lastEventId = Math.max(S.lastEventId, ...S.events.map(e => e.id));
  }

  // ───────────────────────── receipt ─────────────────────────
  function openReceipt(t) {
    $('#rAmount').textContent = money(t.amount);
    $('#rStatus').innerHTML = pill(t.status);
    const secs = t.signedAt && t.settledAt ? Math.max(0, Math.round((new Date(t.settledAt) - new Date(t.signedAt)) / 1000)) : null;
    $('#rTimeline').innerHTML = `
      <li>Signed offline by ${esc(nameOf(t.senderVpa))}<small>${t.signedAt ? new Date(t.signedAt).toLocaleString('en-IN') : '—'}</small></li>
      <li>Relayed through ${t.hopCount} mesh hop${t.hopCount === 1 ? '' : 's'}<small>uploaded by ${esc(t.bridgeNodeId)}</small></li>
      <li>${t.status === 'SETTLED' ? 'Settled' : 'Recorded as ' + esc(t.status)} by the backend<small>${t.settledAt ? new Date(t.settledAt).toLocaleString('en-IN') : '—'}${secs !== null ? ` · ${secs}s after signing` : ''}</small></li>`;
    const rows = [['Transaction', '#' + t.id], ['From', `${nameOf(t.senderVpa)} · ${t.senderVpa}`], ['To', `${nameOf(t.receiverVpa)} · ${t.receiverVpa}`],
      ['Bridge node', t.bridgeNodeId], ['Mesh hops', t.hopCount]];
    if (t.rejectionReason) rows.push(['Reason', t.rejectionReason]);
    $('#rRows').innerHTML = rows.map(r => `<dt>${esc(r[0])}</dt><dd>${esc(r[1])}</dd>`).join('') +
      `<dt>Packet hash</dt><dd class="mono" style="font-size:11px">${esc(t.packetHash)} <button class="btn small" type="button" data-copy="${esc(t.packetHash)}">Copy</button></dd>`;
    openModal('receiptModal');
  }

  // ───────────────────────── busy handling ─────────────────────────
  const ACTION_IDS = ['runDemoBtn', 'runDemoBtn2', 'injectBtn', 'gossipBtn', 'flushBtn', 'meshResetBtn', 'fullResetBtn', 'labTamper', 'labReplay', 'labRace', 'labOverdraw'];

  async function run(btn, fn) {
    if (S.busy) return;
    S.busy = true;
    const label = btn ? btn.innerHTML : '';
    ACTION_IDS.forEach(id => { const b = document.getElementById(id); if (b) b.disabled = true; });
    if (btn) btn.innerHTML = '<span class="spin"></span> Working…';
    try { await fn(); }
    catch (e) { toast(e.message || 'Something went wrong', 'error'); }
    finally {
      if (btn) btn.innerHTML = label;
      ACTION_IDS.forEach(id => { const b = document.getElementById(id); if (b) b.disabled = false; });
      S.busy = false;
    }
  }

  // ───────────────────────── form ─────────────────────────
  function readForm(overrides = {}) {
    const v = {
      sender: $('#sender').value, receiver: $('#receiver').value,
      amountRaw: overrides.amount !== undefined ? String(overrides.amount) : $('#amount').value.trim(),
      pin: $('#pin').value.trim(), ttl: Number($('#ttl').value), start: $('#startDevice').value
    };
    const errs = [];
    if (!v.sender || !v.receiver) errs.push(['sender', 'Accounts have not loaded yet']);
    else if (v.sender === v.receiver) errs.push(['receiver', 'Choose two different accounts']);
    if (!/^\d+(\.\d{1,2})?$/.test(v.amountRaw) || Number(v.amountRaw) <= 0) errs.push(['amount', 'Enter an amount above 0 (max 2 decimals)']);
    if (!/^\d{4}$/.test(v.pin)) errs.push(['pin', 'PIN must be exactly 4 digits']);
    if (!Number.isInteger(v.ttl) || v.ttl < 1 || v.ttl > 10) errs.push(['ttl', 'TTL must be a whole number from 1 to 10']);
    if (!v.start) errs.push(['startDevice', 'Pick the sender’s phone']);
    ['sender', 'receiver', 'amount', 'pin', 'ttl', 'startDevice'].forEach(id => $('#' + id).setAttribute('aria-invalid', errs.some(e => e[0] === id) ? 'true' : 'false'));
    if (errs.length) { toast(errs[0][1], 'error'); $('#' + errs[0][0]).focus(); return null; }
    return { senderVpa: v.sender, receiverVpa: v.receiver, amount: Number(v.amountRaw), pin: v.pin, ttl: v.ttl, startDevice: v.start };
  }

  // ───────────────────────── actions ─────────────────────────
  async function actInject(payload) {
    const r = await api('/api/demo/send', { method: 'POST', body: payload });
    S.flow.flushed = false; S.lastFlush = null;
    S.inspector = r; renderFlush(); renderInspector();
    toast(`Packet ${r.packetId.slice(0, 8)} injected at ${r.injectedAt}`, 'ok');
    await refresh();
    return r;
  }

  async function actGossip() {
    const r = await api('/api/mesh/gossip', { method: 'POST' });
    toast(r.transfers ? `${r.transfers} packet transfer${r.transfers === 1 ? '' : 's'} across the mesh` : 'Nothing new to spread — every phone already has the packet', r.transfers ? 'ok' : '');
    await refresh();
    if (r.transfers) $$('#topo .edge').forEach(e => e.classList.add('flash'));   // after re-render, so it isn't wiped
    return r;
  }

  async function actFlush(quiet) {
    const r = await api('/api/mesh/flush', { method: 'POST' });
    S.lastFlush = r.results; S.flow.flushed = true;
    if (!quiet) {
      if (!r.results.length) toast('No online device holds a packet — nothing to upload', 'error');
      else toast(summarize(r.results).text, r.results.some(x => x.outcome === 'INVALID') ? 'error' : 'ok');
    }
    await refresh();
    return r;
  }

  async function actMeshReset() {
    await api('/api/mesh/reset', { method: 'POST' });
    S.flow.flushed = false; S.lastFlush = null; S.inspector = null;
    await refresh();
    toast('Mesh and idempotency cache cleared', 'ok');
  }

  async function actFullDemo(overrides) {
    const payload = readForm(overrides);
    if (!payload) return null;
    await api('/api/mesh/reset', { method: 'POST' });
    S.flow.flushed = false; S.lastFlush = null; S.inspector = null;
    await refresh();
    await actInject(payload); await sleep(750);
    await actGossip();        await sleep(750);
    const r = await actFlush(false);
    $('#results').scrollIntoView?.({ behavior: 'smooth', block: 'nearest' });
    return r;
  }

  async function actFullReset() {
    if (!window.confirm('Full reset restores the demo balances and clears the ledger, mesh, cache and counters. Continue?')) return;
    await api('/api/demo/reset-full', { method: 'POST', auth: true });
    Object.assign(S, { lastFlush: null, inspector: null, prevBalances: {}, deltas: {} });
    S.flow.flushed = false;
    ['outTamper', 'outReplay', 'outRace', 'outOverdraw'].forEach(id => { $('#' + id).innerHTML = ''; });
    await refresh();
    toast('Full reset complete', 'ok');
  }

  // ── security lab ──
  const callout = (kind, html) => `<div class="callout ${kind}">${html}</div>`;

  async function labTamper() {
    const p = readForm(); if (!p) return;
    const r = await api('/api/demo/tamper', { method: 'POST', body: p });
    $('#outTamper').innerHTML = r.outcome === 'INVALID'
      ? callout('ok', `<b>Blocked ✓</b> — outcome INVALID (${esc(r.reason)}). Flipping one character changed the packet hash from <span class="mono">${esc(shortHash(r.originalPacketHash))}</span> to <span class="mono">${esc(shortHash(r.tamperedPacketHash))}</span> and the AES-GCM auth tag failed. No money moved.`)
      : callout('bad', `Unexpected outcome ${esc(r.outcome)} — the tampered packet was not rejected.`);
    await refresh();
  }

  async function labReplay() {
    const r = await api('/api/mesh/flush', { method: 'POST' });
    S.lastFlush = r.results; S.flow.flushed = true;
    const sm = summarize(r.results);
    $('#outReplay').innerHTML = !r.results.length
      ? callout('info', 'Nothing to replay yet — inject a packet, run gossip, upload once, then try again.')
      : (sm.counts.DUPLICATE_DROPPED && !sm.counts.SETTLED
        ? callout('ok', `<b>Blocked ✓</b> — ${esc(sm.text)}. Balances unchanged.`)
        : callout('info', `${esc(sm.text)}. (This was the packet's first upload, so it settled normally — press again to replay it.)`));
    await refresh();
  }

  async function labRace() {
    const p = readForm(); if (!p) return;
    const r = await api('/api/demo/concurrent-upload', { method: 'POST', body: { ...p, bridges: Number($('#raceN').value) } });
    const exactlyOnce = (r.settled + r.otherOutcomes) === 1;
    $('#outRace').innerHTML = callout(exactlyOnce ? 'ok' : 'bad',
      `<b>${exactlyOnce ? 'Exactly once ✓' : 'Unexpected result'}</b> — ${r.bridges} bridges uploaded at the same instant: ${r.settled} settled${r.otherOutcomes ? `, ${r.otherOutcomes} rejected/invalid` : ''}, ${r.duplicatesDropped} duplicate${r.duplicatesDropped === 1 ? '' : 's'} dropped.`) +
      `<div class="scroll-x" style="margin-top:8px"><table class="table"><tbody>${r.results.map(x => `<tr><td>${esc(x.bridgeNode)}</td><td>${pill(x.outcome)}</td></tr>`).join('')}</tbody></table></div>`;
    await refresh();
  }

  async function labOverdraw() {
    const senderBal = Number((S.accounts.find(a => a.vpa === $('#sender').value) || {}).balance || 0);
    const amount = (Math.floor(senderBal) + 100).toFixed(2);
    const r = await actFullDemo({ amount });
    if (!r) return;
    const row = r.results.find(x => x.outcome === 'REJECTED');
    $('#outOverdraw').innerHTML = row
      ? callout('ok', `<b>Blocked ✓</b> — tried to send ${money(amount)} from a ${money(senderBal)} balance: outcome REJECTED (${esc(row.reason)}). Balances unchanged.`)
      : callout('bad', `Unexpected result: ${esc(summarize(r.results).text || 'no upload happened')}.`);
  }

  // ───────────────────────── init ─────────────────────────
  function bind() {
    $('#runDemoBtn').addEventListener('click', e => run(e.currentTarget, () => actFullDemo()));
    $('#runDemoBtn2').addEventListener('click', e => run(e.currentTarget, () => actFullDemo()));
    $('#injectBtn').addEventListener('click', e => run(e.currentTarget, async () => { const p = readForm(); if (p) await actInject(p); }));
    $('#gossipBtn').addEventListener('click', e => run(e.currentTarget, actGossip));
    $('#flushBtn').addEventListener('click', e => run(e.currentTarget, () => actFlush(false)));
    $('#meshResetBtn').addEventListener('click', e => run(e.currentTarget, actMeshReset));
    $('#fullResetBtn').addEventListener('click', e => run(e.currentTarget, actFullReset));
    $('#labTamper').addEventListener('click', e => run(e.currentTarget, labTamper));
    $('#labReplay').addEventListener('click', e => run(e.currentTarget, labReplay));
    $('#labRace').addEventListener('click', e => run(e.currentTarget, labRace));
    $('#labOverdraw').addEventListener('click', e => run(e.currentTarget, labOverdraw));
    $('#auditRefresh').addEventListener('click', async () => { await refresh(); toast('Refreshed'); });

    $('#sender').addEventListener('change', () => {
      const r = $('#receiver');
      if (r.value === $('#sender').value) { const o = S.accounts.find(a => a.vpa !== $('#sender').value); if (o) r.value = o.vpa; }
    });
    $('#receiver').addEventListener('change', () => {
      const s = $('#sender');
      if (s.value === $('#receiver').value) { const o = S.accounts.find(a => a.vpa !== $('#receiver').value); if (o) s.value = o.vpa; }
    });
    $('#startDevice').addEventListener('change', renderTopology);

    $('#quickAmts').addEventListener('click', e => {
      const b = e.target.closest('button'); if (!b) return;
      if (b.dataset.amt === 'overdraw') {
        const bal = Number((S.accounts.find(a => a.vpa === $('#sender').value) || {}).balance || 0);
        $('#amount').value = (Math.floor(bal) + 100).toFixed(2);
      } else $('#amount').value = b.dataset.amt;
    });

    // delegated: device toggles, topology nodes, ledger + flush rows, copy buttons
    document.addEventListener('click', async e => {
      const tg = e.target.closest('[data-toggle]');
      if (tg) {
        if (S.busy) return;
        try {
          await api(`/api/mesh/devices/${encodeURIComponent(tg.dataset.toggle)}/internet`, { method: 'POST', body: { enabled: tg.dataset.enabled === '1' } });
          await refresh();
        } catch (err) { toast(err.message, 'error'); }
        return;
      }
      const node = e.target.closest('#topo .node');
      if (node) { selectStart(node.dataset.id); return; }
      const row = e.target.closest('tr[data-id],tr[data-tx]');
      if (row) {
        const id = Number(row.dataset.id || row.dataset.tx);
        const t = S.txs.find(x => x.id === id);
        if (t) openReceipt(t); else toast('That transaction is no longer in the recent list');
        return;
      }
      const cp = e.target.closest('[data-copy]');
      if (cp) { copyText(cp.dataset.copy); return; }
      const cl = e.target.closest('[data-close]');
      if (cl) closeModal(cl.dataset.close);
    });
    $('#topo').addEventListener('keydown', e => {
      const node = e.target.closest('.node');
      if (node && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); selectStart(node.dataset.id); }
    });

    $$('.modal').forEach(m => m.addEventListener('mousedown', e => { if (e.target === m) { m.id === 'keyModal' ? finishKey(false) : closeModal(m.id); } }));
    document.addEventListener('keydown', e => {
      if (e.key !== 'Escape') return;
      if ($('#keyModal').classList.contains('open')) finishKey(false);
      closeModal('receiptModal');
    });

    $('#keySave').addEventListener('click', () => finishKey(true));
    $('#keyCancel').addEventListener('click', () => finishKey(false));
    $('#apiKeyInput').addEventListener('keydown', e => { if (e.key === 'Enter') finishKey(true); });
    $('#apiKeyBtn').addEventListener('click', () => { askKey('Needed for Full reset. Kept only for this browser tab.'); });

    $('#ledgerFilters').addEventListener('click', e => {
      const b = e.target.closest('button[data-f]'); if (!b) return;
      S.ledgerFilter = b.dataset.f; renderLedger();
    });
    $('#ledgerSearch').addEventListener('input', e => { S.ledgerQuery = e.target.value; renderLedger(); });
    $('#csvBtn').addEventListener('click', exportCsv);
    $('#copyKeyBtn').addEventListener('click', () => S.serverKey ? copyText(S.serverKey) : toast('Key not loaded yet', 'error'));
  }

  function selectStart(id) {
    const sel = $('#startDevice');
    if (![...sel.options].some(o => o.value === id)) return;
    sel.value = id; renderTopology();
    toast(`Payments will now start at ${id}`);
  }

  async function copyText(text) {
    try { await navigator.clipboard.writeText(text); toast('Copied to clipboard', 'ok'); }
    catch {
      const ta = document.createElement('textarea'); ta.value = text; document.body.appendChild(ta); ta.select();
      try { document.execCommand('copy'); toast('Copied to clipboard', 'ok'); } catch { toast('Copy failed', 'error'); }
      ta.remove();
    }
  }

  function exportCsv() {
    const list = filteredTxs();
    if (!list.length) { toast('Nothing to export yet', 'error'); return; }
    const q = v => '"' + String(v ?? '').replace(/"/g, '""') + '"';
    const rows = [['id', 'sender', 'receiver', 'amount', 'status', 'reason', 'bridge', 'hops', 'signedAt', 'settledAt', 'packetHash']]
      .concat(list.map(t => [t.id, t.senderVpa, t.receiverVpa, t.amount, t.status, t.rejectionReason || '', t.bridgeNodeId, t.hopCount, t.signedAt, t.settledAt, t.packetHash]));
    const blob = new Blob([rows.map(r => r.map(q).join(',')).join('\n')], { type: 'text/csv' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob); a.download = 'upi-mesh-ledger.csv';
    document.body.appendChild(a); a.click(); a.remove();
    setTimeout(() => URL.revokeObjectURL(a.href), 1000);
  }

  async function loadServerKey() {
    try {
      const k = await api('/api/server-key');
      S.serverKey = k.publicKey;
      $('#keyAlgo').textContent = `${k.algorithm} · ${k.hybridScheme}`;
      $('#keyValue').textContent = k.publicKey.slice(0, 44) + '…';
    } catch { $('#keyAlgo').textContent = 'Could not load the server key'; }
  }

  function scrollSpy() {
    const links = $$('#nav a'); if (!('IntersectionObserver' in window)) return;
    const map = new Map(links.map(a => [a.dataset.target, a]));
    const io = new IntersectionObserver(entries => {
      entries.forEach(en => {
        if (!en.isIntersecting) return;
        links.forEach(l => l.classList.remove('active'));
        const l = map.get(en.target.id); if (l) l.classList.add('active');
      });
    }, { rootMargin: '-30% 0px -60% 0px' });
    map.forEach((_, id) => { const el = document.getElementById(id); if (el) io.observe(el); });
  }

  document.addEventListener('DOMContentLoaded', () => {
    bind(); scrollSpy(); loadServerKey(); refresh();
    setInterval(() => { if (!pending && !S.busy && !document.hidden) refresh(); }, 4000);
    document.addEventListener('visibilitychange', () => { if (!document.hidden) refresh(); });
  });
})();
