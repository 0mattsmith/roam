# Even out volume

Every track at one level, so a quiet 1970s master is not drowned out by the
loud one after it — and any track that still sounds wrong can be turned up or
down on its own.

- **Settings → Playback → Even out volume.** On by default.
- **Edit details → Advanced options → Volume**, per track: −100% to +100%,
  default 0%.

Both live in the playback service, so the phone, the car and anything else
connected to the session hear the same thing.

## What "the same volume" means

Integrated loudness, as ITU-R BS.1770-4 and EBU R128 define it, in LUFS — the
number Spotify, YouTube, Tidal and Apple Music level by. Not peak level: a
heavily compressed track and a dynamic one can peak at the same place and
sound nothing alike.

Every track is brought to **−14 LUFS** (`Leveller.TARGET_LUFS`), where the
streaming services settle. Most music made since the late 1990s is louder than
that and is lowered to meet it, which costs nothing. Quieter music is lifted,
at most **+9 dB** (`MAX_LIFT_DB`): past that the limiter is doing the work and
a quiet recording becomes a squashed one.

`LoudnessMeter` is tested against the standard's own signals — EBU Tech 3341
cases 1 to 4, and BS.1770's calibration tone (full-scale 997 Hz in one channel
reads −3.01 LUFS) — to its ±0.1 LU tolerance, at 44.1 and 48 kHz, 16-bit and
float. A mono file counts twice (the standard's dual-mono weighting), because
it plays out of both speakers.

## Where the loudness comes from

**From listening.** `MeteringAudioProcessor` sits in ExoPlayer's audio chain
and hands everything that plays to the meter, unchanged. When a track ends, its
loudness is kept against its row, once (`setLoudnessIfMissing`). Nothing is
downloaded to measure anything — only what is played is heard, which is
invariant 4's reasoning applied to audio.

- **Most of the track, or nothing.** A measurement is kept when three quarters
  of the track was heard, or four minutes of a long one (`worthKeeping`). A
  song skipped after its quiet intro measured its intro, and would be lifted
  for good.
- **Until then, its album.** An unmeasured track takes the average of its
  album's measured tracks (`levelFor`). An album is mastered as a whole, so
  after the first track has played through, the rest are levelled before they
  have been heard themselves.
- **Neither, and it plays as it is.** A track from an album nobody has played
  through yet is not levelled on its first play. That is the cost of measuring
  by listening, and stages 2 and 3 below are how it shrinks.
- **Measured whether the setting is on or not**, so turning it on later has a
  library's worth of numbers to work with.
- **It belongs to the file.** Sync clears it when a file's bytes change, and a
  file that only moves keeps it (`updateFileFacts`).

## Where the gain goes, and why there

Lowering goes through the player's own volume, which is the AudioTrack's.
Lifting goes through a `LoudnessEnhancer` on the player's audio session, which
runs in the mixer and carries its own limiter. Both act on the audio as it is
**heard**, so a gain set at `onMediaItemTransition` lands where the new track
begins.

The obvious place — a gain `AudioProcessor` beside the meter — is the wrong
one. The processor runs before the AudioTrack's buffer and meets the next
track up to a second before anyone hears it, so a gain switched there opens
every track at the previous track's level. The meter can live with that: half a
second of a neighbour in four minutes of song moves nothing. A gain cannot.

Some devices will not give an app a `LoudnessEnhancer` — another app holding
the session's effects, or a vendor build without it. Then quiet tracks are not
lifted, and loud ones are still lowered.

## A track's own adjustment

A percentage of the track's **level**, not of its loudness: the factor is
1 + p/100, so +100% doubles the amplitude (+6 dB), −50% halves it (−6 dB) and
−100% is silence (`TrackVolume`). Stepped in fives.

- **On top of levelling**, for the track that still sounds wrong after it, and
  **on its own when levelling is off** — it is a setting somebody chose for
  that track.
- Total lift, the adjustment included, is capped at **+15 dB**
  (`MAX_TOTAL_LIFT_DB`).
- **User state**, like the trim points: written by the editor without marking
  the track edited, and never by sync or the tag pass (invariant 3).
- Heard immediately: the leveller watches the playing track's row, so moving
  the slider while the song plays changes it then.

## The stages

1. **Measure by listening, and level. Built.** The meter, the album stand-in,
   the gain at each track change, the per-track adjustment, the setting.
2. **ReplayGain tags.** `REPLAYGAIN_TRACK_GAIN` from ID3 `TXXX`, the MP4
   freeform atom and Vorbis comments, and Opus's `R128_TRACK_GAIN`, read by
   `TagParser` — so a library tagged by foobar2000, Picard or beets levels from
   its first play. Needs a re-read of files already read, because the tag pass
   only visits new ones.
3. **The downloader measures what it adds.** FFmpeg is already on the phone for
   remuxing; its `ebur128` filter can measure a file before it is uploaded, and
   the answer is written into it as ReplayGain tags. Music Roam adds then
   levels on its first play, in Roam and in every other player.
4. **`album.json` carries `volume`**, like `start_at` and `end_at`, so an
   adjustment survives a reinstall.
5. **Album mode.** One gain for a whole album, so its quiet ballad stays
   quieter than its single, the way it was mastered.

## Open questions

- **−14 LUFS, or louder?** Louder means fewer tracks lowered and more lifted,
  and lifting is the half that costs something.
- **Is +9 dB enough lift** for the quietest things in the library?
- **Should album mode be the default** once it exists? It is what most people
  mean when they say an album sounds right.
