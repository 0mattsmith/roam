/*
 * The whole front end. No framework, no build step, nothing to keep current --
 * a work queue and a table do not need one, and a second toolchain in this
 * repo would need maintaining forever.
 *
 * The COPY lives here rather than in the API, so changing a word does not mean
 * recompiling an Android module. The server sends counts and rows; what the
 * jobs are called and why they matter is the front end's business.
 */
'use strict';

var JOBS = [
  {
    slug: 'needs-look', label: 'Needs a look', tone: 'hot',
    why: 'No edit, no album.json and no readable tags — nothing has vouched for these values.'
  },
  {
    slug: 'no-year', label: 'No year', tone: '',
    why: 'Blocks the decade shuffles: "Play 80s" cannot see them.'
  },
  {
    slug: 'no-genre', label: 'No genre', tone: '',
    why: 'Typed once per album here, not once per track on a phone.'
  },
  {
    slug: 'no-cover', label: 'Albums with no cover', tone: '',
    why: 'A cover.jpg in the album folder is what Roam reads, and album.json names which.'
  },
  {
    slug: 'frozen', label: 'Frozen on a guess', tone: 'warn',
    why: 'Hand-edited before the tags were ever read, so the tag pass will never touch them.'
  },
  {
    slug: 'missing', label: 'Missing from the source', tone: 'warn',
    why: 'Flagged, never deleted — the loved flag and play count are still here.'
  }
];

var el = function (id) { return document.getElementById(id); };
var state = { job: null, offset: 0, limit: 50, total: 0 };

/* ---------------------------------------------------------------- fetching */

/*
 * Every call goes through here, so the gate has exactly one trigger: a 401.
 * Checking for a cookie in JS would be guessing at what the server thinks.
 */
function api(path, options) {
  return fetch(path, options || {}).then(function (res) {
    if (res.status === 401) { gate(true); throw new Error('locked'); }
    return res.json().then(function (body) {
      if (!res.ok) throw new Error(body.error || res.statusText);
      // Symmetric with the 401 above, and not an afterthought: a call that
      // SUCCEEDS is the proof this browser is in. Taking the gate down only in
      // the PIN handler meant a browser that already had the cookie never went
      // through it -- the queue rendered into #app and #app was still hidden,
      // so every visit after the first was a blank page.
      gate(false);
      return body;
    });
  });
}

function gate(show) {
  el('gate').hidden = !show;
  el('app').hidden = show;
  if (show) el('pin').focus();
}

function failed(message) {
  var box = el('failed');
  if (!message) { box.hidden = true; return; }
  box.textContent = message;
  box.hidden = false;
}

/* ------------------------------------------------------------------ queue */

function showQueue(counts, library) {
  state.job = null;
  el('list').hidden = true;
  el('queue').hidden = false;
  el('empty').hidden = true;
  el('library').textContent = library.tracks.toLocaleString() + ' tracks';

  var queue = el('queue');
  queue.textContent = '';
  JOBS.forEach(function (job) {
    var n = counts[job.slug] || 0;
    var card = document.createElement('button');
    card.className = 'job ' + (n === 0 ? 'done' : job.tone);
    card.onclick = function () { openJob(job.slug, 0); };

    var num = document.createElement('div');
    num.className = 'num';
    num.textContent = n.toLocaleString();
    var lbl = document.createElement('div');
    lbl.className = 'lbl';
    lbl.textContent = job.label;
    var why = document.createElement('div');
    why.className = 'why';
    why.textContent = n === 0 ? 'Nothing outstanding.' : job.why;

    card.appendChild(num);
    card.appendChild(lbl);
    card.appendChild(why);
    queue.appendChild(card);
  });

  // Nav mirrors the cards, for getting between jobs without going back first.
  var nav = el('jobs');
  nav.textContent = '';
  JOBS.forEach(function (job) {
    var b = document.createElement('button');
    b.className = 'nav';
    b.textContent = job.label;
    b.onclick = function () { openJob(job.slug, 0); };
    nav.appendChild(b);
  });
}

function loadQueue() {
  failed(null);
  api('/api/counts').then(function (body) {
    showQueue(body.counts, body.library);
  }).catch(function (e) {
    if (e.message !== 'locked') failed('Could not reach Roam: ' + e.message);
  });
}

/* ------------------------------------------------------------------- rows */

function meta(job) {
  for (var i = 0; i < JOBS.length; i++) if (JOBS[i].slug === job) return JOBS[i];
  return { label: job, why: '' };
}

function openJob(slug, offset) {
  failed(null);
  state.job = slug;
  state.offset = offset;

  var url = '/api/job/' + slug + '?limit=' + state.limit + '&offset=' + offset;
  api(url).then(function (body) {
    state.total = body.total;
    el('queue').hidden = true;
    el('list').hidden = false;
    el('list-title').textContent = meta(slug).label;

    var shown = body.rows.length;
    el('list-count').textContent = shown === 0 ? 'nothing here'
      : (offset + 1) + '–' + (offset + shown) + ' of ' + body.total.toLocaleString();

    var rows = el('rows');
    rows.textContent = '';
    body.rows.forEach(function (row) {
      rows.appendChild(body.kind === 'albums' ? albumRow(row) : trackRow(row, slug));
    });
    el('empty').hidden = shown !== 0;

    // Only offered when there is somewhere to go. A disabled button that is
    // always visible is a worse answer than no button.
    el('prev').hidden = offset === 0;
    el('next').hidden = offset + shown >= body.total;

    Array.prototype.forEach.call(document.querySelectorAll('#jobs .nav'), function (b) {
      b.classList.toggle('on', b.textContent === meta(slug).label);
    });
  }).catch(function (e) {
    if (e.message !== 'locked') failed('Could not load that list: ' + e.message);
  });
}

function cover(artworkId) {
  var img = document.createElement('img');
  img.className = 'art';
  img.alt = '';
  // 320 is the thumb the store already wrote; the provider falls back to the
  // master if there isn't one, so a missing size is not a missing picture.
  if (artworkId) img.src = '/api/artwork/' + artworkId + '?size=320';
  return img;
}

function trackRow(row, slug) {
  var div = document.createElement('div');
  div.className = 'row';
  div.appendChild(cover(row.artworkId));

  var who = document.createElement('div');
  who.className = 'who';
  var t1 = document.createElement('div');
  t1.className = 't1';
  t1.textContent = row.title;
  var t2 = document.createElement('div');
  t2.className = 't2';
  t2.textContent = row.artist + ' · ' + row.album;
  who.appendChild(t1);
  who.appendChild(t2);
  div.appendChild(who);

  var gap = document.createElement('div');
  gap.className = 'gap';
  gap.textContent = whatIsMissing(row, slug);
  div.appendChild(gap);

  if (row.tagState !== 'OK') {
    var chip = document.createElement('span');
    chip.className = 'chip guess';
    chip.textContent = row.tagState === 'FAILED' ? 'failed'
      : row.tagState === 'PATH_INFERRED' ? 'filename' : 'pending';
    div.appendChild(chip);
  }
  return div;
}

/*
 * Says what is wrong with THIS row, not what the job is called.
 *
 * A track in "no year" usually has other gaps too, and seeing them is half
 * the reason to look at a list on a big screen -- fixing the year while the
 * form is open is free.
 */
function whatIsMissing(row, slug) {
  var gaps = [];
  if (!row.year) gaps.push('no year');
  if (!row.genre) gaps.push('no genre');
  if (slug === 'missing') gaps.push('file not found');
  if (row.tagState === 'PATH_INFERRED') gaps.push('title from the filename');
  return gaps.join(', ');
}

function albumRow(row) {
  var div = document.createElement('div');
  div.className = 'row';
  div.appendChild(cover(null));

  var who = document.createElement('div');
  who.className = 'who';
  var t1 = document.createElement('div');
  t1.className = 't1';
  t1.textContent = row.title;
  var t2 = document.createElement('div');
  t2.className = 't2';
  t2.textContent = row.artist + ' · ' + row.trackCount + ' tracks'
    + (row.year ? ' · ' + row.year : '');
  who.appendChild(t1);
  who.appendChild(t2);
  div.appendChild(who);

  var gap = document.createElement('div');
  gap.className = 'gap';
  // The folder is the actionable part: it is where a cover.jpg would go.
  gap.textContent = row.folderPath || 'no folder on record';
  div.appendChild(gap);
  return div;
}

/* ------------------------------------------------------------------ wiring */

el('pin-form').onsubmit = function (e) {
  e.preventDefault();
  el('pin-error').hidden = true;
  var body = new URLSearchParams();
  body.set('pin', el('pin').value);
  fetch('/api/pin', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: body.toString()
  }).then(function (res) {
    if (!res.ok) { el('pin-error').hidden = false; el('pin').value = ''; return; }
    gate(false);
    loadQueue();
  }).catch(function () { el('pin-error').hidden = false; });
};

el('back').onclick = loadQueue;
el('prev').onclick = function () { openJob(state.job, Math.max(0, state.offset - state.limit)); };
el('next').onclick = function () { openJob(state.job, state.offset + state.limit); };

loadQueue();
