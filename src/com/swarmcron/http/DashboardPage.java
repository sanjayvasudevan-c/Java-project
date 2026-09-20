package com.swarmcron.http;

/**
 * The dashboard: one static HTML file with inline CSS/JS, no external
 * scripts, stylesheets, icon fonts, or chart libraries (consistent with the
 * project's zero-dependency constraint). Presented as a floating macOS-style
 * "window" -- a chrome bar with traffic-light controls, frosted glass
 * panels (backdrop-filter blur+saturate) over a soft gradient backdrop,
 * generous rounded corners, and a sliding active-tab indicator -- over the
 * same sidebar-navigated app shell (Overview/Cluster/Jobs/Activity) as
 * before. Every color is still a deliberate, contrast-checked choice (see
 * the project's dataviz palette validator), not an ad hoc hex value; the
 * "recent run activity" panel is still a real inline-SVG sparkline.
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
                color-scheme: light;
                /* Frosted-glass "macOS window" theme: soft pastel backdrop, translucent
                   white panels, an Apple-blue accent. Status/accent colors are still
                   contrast-checked against their actual (near-white) surface -- see
                   DashboardPage's javadoc. */
                --surface-1: rgba(255,255,255,0.72);
                --surface-2: rgba(255,255,255,0.45);
                --surface-solid: #ffffff;
                --text-primary: #1d1d1f;
                --text-secondary: #6e6e73;
                --text-muted: #8a8a8e;
                --border: rgba(0,0,0,0.08);
                --gridline: rgba(0,0,0,0.06);
                --brand: #0A84FF;
                --brand-strong: #0066CC;
                --brand-dim: rgba(10,132,255,0.12);
                --good: #1F9D55;
                --good-dim: rgba(31,157,85,0.12);
                --warning: #B7791E;
                --warning-dim: rgba(183,121,30,0.12);
                --critical: #D70015;
                --critical-dim: rgba(215,0,21,0.10);
                --shadow-sm: 0 1px 1px rgba(0,0,0,0.03), 0 2px 6px rgba(0,0,0,0.04);
                --shadow-md: 0 2px 4px rgba(0,0,0,0.04), 0 12px 28px rgba(0,0,0,0.08);
                --shadow-lg: 0 20px 60px rgba(0,0,0,0.22), 0 2px 10px rgba(0,0,0,0.10);
                --ease: cubic-bezier(.16,1,.3,1);
              }
              * { box-sizing: border-box; }
              html, body { height: 100%; }
              body {
                margin: 0; font-family: -apple-system, BlinkMacSystemFont, "SF Pro Text", system-ui, "Segoe UI", sans-serif;
                color: var(--text-primary);
                display: flex; padding: 26px;
                overflow: hidden;
              }
              code, .mono { font-family: ui-monospace, "SF Mono", SFMono-Regular, Menlo, Consolas, monospace; }

              /* ---- soft animated gradient backdrop, visible through the glass ---- */
              .bg-mesh {
                position: fixed; inset: -10%; z-index: -1;
                background:
                  radial-gradient(circle at 18% 22%, rgba(255,110,110,0.30), transparent 42%),
                  radial-gradient(circle at 82% 15%, rgba(100,140,255,0.32), transparent 45%),
                  radial-gradient(circle at 30% 85%, rgba(255,196,90,0.28), transparent 45%),
                  radial-gradient(circle at 85% 80%, rgba(120,220,190,0.28), transparent 45%),
                  #eef0f3;
                filter: blur(40px) saturate(160%);
                animation: meshDrift 26s ease-in-out infinite alternate;
              }
              @keyframes meshDrift {
                0%   { transform: translate3d(0,0,0) scale(1); }
                100% { transform: translate3d(-1.5%, 1.5%, 0) scale(1.04); }
              }

              /* ---- the floating "window" ---- */
              .window {
                flex: 1; display: flex; flex-direction: column; min-width: 0;
                border-radius: 20px; overflow: hidden;
                background: var(--surface-1);
                backdrop-filter: blur(34px) saturate(190%);
                -webkit-backdrop-filter: blur(34px) saturate(190%);
                border: 1px solid rgba(255,255,255,0.55);
                box-shadow: var(--shadow-lg);
                animation: windowIn .6s var(--ease) both;
              }
              @keyframes windowIn { from { opacity: 0; transform: scale(.97) translateY(10px); } to { opacity: 1; transform: none; } }

              .chrome-bar {
                flex: none; height: 46px; display: flex; align-items: center; padding: 0 16px;
                position: relative; border-bottom: 1px solid transparent;
                transition: border-color .2s, box-shadow .2s, background .2s;
              }
              .chrome-bar.scrolled { border-bottom-color: var(--border); box-shadow: 0 1px 0 rgba(0,0,0,0.02); background: rgba(255,255,255,0.35); }
              .traffic-lights { display: flex; gap: 8px; }
              .traffic-lights .light {
                width: 12px; height: 12px; border-radius: 50%; position: relative;
                box-shadow: inset 0 0 0 0.5px rgba(0,0,0,0.12);
              }
              .traffic-lights .light svg { position:absolute; inset:0; margin:auto; width:7px; height:7px; opacity:0; transition: opacity .12s; }
              .traffic-lights:hover .light svg { opacity: 1; }
              .light.red { background: #FF5F57; } .light.red svg { color: #7d0000; }
              .light.yellow { background: #FEBC2E; } .light.yellow svg { color: #7a5200; }
              .light.green { background: #28C840; } .light.green svg { color: #0a4d16; }
              .chrome-title { position: absolute; left: 0; right: 0; text-align: center; font-size: 13px; font-weight: 600; color: var(--text-secondary); pointer-events: none; letter-spacing: -0.01em; }

              .app { flex: 1; display: flex; min-height: 0; }

              .sidebar { width: 226px; flex: none; padding: 14px 10px; display: flex; flex-direction: column; gap: 4px; overflow-y: auto; border-right: 1px solid var(--border); }
              .brand-row { display: flex; align-items: center; gap: 10px; padding: 6px 8px 16px; }
              .brand-row .mark { width: 26px; height: 26px; border-radius: 7px; background: linear-gradient(135deg, var(--brand), var(--brand-strong)); display: flex; align-items: center; justify-content: center; font-weight: 700; font-size: 12px; flex: none; color: #fff; box-shadow: 0 2px 6px rgba(10,132,255,0.35); }
              .brand-row .name { font-weight: 600; font-size: 14.5px; letter-spacing: -0.01em; }

              .node-pill { margin: 0 8px 16px; padding: 10px 12px; background: var(--surface-solid); border: 1px solid var(--border); border-radius: 12px; box-shadow: var(--shadow-sm); }
              .node-pill .label { font-size: 10px; color: var(--text-muted); text-transform: uppercase; letter-spacing: .06em; }
              .node-pill .id { font-size: 13.5px; font-weight: 700; margin-top: 3px; }
              .node-pill .conn { display: flex; align-items: center; gap: 6px; margin-top: 9px; font-size: 12px; color: var(--text-secondary); }
              .dot { width: 7px; height: 7px; border-radius: 50%; background: var(--good); box-shadow: 0 0 0 3px var(--good-dim); flex: none; animation: dotPulse 2.4s ease-in-out infinite; }
              .dot.warn { background: var(--warning); box-shadow: 0 0 0 3px var(--warning-dim); }
              @keyframes dotPulse { 0%,100% { opacity: 1; } 50% { opacity: .55; } }

              nav.tabs { position: relative; display: flex; flex-direction: column; gap: 2px; }
              #navIndicator { position: absolute; left: 0; right: 0; top: 0; height: 34px; background: var(--brand-dim); border-radius: 8px; transition: transform .28s var(--ease), height .28s var(--ease); z-index: 0; }
              nav.tabs button { all: unset; cursor: pointer; position: relative; z-index: 1; padding: 9px 10px; border-radius: 8px; font-size: 13.5px; color: var(--text-secondary); display: flex; align-items: center; gap: 10px; transition: color .15s; }
              nav.tabs button:hover { color: var(--text-primary); }
              nav.tabs button.active { color: var(--brand-strong); font-weight: 600; }
              nav.tabs button svg { width: 16px; height: 16px; flex: none; opacity: .85; }
              .sidebar-foot { margin-top: auto; padding: 10px 8px 2px; font-size: 11px; line-height: 1.5; color: var(--text-muted); }

              .main { flex: 1; min-width: 0; padding: 26px 34px 60px; overflow-y: auto; scroll-behavior: smooth; }
              .topbar { margin-bottom: 20px; }
              .topbar h1 { font-size: 22px; margin: 0; letter-spacing: -0.02em; color: var(--text-primary); font-weight: 700; }
              .topbar .desc { font-size: 12.5px; color: var(--text-muted); margin-top: 3px; }

              .banner { display: none; align-items: center; gap: 10px; background: var(--critical-dim); border: 1px solid rgba(215,0,21,0.25); color: var(--critical); padding: 10px 14px; border-radius: 12px; font-size: 13px; font-weight: 600; margin-bottom: 18px; animation: fadeSlideUp .4s var(--ease) both; }
              .banner.show { display: flex; }

              .kpi-row { display: grid; grid-template-columns: repeat(auto-fit, minmax(140px, 1fr)); gap: 12px; margin-bottom: 22px; }
              .kpi, .card {
                background: var(--surface-solid); border: 1px solid var(--border); box-shadow: var(--shadow-sm);
                transition: transform .18s var(--ease), box-shadow .18s var(--ease);
                animation: fadeSlideUp .5s var(--ease) both;
              }
              .kpi:hover, .card:hover { transform: translateY(-2px); box-shadow: var(--shadow-md); }
              .kpi-row .kpi:nth-child(1) { animation-delay: .02s; } .kpi-row .kpi:nth-child(2) { animation-delay: .06s; }
              .kpi-row .kpi:nth-child(3) { animation-delay: .10s; } .kpi-row .kpi:nth-child(4) { animation-delay: .14s; }
              .kpi-row .kpi:nth-child(5) { animation-delay: .18s; } .kpi-row .kpi:nth-child(6) { animation-delay: .22s; }
              .kpi { border-radius: 14px; padding: 14px 16px; }
              .kpi .k { font-size: 10px; text-transform: uppercase; letter-spacing: .06em; color: var(--text-muted); margin-bottom: 9px; }
              .kpi .v { font-size: 22px; font-weight: 700; letter-spacing: -0.02em; color: var(--text-primary); }

              .card { border-radius: 18px; padding: 18px 20px; margin-bottom: 18px; }
              .card h2 { font-size: 12px; text-transform: uppercase; letter-spacing: .06em; color: var(--text-muted); margin: 0 0 14px; font-weight: 600; }
              .card .empty { color: var(--text-muted); font-size: 13px; padding: 8px 2px; }

              @keyframes fadeSlideUp { from { opacity: 0; transform: translateY(12px); } to { opacity: 1; transform: none; } }

              table { width: 100%; border-collapse: collapse; font-size: 13px; }
              thead th { text-align: left; font-size: 10.5px; text-transform: uppercase; letter-spacing: .04em; color: var(--text-muted); font-weight: 600; padding: 0 10px 10px; border-bottom: 1px solid var(--gridline); }
              tbody td { padding: 10px 10px; border-bottom: 1px solid var(--gridline); vertical-align: middle; color: var(--text-primary); }
              tbody tr:last-child td { border-bottom: none; }
              tbody tr { transition: background .12s; }
              tbody tr:hover td { background: rgba(0,0,0,0.02); }
              .cmd { color: var(--text-secondary); }
              code.pill { background: rgba(0,0,0,0.045); padding: 2px 7px; border-radius: 5px; font-size: 12px; color: var(--text-primary); }

              .badge { display: inline-flex; align-items: center; gap: 6px; padding: 3px 9px 3px 7px; border-radius: 20px; font-size: 12px; font-weight: 600; white-space: nowrap; }
              .badge .d { width: 6px; height: 6px; border-radius: 50%; flex: none; }
              .badge.good { background: var(--good-dim); color: var(--good); } .badge.good .d { background: var(--good); }
              .badge.warn { background: var(--warning-dim); color: var(--warning); } .badge.warn .d { background: var(--warning); }
              .badge.crit { background: var(--critical-dim); color: var(--critical); } .badge.crit .d { background: var(--critical); }
              .badge.muted { background: rgba(0,0,0,0.05); color: var(--text-secondary); } .badge.muted .d { background: var(--text-muted); }
              .badge.brand { background: var(--brand-dim); color: var(--brand-strong); } .badge.brand .d { background: var(--brand); }

              .toolbar { display: flex; gap: 8px; margin-top: 16px; flex-wrap: wrap; border-top: 1px solid var(--gridline); padding-top: 16px; }
              .toolbar input { background: rgba(0,0,0,0.03); border: 1px solid var(--border); color: var(--text-primary); padding: 8px 10px; border-radius: 9px; font-size: 13px; transition: background .15s, border-color .15s; }
              .toolbar input::placeholder { color: var(--text-muted); }
              .toolbar input:focus { outline: none; background: var(--surface-solid); border-color: var(--brand); box-shadow: 0 0 0 3px var(--brand-dim); }
              .toolbar input[name=id] { width: 140px; }
              .toolbar input[name=schedule] { width: 150px; font-family: ui-monospace, monospace; }
              .toolbar input[name=command] { flex: 1; min-width: 220px; }
              button.primary { background: var(--brand); color: white; border: none; padding: 9px 18px; border-radius: 20px; font-size: 13px; font-weight: 600; cursor: pointer; box-shadow: 0 2px 8px rgba(10,132,255,0.35); transition: background .15s, transform .1s; }
              button.primary:hover { background: var(--brand-strong); }
              button.primary:active { transform: scale(.96); }
              button.ghost-danger { background: transparent; border: 1px solid rgba(215,0,21,0.3); color: var(--critical); padding: 5px 11px; border-radius: 14px; font-size: 12px; cursor: pointer; transition: background .15s, transform .1s; }
              button.ghost-danger:hover { background: var(--critical-dim); }
              button.ghost-danger:active { transform: scale(.94); }

              .meter { height: 6px; border-radius: 4px; background: var(--gridline); overflow: hidden; display: flex; margin-top: 9px; }
              .meter > span { height: 100%; transition: width .4s var(--ease); }

              .tab-panel { display: none; }
              .tab-panel.active { display: block; animation: fadeSlideUp .38s var(--ease) both; }

              .sparkline-wrap { display: flex; align-items: center; gap: 20px; flex-wrap: wrap; }
              svg.spark { display: block; max-width: 100%; }
              .spark-legend { display: flex; gap: 18px; font-size: 12px; color: var(--text-secondary); }
              .spark-legend .sw { display: inline-block; width: 9px; height: 9px; border-radius: 2px; margin-right: 6px; vertical-align: -1px; }

              @media (max-width: 780px) {
                body { padding: 0; }
                .window { border-radius: 0; }
                .sidebar { position: fixed; z-index: 5; height: 100%; top: 46px; background: var(--surface-solid); transform: translateX(-100%); transition: transform .2s; }
                .sidebar.open { transform: translateX(0); }
                .main { padding: 18px; }
              }
            </style>
            </head>
            <body>
            <div class="bg-mesh"></div>
            <div class="window">
              <div class="chrome-bar" id="chromeBar">
                <div class="traffic-lights">
                  <span class="light red"><svg viewBox="0 0 8 8" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"><path d="M1.5 1.5l5 5M6.5 1.5l-5 5"/></svg></span>
                  <span class="light yellow"><svg viewBox="0 0 8 8" fill="none" stroke="currentColor" stroke-width="1.3" stroke-linecap="round"><path d="M1.3 4h5.4"/></svg></span>
                  <span class="light green"><svg viewBox="0 0 8 8" fill="none" stroke="currentColor" stroke-width="1.1"><path d="M1 4.2L3 2h3v3L3.8 7 1 4.2z"/></svg></span>
                </div>
                <div class="chrome-title">SwarmCron</div>
              </div>

              <div class="app">
                <aside class="sidebar">
                  <div class="brand-row"><div class="mark">SC</div><div class="name">SwarmCron</div></div>
                  <div class="node-pill">
                    <div class="label">This node</div>
                    <div class="id mono" id="selfId">&ndash;</div>
                    <div class="conn"><span class="dot" id="connDot"></span><span id="connState">connecting&hellip;</span></div>
                  </div>
                  <nav class="tabs">
                    <div id="navIndicator"></div>
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

                <main class="main" id="mainScroll">
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
            </div>

            <script>
              const esc = s => String(s).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
              const fmtTime = ms => ms ? new Date(ms).toLocaleTimeString() : '&ndash;';
              const NODE_BADGE = { ALIVE: 'good', SUSPECT: 'warn', DEAD: 'crit', LEFT: 'muted' };
              let lastRuns = [];
              let lastStatus = null;

              // ---- window chrome: scroll-edge effect on the title bar (a la macOS toolbars) ----
              document.getElementById('mainScroll').addEventListener('scroll', e => {
                document.getElementById('chromeBar').classList.toggle('scrolled', e.target.scrollTop > 2);
              });

              // ---- tab navigation, with a sliding pill indicator ----
              function positionIndicator(btn) {
                const nav = document.querySelector('nav.tabs');
                const indicator = document.getElementById('navIndicator');
                const navBox = nav.getBoundingClientRect();
                const btnBox = btn.getBoundingClientRect();
                indicator.style.transform = `translateY(${btnBox.top - navBox.top}px)`;
                indicator.style.height = btnBox.height + 'px';
              }
              document.querySelectorAll('nav.tabs button').forEach(btn => {
                btn.addEventListener('click', () => {
                  document.querySelectorAll('nav.tabs button').forEach(b => b.classList.remove('active'));
                  document.querySelectorAll('.tab-panel').forEach(p => p.classList.remove('active'));
                  btn.classList.add('active');
                  document.getElementById('tab-' + btn.dataset.tab).classList.add('active');
                  document.getElementById('mainScroll').scrollTo({ top: 0, behavior: 'smooth' });
                  positionIndicator(btn);
                });
              });
              window.addEventListener('resize', () => positionIndicator(document.querySelector('nav.tabs button.active')));

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
                  inner += `<circle cx="${p.x.toFixed(1)}" cy="${p.y.toFixed(1)}" r="${ok ? 2.5 : 4.5}" fill="${ok ? 'var(--brand)' : 'var(--critical)'}" stroke="var(--surface-solid)" stroke-width="2"><title>${esc(p.r.jobId)} on ${esc(p.r.nodeId)}: ${p.r.durationMillis}ms${ok ? '' : ' (failed)'}</title></circle>`;
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
              positionIndicator(document.querySelector('nav.tabs button.active'));
            </script>
            </body>
            </html>
            """;
}
