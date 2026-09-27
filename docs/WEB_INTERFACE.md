# The web interface

A toggle in Settings starts an HTTP server on the phone. Open it from a laptop
on the same wifi and you get Roam's library in a browser — to **edit**, with a
real keyboard and a screen that fits more than six rows.

## Why this and not the desktop app

Three things solve "I want to edit my metadata on a big screen": this, the
Compose Multiplatform desktop app in phase 6, and a Roam server in a container.
All three are real, and building more than one would be daft.

This is the cheapest by a wide margin. No new platform, no second build, no
distribution, no install. The library, the editor rules and the Drive
credentials are all already on the phone — a browser is just a different way to
reach them. The desktop app is worth building when the phone and the car are
finished and the shared modules have stopped moving; that is what phase 6 says,
and it is still right.

So this is not a step towards the desktop app. It is the thing that makes
waiting for it painless.

## What it is not

**Not remote access.** Local network only, and no port forwarding, no tunnel,
no dynamic DNS. If it ever needs to work from elsewhere, that is Tailscale's
job and not Roam's.

**Not a second implementation of Roam.** Every write goes through the same
`TrackEditor` and `ArtworkEditor` the phone uses. See below — it is the single
most important rule here.

**Not a player, to begin with.** See "Playback, last".

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

A web handler that reached for `TrackDao` directly would reimplement every one
of those bugs, and would do it in a place nobody thinks to look. **The HTTP
layer's only job is to turn a request into an editor call and the result into
JSON.**

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

## The stages

Ordered so each one is worth having alone.

**1 — Browse, read only.** The server, the PIN, the foreground service, and a
library view. Proves the lifecycle question (does it survive the screen going
off?) before anything depends on it.

**2 — Edit metadata.** The actual point. Track and album forms over
`TrackEditor`, with the same dirty-checking the phone does — Apply stays
disabled until something really moved, or every track you merely looked at gets
marked hand-edited.

**3 — Cover art.** Upload through `ArtworkEditor`, which archives rather than
overwrites. Drag a file onto an album.

**4 — Link a document by hand.** Below.

**5 — Playback, if ever.** Below.

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

## Playback, last

You asked for playable tracks, and it should be built last anyway.

Serving audio means Drive → phone → browser for every track. Double the
bandwidth, over the phone's connection, with the phone awake as a proxy the
whole time. It is the most expensive part to build and the least connected to
why the interface is wanted: editing metadata with a keyboard works perfectly
with no audio at all.

When it does land, it is HTTP range requests over `SourceProvider.readRange`,
and it should say plainly that it is streaming through the phone.

## Shape

A new module, `:feature:webui`, depending on `:core:database` and
`:data:catalog` and nothing else — it is a second front end over the same
catalogue, so it must not reach into `:feature:library`.

**NanoHTTPD rather than Ktor**, despite Ktor being the more natural fit for a
Kotlin coroutine codebase. This serves a JSON API and a handful of static files;
NanoHTTPD is about 50 KB and Ktor is several megabytes, and the APK is already
carrying a Python runtime and an FFmpeg build. Size is a documented problem here
in a way elegance is not.

The front end is plain HTML, CSS and a single JS file, served from assets. No
build step, no npm, nothing to keep current. A metadata form does not need a
framework, and a second toolchain in this repo would need maintaining forever.

## Open questions

- **Does the server survive the screen going off** with a foreground service, on
  a phone with aggressive battery management? Stage one answers it, and the
  answer decides whether this is pleasant or infuriating.
- **Does editing want a track list or a work queue?** "Show me everything with
  no year" is probably more useful than browsing artists — the phone already
  does browsing well, and this exists for the jobs the phone is bad at.
- **What happens to an edit made while a sync is running.** The phone has the
  same question today and answers it by accident; two clients make it worth
  deciding on purpose.
