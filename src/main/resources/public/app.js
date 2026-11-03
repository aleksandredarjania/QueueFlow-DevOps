const $ = (id) => document.getElementById(id);
let cachedTickets = [];
let toastTimer;

async function api(path, options = {}) {
  const response = await fetch(path, options);
  const data = await response.json();
  if (!response.ok) throw new Error(data.error || `HTTP ${response.status}`);
  return data;
}

function notify(message) {
  const toast = $('toast');
  toast.textContent = message;
  toast.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => toast.classList.remove('show'), 3200);
}

function displayTickets() {
  const filter = $('status-filter').value;
  const rows = $('ticket-rows');
  rows.replaceChildren();
  const visible = cachedTickets.filter((ticket) => filter === 'ALL' || ticket.status === filter).reverse();
  if (visible.length === 0) {
    const row = rows.insertRow();
    const cell = row.insertCell();
    cell.colSpan = 4;
    cell.className = 'empty';
    cell.textContent = filter === 'ALL' ? 'No tickets yet. Add your first visitor above.' : 'No tickets match this filter.';
    return;
  }
  for (const ticket of visible) {
    const row = rows.insertRow();
    for (const value of [`#${ticket.number}`, ticket.customer, ticket.service]) {
      row.insertCell().textContent = value;
    }
    const badge = document.createElement('span');
    badge.className = `pill ${ticket.status}`;
    badge.textContent = ticket.status === 'DONE' ? 'Completed' : ticket.status === 'SERVING' ? 'In service' : 'Waiting';
    row.insertCell().append(badge);
  }
}

async function refresh() {
  try {
    const [stats, tickets, health] = await Promise.all([
      api('/api/stats'), api('/api/tickets'), api('/api/health'),
    ]);
    cachedTickets = tickets;
    $('waiting-count').textContent = stats.waiting;
    $('serving-count').textContent = stats.serving;
    $('done-count').textContent = stats.done;
    $('total-count').textContent = tickets.length;
    $('server-status').textContent = health.status === 'ok' ? 'System operational' : 'Service unavailable';
    const serving = tickets.find((ticket) => ticket.status === 'SERVING');
    $('active-number').textContent = serving ? `#${serving.number}` : '—';
    $('active-name').textContent = serving ? serving.customer : 'No active ticket';
    $('active-service').textContent = serving ? serving.service : 'Call the next visitor to begin';
    $('next-button').disabled = Boolean(serving) || stats.waiting === 0;
    $('finish-button').disabled = !serving;
    displayTickets();
  } catch (error) {
    $('server-status').textContent = 'Service unavailable';
    console.error('Refresh failed:', error.message);
  }
}

$('ticket-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  const form = event.currentTarget;
  try {
    const data = await api('/api/tickets', { method: 'POST', body: new URLSearchParams(new FormData(form)) });
    form.reset();
    notify(`Ticket #${data.number} created successfully`);
    await refresh();
  } catch (error) { notify(error.message); }
});

for (const [button, endpoint, action] of [
  ['next-button', '/api/next', 'Called'],
  ['finish-button', '/api/finish', 'Completed'],
]) {
  $(button).addEventListener('click', async () => {
    try {
      const ticket = await api(endpoint, { method: 'POST' });
      notify(`${action} ticket #${ticket.number}`);
      await refresh();
    } catch (error) { notify(error.message); }
  });
}

$('status-filter').addEventListener('change', displayTickets);
refresh();
setInterval(refresh, 8000);
