package com.swarmcron.http;

/**
 * The dashboard: one static HTML file with inline CSS/JS, no external
 * scripts, stylesheets, icon fonts, or chart libraries (consistent with the
 * project's zero-dependency constraint). A sidebar-navigated app shell over
 * four sections (Overview/Cluster/Jobs/Activity); the color system is the
 * validated dark palette (status colors, categorical blue, chart surfaces)
 * rather than ad hoc hex values, and the "recent run activity" panel is a
 * real inline-SVG sparkline, not just a table, per the project's own
 * dataviz conventions for anything that renders live operational data.
 *
 * Fetches its initial state from the JSON API on load, then keeps itself
 * current by re-fetching whichever section an SSE event on /events says
 * changed -- simpler and plenty fast at this data scale than hand-rolling
 * client-side diffing for a handful of small tables and one sparkline.
 */
final class DashboardPage {

    private DashboardPage() {}

    static final String HTML = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>SwarmCron</title>
            <style>
              :root {
                color-scheme: dark;
                --surface-0: #0d0d0d;
                --surface-1: #1a1a19;
                --surface-2: #212120;
                --text-primary: #ffffff;
                --text-secondary: #c3c2b7;
                --text-muted: #898781;
                --border: rgba(255,255,255,0.10);
                --gridline: #2c2c2a;
                --brand: #3987e5;
                --brand-strong: #2a78d6;
                --brand-dim: rgba(57,135,229,0.16);
                --good: #0ca30c;
                --good-dim: rgba(12,163,12,0.16);
                --warning: #fab219;
                --warning-dim: rgba(250,178,25,0.16);
                --critical: #d03b3b;
                --critical-dim: rgba(208,59,59,0.16);
              }
              * { box-sizing: border-box; }
              body { margin: 0; font-family: system-ui, -apple-system, "Segoe UI", sans-serif; background: var(--surface-0); color: var(--text-primary); }
              code, .mono { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; }

              .app { display: flex; min-height: 100vh; }

              .sidebar { width: 228px; flex: none; background: var(--surface-2); border-right: 1px solid var(--border); padding: 18px 12px; display: flex; flex-direction: column; gap: 4px; }
              .brand-row { display: flex; align-items: center; gap: 10px; padding: 4px 8px 18px; }
              .brand-row .mark { width: 28px; height: 28px; border-radius: 8px; background: linear-gradient(135deg, var(--brand), var(--brand-strong)); display: flex; align-items: center; justify-content: center; font-weight: 700; font-size: 13px; flex: none; }
              .brand-row .name { font-weight: 700; font-size: 15px; letter-spacing: -0.01em; }

              .node-pill { margin: 0 8px 16px; padding: 10px 12px; background: var(--surface-1); border: 1px solid var(--border); border-radius: 10px; }
              .node-pill .label { font-size: 10.5px; color: var(--text-muted); text-transform: uppercase; letter-spacing: .06em; }
              .node-pill .id { font-size: 14px; font-weight: 700; margin-top: 3px; }
              .node-pill .conn { display: flex; align-items: center; gap: 6px; margin-top: 9px; font-size: 12px; color: var(--text-secondary); }
              .dot { width: 7px; height: 7px; border-radius: 50%; background: var(--good); box-shadow: 0 0 0 3px var(--good-dim); flex: none; }
              .dot.warn { background: var(--warning); box-shadow: 0 0 0 3px var(--warning-dim); }

              nav.tabs { display: flex; flex-direction: column; gap: 2px; }
              nav.tabs button { all: unset; cursor: pointer; padding: 9px 10px; border-radius: 8px; font-size: 13.5px; color: var(--text-secondary); display: flex; align-items: center; gap: 10px; }
              nav.tabs button:hover { background: rgba(255,255,255,0.05); color: var(--text-primary); }
              nav.tabs button.active { background: var(--brand-dim); color: var(--text-primary); font-weight: 600; }
              nav.tabs button svg { width: 16px; height: 16px; flex: none; opacity: .9; }
              .sidebar-foot { margin-top: auto; padding: 10px 8px 2px; font-size: 11px; line-height:1.5; color: var(--text-muted); }

              .main { flex: 1; min-width: 0; padding: 26px 34px 60px; max-width: 1180px; }
              .topbar { margin-bottom: 20px; }
              .topbar h1 { font-size: 20px; margin: 0; letter-spacing: -0.01em; }
              .topbar .desc { font-size: 12.5px; color: var(--text-muted); margin-top: 3px; }

              .banner { display: none; align-items: center; gap: 10px; background: var(--critical-dim); border: 1px solid rgba(208,59,59,0.4); color: #ff9f9f; padding: 10px 14px; border-radius: 10px; font-size: 13px; font-weight: 600; margin-bottom: 18px; }
              .banner.show { display: flex; }

              .kpi-row { display: grid; grid-template-columns: repeat(auto-fit, minmax(140px, 1fr)); gap: 12px; margin-bottom: 22px; }
              .kpi { background: var(--surface-1); border: 1px solid var(--border); border-radius: 12px; padding: 14px 16px; }
              .kpi .k { font-size: 10.5px; text-transform: uppercase; letter-spacing: .06em; color: var(--text-muted); margin-bottom: 9px; }
              .kpi .v { font-size: 21px; font-weight: 700; letter-spacing: -0.01em; }

              .card { background: var(--surface-1); border: 1px solid var(--border); border-radius: 12px; padding: 18px 20px; margin-bottom: 18px; }
              .card h2 { font-size: 12.5px; text-transform: uppercase; letter-spacing: .06em; color: var(--text-muted); margin: 0 0 14px; font-weight: 600; }
              .card .empty { color: var(--text-muted); font-size: 13px; padding: 8px 2px; }

              table { width: 100%; border-collapse: collapse; font-size: 13px; }
              thead th { text-align: left; font-size: 10.5px; text-transform: uppercase; letter-spacing: .04em; color: var(--text-muted); font-weight: 600; padding: 0 10px 10px; border-bottom: 1px solid var(--gridline); }
              tbody td { padding: 10px 10px; border-bottom: 1px solid var(--gridline); vertical-align: middle; }
              tbody tr:last-child td { border-bottom: none; }
              tbody tr:hover td { background: rgba(255,255,255,0.025); }
              .cmd { color: var(--text-secondary); }
              code.pill { background: rgba(255,255,255,0.07); padding: 2px 7px; border-radius: 5px; font-size: 12px; }

              .badge { display: inline-flex; align-items: center; gap: 6px; padding: 3px 9px 3px 7px; border-radius: 20px; font-size: 12px; font-weight: 600; white-space: nowrap; }
              .badge .d { width: 6px; height: 6px; border-radius: 50%; flex: none; }
              .badge.good { background: var(--good-dim); color: #4fd94f; } .badge.good .d { background: var(--good); }
              .badge.warn { background: var(--warning-dim); color: var(--warning); } .badge.warn .d { background: var(--warning); }
              .badge.crit { background: var(--critical-dim); color: #ff8f8f; } .badge.crit .d { background: var(--critical); }
              .badge.muted { background: rgba(255,255,255,0.07); color: var(--text-secondary); } .badge.muted .d { background: var(--text-muted); }
              .badge.brand { background: var(--brand-dim); color: #8fbdf2; } .badge.brand .d { background: var(--brand); }

              .toolbar { display: flex; gap: 8px; margin-top: 16px; flex-wrap: wrap; border-top: 1px solid var(--gridline); padding-top: 16px; }
              .toolbar input { background: var(--surface-2); border: 1px solid var(--border); color: var(--text-primary); padding: 8px 10px; border-radius: 8px; font-size: 13px; }
              .toolbar input::placeholder { color: var(--text-muted); }
              .toolbar input:focus { outline: 2px solid var(--brand); outline-offset: -1px; }
              .toolbar input[name=id] { width: 140px; }
              .toolbar input[name=schedule] { width: 150px; font-family: ui-monospace, monospace; }
              .toolbar input[name=command] { flex: 1; min-width: 220px; }
              button.primary { background: var(--brand); color: white; border: none; padding: 9px 18px; border-radius: 8px; font-size: 13px; font-weight: 600; cursor: pointer; }
              button.primary:hover { background: var(--brand-strong); }
              button.ghost-danger { background: transparent; border: 1px solid rgba(208,59,59,0.4); color: #ff9f9f; padding: 5px 11px; border-radius: 7px; font-size: 12px; cursor: pointer; }
              button.ghost-danger:hover { background: var(--critical-dim); }

              .meter { height: 6px; border-radius: 4px; background: var(--gridline); overflow: hidden; display: flex; margin-top: 9px; }
              .meter > span { height: 100%; }

              .tab-panel { display: none; }
              .tab-panel.active { display: block; }

              .sparkline-wrap { display: flex; align-items: center; gap: 20px; flex-wrap: wrap; }
              svg.spark { display: block; max-width: 100%; }
              .spark-legend { display: flex; gap: 18px; font-size: 12px; color: var(--text-secondary); }
              .spark-legend .sw { display: inline-block; width: 9px; height: 9px; border-radius: 2px; margin-right: 6px; vertical-align: -1px; }

              @media (max-width: 780px) {
                .sidebar { position: fixed; z-index: 5; height: 100vh; transform: translateX(-100%); transition: transform .15s; }
                .sidebar.open { transform: translateX(0); }
                .main { padding: 18px; }
              }
            </style>
            </head>
            <body>
            <div class="app">
              <aside class="sidebar">
                <div class="brand-row"><div class="mark">SC</div><div class="name">SwarmCron</div></div>
                <div class="node-pill">
                  <div class="label">This node</div>
                  <div class="id mono" id="selfId">&ndash;</div>
                  <div class="conn"><span class="dot" id="connDot"></span><span id="connState">connecting&hellip;</span></div>
                </div>
                <nav class="tabs">
                  <button data-tab="overview" class="active" type="button">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><rect x="3" y="3" width="7" height="9" rx="1.5"/><rect x="14" y="3" width="7" height="5" rx="1.5"/><rect x="14" y="12" width="7" height="9" rx="1.5"/><rect x="3" y="16" width="7" height="5" rx="1.5"/></svg>
                    Overview
                  </button>
                  <button data-tab="cluster" type="button">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><circle cx="12" cy="5" r="2.5"/><circle cx="5" cy="19" r="2.5"/><circle cx="19" cy="19" r="2.5"/><path d="M12 7.5V13M12 13L6.5 17M12 13l5.5 4"/></svg>
                    Cluster
                  </button>
                  <button data-tab="jobs" type="button">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M4 6h11M4 12h11M4 18h7"/><path d="M17 16l2.2 2.2L23 14"/></svg>
                    Jobs
                  </button>
                  <button data-tab="activity" type="button">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><path d="M3 12h4l2-7 4 14 2-7h6"/></svg>
                    Activity
                  </button>
                </nav>
                <div class="sidebar-foot">Peer-to-peer cron scheduler.<br>Zero external dependencies.</div>
              </aside>

              <main class="main">
                <div class="banner" id="degradedBanner">&#9888; DEGRADED &mdash; this node cannot see a majority of the known cluster and will not execute jobs</div>

                <section class="tab-panel active" id="tab-overview">
                  <div class="topbar"><h1>Overview</h1><div class="desc">Live cluster state from this node's point of view</div></div>
                  <div class="kpi-row" id="kpiRow"></div>
                  <div class="card">
                    <h2>Run activity</h2>
                    <div class="sparkline-wrap">
                      <svg class="spark" id="sparkSvg" width="620" height="110" viewBox="0 0 620 110"></svg>
                      <div class="spark-legend">
                        <div><span class="sw" style="background:var(--brand)"></span>duration</div>
                        <div><span class="sw" style="background:var(--critical)"></span>failed run</div>
                      </div>
                    </div>
                  </div>
                </section>

                <section class="tab-panel" id="tab-cluster">
                  <div class="topbar"><h1>Cluster</h1><div class="desc"><span id="nodeCount">0</span> known node(s)</div></div>
                  <div class="card">
                    <table id="nodesTable">
                      <thead><tr><th>Node</th><th>Address</th><th>State</th><th>Incarnation</th></tr></thead>
                      <tbody></tbody>
                    </table>
                  </div>
                </section>

                <section class="tab-panel" id="tab-jobs">
                  <div class="topbar"><h1>Jobs</h1><div class="desc">Scheduled cluster-wide; ring assigns one owning node per job</div></div>
                  <div class="card">
                    <table id="jobsTable">
                      <thead><tr><th>ID</th><th>Schedule</th><th>Command</th><th>Owner</th><th>Enabled</th><th>Overlap</th><th>Last Run</th><th></th></tr></thead>
                      <tbody></tbody>
                    </table>
                    <form class="toolbar" id="addJobForm">
                      <input name="id" placeholder="job id" required>
                      <input name="schedule" placeholder="*/5 * * * * *" required>
                      <input name="command" placeholder="/bin/echo hello" required>
                      <button class="primary" type="submit">Add job</button>
                    </form>
                  </div>
                </section>

                <section class="tab-panel" id="tab-activity">
                  <div class="topbar"><h1>Activity</h1><div class="desc">Recent runs across every node in the cluster</div></div>
                  <div class="card">
                    <table id="runsTable">
                      <thead><tr><th>Time</th><th>Job</th><th>Node</th><th>Result</th><th>Exit</th><th>Duration</th></tr></thead>
                      <tbody></tbody>
                    </table>
                  </div>
                </section>
              </main>
            </div>

            <script>
              const esc = s => String(s).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
              const fmtTime = ms => ms ? new Date(ms).toLocaleTimeString() : '&ndash;';
              const NODE_BADGE = { ALIVE: 'good', SUSPECT: 'warn', DEAD: 'crit', LEFT: 'muted' };
              let lastRuns = [];
              let lastStatus = null;

              // ---- tab navigation ----
              document.querySelectorAll('nav.tabs button').forEach(btn => {
                btn.addEventListener('click', () => {
                  document.querySelectorAll('nav.tabs button').forEach(b => b.classList.remove('active'));
                  document.querySelectorAll('.tab-panel').forEach(p => p.classList.remove('active'));
                  btn.classList.add('active');
                  document.getElementById('tab-' + btn.dataset.tab).classList.add('active');
                });
              });

              // ---- data loading ----
              async function loadStatus() {
                lastStatus = await (await fetch('/api/status')).json();
                document.getElementById('selfId').textContent = lastStatus.selfId;
                document.getElementById('degradedBanner').classList.toggle('show', lastStatus.raft.degraded);
                renderKpis();
              }

              async function loadNodes() {
                const nodes = await (await fetch('/api/nodes')).json();
                document.getElementById('nodeCount').textContent = nodes.length;
                document.querySelector('#nodesTable tbody').innerHTML = nodes.map(n => `
                  <tr><td><strong>${esc(n.nodeId)}</strong></td><td class="mono cmd">${esc(n.address)}</td>
                  <td><span class="badge ${NODE_BADGE[n.state] || 'muted'}"><span class="d"></span>${esc(n.state)}</span></td>
                  <td>${n.incarnation}</td></tr>
                `).join('') || '<tr><td colspan="4" class="empty">no nodes known yet</td></tr>';
              }

              async function loadJobs() {
                const jobs = await (await fetch('/api/jobs')).json();
                document.querySelector('#jobsTable tbody').innerHTML = jobs.map(j => {
                  const lr = j.lastRun;
                  const lastRun = lr
                    ? `<span class="badge ${lr.success ? 'good' : 'crit'}"><span class="d"></span>${lr.success ? 'OK' : 'FAIL'}</span> <span class="cmd">${fmtTime(lr.timestampMillis)}</span>`
                    : '<span class="cmd">never</span>';
                  return `<tr><td><code class="pill">${esc(j.id)}</code></td><td class="mono cmd">${esc(j.schedule)}</td><td class="cmd">${esc(j.command.join(' '))}</td>
                    <td>${j.owner ? `<span class="badge brand"><span class="d"></span>${esc(j.owner)}</span>` : '&ndash;'}</td>
                    <td>${j.enabled ? 'yes' : 'no'}</td><td class="cmd">${esc(j.overlapPolicy)}</td>
                    <td>${lastRun}</td>
                    <td><button class="ghost-danger" onclick="deleteJob('${esc(j.id)}')">delete</button></td></tr>`;
                }).join('') || '<tr><td colspan="8" class="empty">no jobs scheduled yet &mdash; add one below</td></tr>';
              }

              async function loadRuns() {
                lastRuns = await (await fetch('/api/runs')).json();
                document.querySelector('#runsTable tbody').innerHTML = lastRuns.slice(0, 40).map(r => `
                  <tr><td class="cmd">${fmtTime(r.timestampMillis)}</td><td><code class="pill">${esc(r.jobId)}</code></td><td>${esc(r.nodeId)}</td>
                  <td><span class="badge ${r.success ? 'good' : 'crit'}"><span class="d"></span>${r.success ? 'OK' : 'FAIL'}</span></td>
                  <td class="cmd">${r.exitCode ?? '&ndash;'}</td><td class="cmd">${r.durationMillis}ms</td></tr>
                `).join('') || '<tr><td colspan="6" class="empty">no runs recorded yet</td></tr>';
                renderKpis();
                renderSpark();
              }

              function loadAll() { loadStatus(); loadNodes(); loadJobs(); loadRuns(); }

              // ---- KPI tiles ----
              function renderKpis() {
                if (!lastStatus) return;
                const role = lastStatus.raft.role;
                const roleBadge = role === 'LEADER' ? 'brand' : (role === 'CANDIDATE' ? 'warn' : 'muted');
                const recent = lastRuns.slice(0, 50);
                const successRate = recent.length ? Math.round(100 * recent.filter(r => r.success).length / recent.length) : null;
                document.getElementById('kpiRow').innerHTML = `
                  <div class="kpi"><div class="k">Role</div><div class="v"><span class="badge ${roleBadge}"><span class="d"></span>${esc(role)}</span></div></div>
                  <div class="kpi"><div class="k">Term</div><div class="v">${lastStatus.raft.term}</div></div>
                  <div class="kpi"><div class="k">Leader</div><div class="v">${esc(lastStatus.raft.leaderId ?? '&ndash;')}</div></div>
                  <div class="kpi"><div class="k">Ring Nodes</div><div class="v">${lastStatus.ring.physicalNodeCount}</div></div>
                  <div class="kpi"><div class="k">Active Jobs</div><div class="v">${lastStatus.jobCount}</div></div>
                  <div class="kpi"><div class="k">Success Rate</div><div class="v">${successRate === null ? '&ndash;' : successRate + '%'}</div>
                    ${successRate === null ? '' : `<div class="meter"><span style="width:${successRate}%;background:var(--good)"></span><span style="width:${100 - successRate}%;background:var(--critical)"></span></div>`}
                  </div>
                `;
              }

              // ---- sparkline (recent run durations; failed runs marked) ----
              function renderSpark() {
                const svg = document.getElementById('sparkSvg');
                const W = 620, H = 110, PAD = 12;
                const data = lastRuns.slice(0, 40).slice().reverse();
                if (data.length < 2) {
                  svg.innerHTML = `<text x="${W / 2}" y="${H / 2}" fill="var(--text-muted)" font-size="12" text-anchor="middle">not enough runs yet</text>`;
                  return;
                }
                const maxDur = Math.max.apply(null, data.map(r => r.durationMillis).concat([1]));
                const stepX = (W - PAD * 2) / (data.length - 1);
                const points = data.map((r, i) => ({
                  x: PAD + i * stepX,
                  y: H - PAD - (r.durationMillis / maxDur) * (H - PAD * 2),
                  r: r
                }));
                const line = points.map((p, i) => (i === 0 ? 'M' : 'L') + p.x.toFixed(1) + ' ' + p.y.toFixed(1)).join(' ');
                const area = line + ` L ${points[points.length - 1].x.toFixed(1)} ${H - PAD} L ${points[0].x.toFixed(1)} ${H - PAD} Z`;
                let inner = `<line x1="${PAD}" y1="${H - PAD}" x2="${W - PAD}" y2="${H - PAD}" stroke="var(--gridline)" stroke-width="1"/>`;
                inner += `<path d="${area}" fill="var(--brand)" opacity="0.12"/>`;
                inner += `<path d="${line}" fill="none" stroke="var(--brand)" stroke-width="2" stroke-linejoin="round" stroke-linecap="round"/>`;
                for (const p of points) {
                  const ok = p.r.success;
                  inner += `<circle cx="${p.x.toFixed(1)}" cy="${p.y.toFixed(1)}" r="${ok ? 2.5 : 4.5}" fill="${ok ? 'var(--brand)' : 'var(--critical)'}" stroke="var(--surface-1)" stroke-width="2"><title>${esc(p.r.jobId)} on ${esc(p.r.nodeId)}: ${p.r.durationMillis}ms${ok ? '' : ' (failed)'}</title></circle>`;
                }
                svg.innerHTML = inner;
              }

              // ---- actions ----
              async function deleteJob(id) {
                await fetch('/api/jobs/' + encodeURIComponent(id), { method: 'DELETE' });
                loadJobs();
              }

              document.getElementById('addJobForm').addEventListener('submit', async e => {
                e.preventDefault();
                const f = new FormData(e.target);
                await fetch('/api/jobs', {
                  method: 'POST',
                  headers: { 'Content-Type': 'application/json' },
                  body: JSON.stringify({
                    id: f.get('id'), schedule: f.get('schedule'), command: f.get('command').split(' ').filter(Boolean),
                    enabled: true
                  })
                });
                e.target.reset();
                loadJobs();
              });

              // ---- live updates ----
              function connectEvents() {
                const es = new EventSource('/events');
                const conn = document.getElementById('connState');
                const dot = document.getElementById('connDot');
                es.onopen = () => { conn.textContent = 'live'; dot.classList.remove('warn'); };
                es.onerror = () => { conn.textContent = 'reconnecting&hellip;'; dot.classList.add('warn'); };
                es.onmessage = e => {
                  const msg = JSON.parse(e.data);
                  if (msg.type === 'membership') loadNodes();
                  else if (msg.type === 'ring') { loadStatus(); loadJobs(); }
                  else if (msg.type === 'raft') loadStatus();
                  else if (msg.type === 'job') loadJobs();
                  else if (msg.type === 'run') { loadRuns(); loadJobs(); }
                };
              }

              loadAll();
              connectEvents();
            </script>
            </body>
            </html>
            """;
}
