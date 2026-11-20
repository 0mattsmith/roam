# The web interface

A toggle in Settings starts an HTTP server on the phone. Open it from a laptop
on the same wifi and you get Roam in a browser — the whole app, on a screen
that fits more than six rows and with a real keyboard: browse, search, play,
edit, and add music.

Visual reference: `docs/web-mockup.html`, which predates this and shows the
work queue as the front door (see "The landing page is the library").

## The app, on a big screen

Everything the phone does, the browser does: the library tabs, artist and
album pages, Loved, search (`docs/SEARCH.md`, the same page laid out wide),
playback, editing, artwork, adding music and the Adding list, Needs a look.
Where the phone stacks things, the web puts them side by side. It does not
leave things out.

**Except what belongs to the phone.** The Drive sign-in (Play Services'
`AuthorizationClient` only exists there), wifi-only transfers, the web
interface's own switch and PIN, battery settings. A browser cannot change how a
phone behaves, so those stay in the phone's Settings; the web can show them
where knowing them helps.

## Why this and not the desktop app

This is the desktop version of Roam now. Phase 6 planned a Compose
Multiplatform desktop app, and two desktop front ends would be daft — so this
takes its place for as long as the phone is what serves the library.

It is still the cheapest by a wide margin. No new platform, no second build, no
distribution, no install. The library, the editor rules and the Drive
credentials are all already on the phone — a browser is just a different way to
reach them.

What phase 6 would give that this cannot is independence from the phone: music
on the laptop with the phone flat, off, or on another network. If that starts
to matter, phase 6 comes back for exactly that and nothing else.

## What it is not

**Not remote access.** Local network only, and no port forwarding, no tunnel,
no dynamic DNS. If it ever needs to work from elsewhere, that is Tailscale's
job and not Roam's.

**Not a second implementation of Roam.** It has every feature and owns none of
the rules. Every write goes through the same code the phone uses. See below —
it is the single most important rule here, and it matters more now that the
web can do everything.

## Everything writes through the editors, never the DAOs

This is the rule the whole design hangs on.

`TrackEditor.apply` is not a wrapper around an UPDATE. It sets `userEdited` only
when the metadata actually moved, so a trim or a lyric fetch does not freeze a
track; it inserts parent artist and album rows before pointing at them, because
ids are content-derived and a rename is a *new row*; it wraps an album rename in
a transaction, because renaming moves every track to a new id at once and
half-way through is half an album; and it recomputes the rollups afterwards.

`ArtworkEditor` likewise never overwrites an image on Drive — it numbers the
outgoing one aside (invariant 6d), so every cover a folder has held is still
there.

And it no longer needs to be asked what the artwork *is*. `CoverApplier` reads
`cover.jpg` from the album folder on every crawl, so what the web UI shows is
the embedded frame or the file on Drive — whichever is current — rather than a
frozen copy of whatever was last uploaded. The editor's job ends at putting the
file there. That matters more here than on the phone: editing covers from a
laptop and having them not match is exactly the confusion this interface exists
to remove.

A web handler that reached for `TrackDao` directly would reimplement every one
of those bugs, and would do it in a place nobody thinks to look. **The HTTP
layer's only job is to turn a request into an editor call and the result into
JSON.**

A whole app writes in more places than an editor does, and some of the phone's
writes are not behind an editor yet. The heart is `tracks.setLoved` called from
`LibraryViewModel`; Remove from library is `tracks.setHidden` and then a
recount. Neither is much code, which is exactly how two copies of it drift. The
fix is not to inject `TrackDao` into `:feature:webui` — invariant 12 stays
checkable by grep only while the web holds no DAO with writes in it — but to
give each of those writes one owner in `:data:catalog` that both front ends
call. Plays count the same way: the server decides whether a track the browser
left counts as a play with the same `PlayThreshold` the service uses.

One real mismatch to solve: `ArtworkEditor` takes an Android `Uri`, because
every caller so far has been a photo picker. A browser upload arrives as bytes.
Either stage them to a cache file and hand over its `Uri`, or add a
bytes-accepting overload — the second is cleaner and the first is one line.

## Security

An HTTP server on your home wifi that can rewrite your library is reachable by
every device on that network. Roam's story until now has been *there is no
credential and no server*; this is the first thing that genuinely reverses it.

**A PIN, shown in Settings, required once per browser.** Not because anyone is
attacking you — because "I opened it on my phone by accident and renamed an
album" is a real Tuesday. Stored as a cookie after one successful entry.

**Bound to the local interface, and it says the address.** Settings shows
`http://192.168.1.x:8080` and the PIN together, so there is nothing to work out.

**A foreground service, with the notification that implies.** Android kills a
background server, so the toggle's subtitle has to say that a notification is
the price of leaving it on. Off by default, and it stops when toggled off rather
than lingering.

**Every feature means every write.** From the web you can hide tracks, rename
albums and start downloads on the phone. Nothing it can do destroys anything —
Remove from library hides (invariant 3c) and artwork is archived, never
overwritten (6d) — so a PIN is still the right weight. The audio stream sits
behind the same PIN.

## The stages

Ordered so each one is worth having alone.

**1 — Browse, read only. Built.** `:feature:webui`: the server, the PIN, the
foreground service and the work queue below. Settings → Web interface has the
switch, the address and the PIN; `:app` starts the service from the setting so
no UI feature depends on another.

What is left is the one thing only a phone can answer: **does it survive the
screen going off?** Turn it on, leave it, come back in an hour and reload. If
it holds, stage two is worth building; if the phone kills it anyway, that is a
battery-optimisation exemption to ask for and the answer changes the shape of
everything after.

**2 — The library, like the app.** Artists, Albums, Songs and Loved, artist
and album pages, read only. The library becomes the front door and the work
queue moves to Needs a look (below).

**3 — Playback.** The player bar, streaming through the phone, play counts.
Below.

**4 — Search.** `docs/SEARCH.md`'s page, wide: your music on the left, the
catalogues on the right, one icon per row saying what you can do.

**5 — Edit metadata and artwork.** Track and album forms over `TrackEditor`,
with the same dirty-checking the phone does — Apply stays disabled until
something really moved, or every track you merely looked at gets marked
hand-edited. Covers upload through `ArtworkEditor`, which archives rather than
overwrites; drag a file onto an album.

**6 — Add music.** The + and the Adding list. Needs the acquire path in
`:data:acquire` (`docs/SEARCH.md`, stage 3), for the same reason the phone's
search page does: a feature module cannot hand it to anyone else.

**7 — Link a document by hand.** Below.

## Pinning it to a taskbar

There is a web app manifest and a set of icons, generated from the launcher
icon's own paths by `tools/webui-icons.py` — so the pinned app is the same mark
as the phone, not a lookalike. Chrome and Edge on the desktop will install any
page as a windowed app from their menu (Chrome: ⋯ → Cast, save and share →
Install page as app; Edge: ⋯ → Apps → Install this site as an app), and they
read the manifest for the name and the icon.

**There is no service worker, and there cannot be one.** Service workers need a
secure context, and this is plain HTTP on a LAN address — so
`navigator.serviceWorker` does not exist, and Chrome's automatic install prompt
never fires. That only costs offline caching, which would be meaningless here:
every number on the page is live from the phone, and the phone is the thing
that has to be reachable anyway.

If a true PWA ever matters, the route is HTTPS with a certificate the browser
already trusts, which in practice means Tailscale — the same answer this
document gives for remote access, and still not Roam's job.

## Linking `album.json` by hand

The part nothing else does, and the reason a web UI is the right home for it.

`DocApplier` matches a document to tracks on the `file` locator — never a title
or a track number, because those are exactly what an edit changes. That is
correct and it is also why matching fails when a folder was renamed on Drive, or
when an external tool wrote paths that do not agree with reality.

Today there is no way to say *this document belongs to this album*. With both
lists side by side on a big screen there obviously is.

The mechanism mostly exists: `DocApplier.apply(provider, found, force = true)`
already skips the `doc_revisions` cache. What manual linking adds is applying a
document to an album the matcher would not have chosen — so the association has
to be recorded somewhere, and `doc_revisions` is the table that already knows
which document went where.

Worth being careful: a forced link is a user decision and must outrank the
matcher permanently, or the next sync quietly undoes it.

## Playback

No longer last: a desktop version of a music player plays music.

The route is the one this section always named: an `<audio>` element pointed
at the phone, which answers HTTP range requests over
`SourceProvider.readRange`. Drive → phone → browser for every track — double
the bandwidth, over the phone's connection, with the phone awake as a proxy the
whole time. On home wifi that is affordable, and the player bar says plainly
that it is streaming through the phone.

**Not a Drive token in the browser.** Handing the page an access token would
let the laptop fetch from Drive directly and halve the traffic, but Roam's
token can write to Drive and this is plain HTTP on a shared network: anything
that can see the traffic could take it. The phone keeps the credentials; the
browser only ever sees audio.

- **The browser has its own queue.** It plays on the laptop and does not drive
  the phone's player. A remote control for the phone is a different feature;
  see Open questions.
- **Plays count.** When the browser leaves a track it reports how far it got,
  and the server applies `PlayThreshold` — half the track or four minutes,
  under thirty seconds nothing — so a week of laptop listening counts like a
  week in the car.
- **Trims hold.** There is no `ClippingConfiguration` in a browser, so the page
  seeks past `startMs` and stops at `endMs` itself. An unset end means the end
  of the file, as on the phone.
- **A format the browser cannot decode says so** on the row and in the bar,
  rather than playing silence.
- **The phone's cache could serve repeats** without touching Drive. Worth doing
  once the plain path works, not before.

## Shape

A new module, `:feature:webui`, depending on `:core:database` and
`:data:catalog` — and, as the stages land, `:data:acquire` for adding music and
`:feature:player` for `PlayThreshold`, which a UI feature may depend on. It is
a second front end over the same catalogue, so it must not reach into
`:feature:library`.

**NanoHTTPD rather than Ktor**, despite Ktor being the more natural fit for a
Kotlin coroutine codebase. This serves a JSON API and a handful of static files;
NanoHTTPD is about 50 KB and Ktor is several megabytes, and the APK is already
carrying a Python runtime and an FFmpeg build. Size is a documented problem here
in a way elegance is not.

The front end is plain HTML, CSS and JavaScript, served from assets. No build
step, no npm, nothing to keep current — a second toolchain in this repo would
need maintaining forever, and that still holds for a whole app. What does not
hold is the single JS file: a player, a search page and the editors are too
much for one. Split it into ES modules, one per screen — `<script
type="module">` needs no bundler, and `WebRoutes.mime` already serves `.js` as
JavaScript. Each new file goes into `WebRoutes.ASSETS`, and `check-deps.py`
keeps that list honest.

## The landing page is the library

This was settled the other way: the work queue as the front door, because the
phone already browses well and a laptop was for jobs the phone is bad at. With
the web as the desktop app, the front door is the library, as it is on the
phone. Stage one built the queue first, and it stays.

It moves to **Needs a look**, a tab in the nav with its count beside it.
"Everything with no year", "everything nothing has vouched for", "albums with
no cover" are still the best reasons to open a laptop, and every count is still
a query that exists. "Needs a look" is `isGuess` — not `userEdited`, not
`fromDoc`, and `tagState != OK` — the same rule Settings → Needs a look uses
today.

## Open questions

- **Does the server survive the screen going off** with a foreground service, on
  a phone with aggressive battery management? Stage one answers it, and it
  matters more than it did: an editor that dies when the phone sleeps is
  annoying, a player that does is broken.
- **What happens to an edit made while a sync is running.** The phone has the
  same question today and answers it by accident; two clients make it worth
  deciding on purpose.
- **Does the web also control the phone's playback?** It plays on the laptop.
  Driving the phone's player from the laptop is a remote control, a different
  feature — but it decides what the player bar's buttons mean, so it wants an
  answer before stage 3.
- **Phase 6 in CLAUDE.md** still describes a Compose Multiplatform desktop app.
  If this is the desktop version, that phase shrinks to what this cannot do:
  play without the phone.
