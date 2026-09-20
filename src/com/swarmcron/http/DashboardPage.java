package com.swarmcron.http;

/**
 * The dashboard: one static HTML file with inline CSS/JS, no external
 * scripts or stylesheets (consistent with the project's zero-dependency
 * constraint). Fetches its initial state from the JSON API on load, then
 * keeps itself current by re-fetching whichever section an SSE event on
 * /events says changed -- simpler and plenty fast at this data scale than
 * hand-rolling client-side diffing for a few small tables.
 */
final class DashboardPage {

    private DashboardPage() {}

    static final String HTML = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
            <meta charset="utf-8">
            <title>SwarmCron</title>
            <style>
              :root { color-scheme: dark; }
              body { font-family: -apple-system, Segoe UI, Helvetica, Arial, sans-serif; background: #0f1115; color: #e6e6e6; margin: 0; padding: 24px; }
              h1 { font-size: 20px; margin: 0 0 4px; }
              h2 { font-size: 15px; text-transform: uppercase; letter-spacing: 0.05em; color: #9aa4b2; margin: 28px 0 10px; }
              .sub { color: #9aa4b2; font-size: 13px; margin-bottom: 20px; }
              table { width: 100%; border-collapse: collapse; font-size: 13px; }
              th, td { text-align: left; padding: 6px 10px; border-bottom: 1px solid #262a33; }
              th { color: #9aa4b2; font-weight: 600; }
              tr:hover td { background: #171a21; }
              .badge { display: inline-block; padding: 2px 8px; border-radius: 10px; font-size: 12px; font-weight: 600; }
              .alive, .ok { background: #133a24; color: #4ade80; }
              .suspect { background: #3a2f13; color: #facc15; }
              .dead, .fail { background: #3a1313; color: #f87171; }
              .left { background: #22262f; color: #9aa4b2; }
              .leader { background: #132a3a; color: #60a5fa; }
              .degraded-banner { background: #3a1313; color: #f87171; padding: 8px 14px; border-radius: 6px; margin-bottom: 16px; font-weight: 600; display: none; }
              .panel { background: #161920; border: 1px solid #262a33; border-radius: 8px; padding: 16px; margin-bottom: 16px; }
              .row { display: flex; gap: 24px; flex-wrap: wrap; }
              .stat { min-width: 120px; }
              .stat .v { font-size: 22px; font-weight: 700; }
              .stat .k { color: #9aa4b2; font-size: 12px; }
              form.add-job { display: flex; gap: 8px; margin-top: 14px; flex-wrap: wrap; }
              form.add-job input { background: #0f1115; border: 1px solid #262a33; color: #e6e6e6; padding: 6px 8px; border-radius: 4px; font-size: 13px; }
              form.add-job input[name=id] { width: 120px; }
              form.add-job input[name=schedule] { width: 140px; }
              form.add-job input[name=command] { flex: 1; min-width: 200px; }
              button { background: #2563eb; color: white; border: none; padding: 6px 12px; border-radius: 4px; cursor: pointer; font-size: 13px; }
              button.danger { background: #7f1d1d; }
              button:hover { opacity: 0.9; }
              .muted { color: #9aa4b2; }
              code { background: #0f1115; padding: 1px 5px; border-radius: 3px; }
            </style>
            </head>
            <body>
              <h1>SwarmCron</h1>
              <div class="sub">node <code id="selfId">-</code> &middot; <span id="connState" class="muted">connecting...</span></div>
              <div id="degradedBanner" class="degraded-banner">DEGRADED -- this node cannot see a majority of the known cluster and will not execute jobs</div>

              <div class="panel row" id="statusPanel"></div>

              <h2>Cluster (<span id="nodeCount">0</span> nodes)</h2>
              <div class="panel"><table id="nodesTable"><thead><tr><th>Node</th><th>Address</th><th>State</th><th>Incarnation</th></tr></thead><tbody></tbody></table></div>

              <h2>Jobs</h2>
              <div class="panel">
                <table id="jobsTable"><thead><tr><th>ID</th><th>Schedule</th><th>Command</th><th>Owner</th><th>Enabled</th><th>Overlap</th><th>Last Run</th><th></th></tr></thead><tbody></tbody></table>
                <form class="add-job" id="addJobForm">
                  <input name="id" placeholder="job id" required>
                  <input name="schedule" placeholder="* * * * * *" required>
                  <input name="command" placeholder="/bin/echo hello" required>
                  <button type="submit">Add job</button>
                </form>
              </div>

              <h2>Recent Runs</h2>
              <div class="panel"><table id="runsTable"><thead><tr><th>Time</th><th>Job</th><th>Node</th><th>Result</th><th>Exit</th><th>Duration</th></tr></thead><tbody></tbody></table></div>

            <script>
              const esc = s => String(s).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
              const fmtTime = ms => ms ? new Date(ms).toLocaleTimeString() : '-';

              async function loadStatus() {
                const s = await (await fetch('/api/status')).json();
                document.getElementById('selfId').textContent = s.selfId;
                document.getElementById('degradedBanner').style.display = s.raft.degraded ? 'block' : 'none';
                document.getElementById('statusPanel').innerHTML = `
                  <div class="stat"><div class="v"><span class="badge ${s.raft.role === 'LEADER' ? 'leader' : 'ok'}">${esc(s.raft.role)}</span></div><div class="k">role</div></div>
                  <div class="stat"><div class="v">${esc(s.raft.term)}</div><div class="k">term</div></div>
                  <div class="stat"><div class="v">${esc(s.raft.leaderId ?? '-')}</div><div class="k">leader</div></div>
                  <div class="stat"><div class="v">${s.ring.physicalNodeCount}</div><div class="k">ring nodes</div></div>
                  <div class="stat"><div class="v">${s.jobCount}</div><div class="k">active jobs</div></div>
                `;
              }

              async function loadNodes() {
                const nodes = await (await fetch('/api/nodes')).json();
                document.getElementById('nodeCount').textContent = nodes.length;
                document.querySelector('#nodesTable tbody').innerHTML = nodes.map(n => `
                  <tr><td>${esc(n.nodeId)}</td><td class="muted">${esc(n.address)}</td>
                  <td><span class="badge ${n.state.toLowerCase()}">${esc(n.state)}</span></td>
                  <td>${n.incarnation}</td></tr>
                `).join('');
              }

              async function loadJobs() {
                const jobs = await (await fetch('/api/jobs')).json();
                document.querySelector('#jobsTable tbody').innerHTML = jobs.map(j => {
                  const lr = j.lastRun;
                  const lastRun = lr ? `<span class="badge ${lr.success ? 'ok' : 'fail'}">${lr.success ? 'OK' : 'FAIL'}</span> ${fmtTime(lr.timestampMillis)}` : '<span class="muted">never</span>';
                  return `<tr><td><code>${esc(j.id)}</code></td><td>${esc(j.schedule)}</td><td class="muted">${esc(j.command.join(' '))}</td>
                    <td>${esc(j.owner ?? '-')}</td><td>${j.enabled ? 'yes' : 'no'}</td><td>${esc(j.overlapPolicy)}</td>
                    <td>${lastRun}</td>
                    <td><button class="danger" onclick="deleteJob('${esc(j.id)}')">delete</button></td></tr>`;
                }).join('');
              }

              async function loadRuns() {
                const runs = await (await fetch('/api/runs')).json();
                document.querySelector('#runsTable tbody').innerHTML = runs.slice(0, 30).map(r => `
                  <tr><td>${fmtTime(r.timestampMillis)}</td><td><code>${esc(r.jobId)}</code></td><td>${esc(r.nodeId)}</td>
                  <td><span class="badge ${r.success ? 'ok' : 'fail'}">${r.success ? 'OK' : 'FAIL'}</span></td>
                  <td>${r.exitCode ?? '-'}</td><td>${r.durationMillis}ms</td></tr>
                `).join('');
              }

              function loadAll() { loadStatus(); loadNodes(); loadJobs(); loadRuns(); }

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

              function connectEvents() {
                const es = new EventSource('/events');
                const conn = document.getElementById('connState');
                es.onopen = () => conn.textContent = 'live';
                es.onerror = () => { conn.textContent = 'reconnecting...'; };
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
