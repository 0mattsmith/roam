package app.roam.feature.library

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import app.roam.core.database.TrackDao
import app.roam.core.datastore.SettingsRepository
import app.roam.core.model.Ids
import app.roam.data.acquire.AddLedger
import app.roam.data.acquire.AddState
import app.roam.data.acquire.AlbumPlacement
import app.roam.data.acquire.ClearedDownloads
import app.roam.data.acquire.DownloadRequest
import app.roam.data.acquire.DownloadStatus
import app.roam.data.acquire.DownloadWorker
import app.roam.data.acquire.Holdings
import app.roam.data.acquire.PendingDownload
import app.roam.data.acquire.TrackIdentifier
import app.roam.data.acquire.VideoIdentity
import app.roam.data.acquire.YoutubeResult
import app.roam.data.acquire.YoutubeSource
import app.roam.data.catalog.metadata.ArtistMatch
import app.roam.data.catalog.metadata.Discogs
import app.roam.data.catalog.metadata.MetadataSource
import app.roam.data.catalog.metadata.MusicBrainz
import app.roam.data.catalog.metadata.ReleaseMatch
import app.roam.data.catalog.metadata.ReleaseSource
import app.roam.data.catalog.metadata.ReleaseTrack
import app.roam.data.catalog.sync.SyncWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject

/** One track of a release, and the library row it already is, if it is one. */
data class AlbumTrackRow(
    val track: ReleaseTrack,
    /** Yours already: the track to play rather than a + to add. */
    val heldId: Long?,
) {
    val inLibrary: Boolean get() = heldId != null
}

/** The album sheet: what it is, and every track it should contain. */
data class AlbumUiState(
    val title: String,
    val artist: String,
    val year: Int? = null,
    val coverUrl: String? = null,
    val tracks: List<AlbumTrackRow> = emptyList(),
    /**
     * Every release the catalogues offered, not just the one being shown.
     *
     * The original, the remaster, the deluxe edition and three regional
     * pressings all exist under the same name with different tracklists, and
     * only the person looking at them knows which one they own.
     */
    val candidates: List<ReleaseMatch> = emptyList(),
    val selectedId: String? = null,
    /** Which catalogue answered. Discogs only appears once a token is set. */
    val source: MetadataSource = MetadataSource.MUSICBRAINZ,
    val sources: List<MetadataSource> = emptyList(),
    val loading: Boolean = false,
    val message: String? = null,
) {
    val missing: List<ReleaseTrack> get() = tracks.filterNot { it.inLibrary }.map { it.track }
    val heldCount: Int get() = tracks.count { it.inLibrary }

    /** The pressing on show. Two catalogues can issue one id, so the source counts too. */
    fun isSelected(match: ReleaseMatch): Boolean = match.id == selectedId && match.source == source
}

/** How a discography is ordered. */
enum class ReleaseSort(val label: String) {
    RELEASE_DATE_DESC("Newest first"),
    RELEASE_DATE_ASC("Oldest first"),
    POPULARITY_DESC("Most popular"),
    POPULARITY_ASC("Least popular"),
}

/** An artist's discography, as a catalogue lists it. */
data class ArtistUiState(
    val name: String,
    val detail: String? = null,
    val imageUrl: String? = null,
    /** Everything the catalogue returned, before sorting or filtering. */
    val allReleases: List<ReleaseMatch> = emptyList(),
    val sort: ReleaseSort = ReleaseSort.RELEASE_DATE_DESC,
    val includeCompilations: Boolean = true,
    val source: MetadataSource = MetadataSource.MUSICBRAINZ,
    val loading: Boolean = false,
    val message: String? = null,
) {
    /** True when the source can actually rank by popularity. Discogs only. */
    val supportsPopularity: Boolean get() = allReleases.any { it.popularity != null }

    val releases: List<ReleaseMatch>
        get() {
            val filtered =
                if (includeCompilations) allReleases
                else allReleases.filterNot { it.isCompilation }

            return when (sort) {
                ReleaseSort.RELEASE_DATE_DESC -> filtered.sortedByDescending { it.year ?: 0 }
                ReleaseSort.RELEASE_DATE_ASC ->
                    // Unknown years last rather than pretending they are ancient.
                    filtered.sortedBy { it.year ?: Int.MAX_VALUE }
                // Nulls last BOTH ways: no popularity data means unknown, not
                // unpopular, and burying a record at one end would be a claim
                // the source never made.
                ReleaseSort.POPULARITY_DESC ->
                    filtered.sortedByDescending { it.popularity ?: Int.MIN_VALUE }
                ReleaseSort.POPULARITY_ASC ->
                    filtered.sortedBy { it.popularity ?: Int.MAX_VALUE }
            }
        }
}

/**
 * One album as the catalogues know it: every pressing found under one title
 * and artist, shown as one row, and how much of it is yours already.
 *
 * Grouped because MusicBrainz answers "Definitely Maybe" with every country's
 * CD, cassette and reissue, and five identical rows read as a broken page.
 * The pressings are not lost: they are the album sheet's own list to choose
 * from, which is where telling them apart is the point.
 */
data class ReleaseGroup(
    /** The first is the one shown; the rest are offered when it is opened. */
    val releases: List<ReleaseMatch>,
    /** Tracks the library holds under this title and album artist. */
    val held: Int,
) {
    val shown: ReleaseMatch get() = releases.first()

    /** Two catalogues can issue the same numeric id, so the source is part of it. */
    val key: String get() = "${shown.source}/${shown.id}"

    /** Where it would file, which is also how its jobs are found again. */
    val placement: AlbumPlacement get() = AlbumPlacement.from(shown)
}

/**
 * The search page's second group: what the catalogues and YouTube offer for a
 * term, with what you already have left out.
 *
 * Three sections from three places, each drawn as it lands, so each has its
 * own flag rather than one spinner standing for all of them.
 */
data class CatalogueResults(
    /** The term these answer. Blank while there is none worth sending. */
    val query: String = "",
    val artists: List<ArtistMatch> = emptyList(),
    val searchingArtists: Boolean = false,
    val releases: List<ReleaseGroup> = emptyList(),
    val searchingReleases: Boolean = false,
    /** Narrows the album search to various-artists collections. */
    val compilationsOnly: Boolean = false,
    val songs: List<YoutubeResult> = emptyList(),
    val searchingSongs: Boolean = false,
    /** True while "Show more" waits on rows that are not ready yet. */
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    /**
     * Songs that YouTube's fuller answer showed to be yours, by video id.
     * Turned into Play where they stand rather than taken out: a row that
     * vanishes under a thumb is how the wrong one gets tapped.
     */
    val heldSongs: Map<String, Long> = emptyMap(),
    /** No catalogue could be asked at all -- usually no signal -- rather than none knowing the term. */
    val cataloguesUnreachable: Boolean = false,
    /** yt-dlp's own words, when the song search failed. */
    val songsError: String? = null,
) {
    val searching: Boolean get() = searchingArtists || searchingReleases || searchingSongs
    val isEmpty: Boolean get() = artists.isEmpty() && releases.isEmpty() && songs.isEmpty()
}

/**
 * Music you do not have, on the search page (docs/SEARCH.md, stage 4): the
 * second group, the + on each row and the state it turns into, the album and
 * artist sheets, the placement question and the Adding list.
 *
 * A ViewModel of its own beside [LibraryViewModel] rather than more of it.
 * Nothing here touches the library except to ask what it already holds, and
 * that question is [Holdings]'.
 *
 * Your own music answers from Room on every keystroke; this costs requests, so
 * it waits for the typing to stop and each source draws when it answers.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AddMusicViewModel @Inject constructor(
    private val app: Application,
    private val tracks: TrackDao,
    private val youtube: YoutubeSource,
    private val musicBrainz: MusicBrainz,
    private val discogs: Discogs,
    private val settings: SettingsRepository,
    private val identifier: TrackIdentifier,
    private val holdings: Holdings,
    private val cleared: ClearedDownloads,
) : ViewModel() {

    private val _results = MutableStateFlow(CatalogueResults())
    val results: StateFlow<CatalogueResults> = _results.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    /** One line for the snackbar: what a tap started, or why it could not. */
    val message: StateFlow<String?> = _message.asStateFlow()

    private val workManager: WorkManager get() = WorkManager.getInstance(app)

    // ---- the Adding list, and the state of every row ------------------------

    /**
     * Every add, straight from WorkManager, cleared ones included.
     *
     * No mirror of it is kept in Room. WorkManager already survives process
     * death and reboots, and a second copy of the same list is a second thing
     * that can be wrong -- so this reads its state rather than tracking it.
     */
    private val allDownloads: StateFlow<List<DownloadStatus>> =
        WorkManager.getInstance(app)
            .getWorkInfosByTagFlow(DownloadWorker.TAG_ALL)
            .map { infos -> infos.map { DownloadStatus.of(it) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The Adding list: every add, less the failures somebody has dismissed. */
    val downloads: StateFlow<List<DownloadStatus>> =
        combine(allDownloads, cleared.ids) { all, gone -> all.filterNot { it.id in gone } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Whether the sync that follows an add is still to run. */
    private val syncPending: Flow<Boolean> =
        WorkManager.getInstance(app)
            .getWorkInfosForUniqueWorkFlow(SyncWorker.NAME_ONESHOT)
            .map { infos -> infos.any { !it.state.isFinished } }
            .distinctUntilChanged()

    // Which adds sync has made rows of. Kept for the life of the screen rather
    // than of the job: "Clear finished" forgets jobs, and a row that turned
    // into Play must not turn back into a + for music you now own.
    private val resolved = mutableMapOf<String, Long>()
    private val resolvedAlbums = mutableMapOf<String, Long>()
    private var resolvedFor: Any? = null

    /**
     * Everything a row needs to draw its icon. Recomputed when a job moves,
     * when a sync starts or ends, and when the library gains rows -- the last
     * is what turns a teal ring into Play without anybody asking.
     */
    val ledger: StateFlow<AddLedger> =
        combine(
            downloads,
            syncPending,
            tracks.count().distinctUntilChanged(),
        ) { jobs, syncing, count -> Triple(jobs, syncing, count) }
            .mapLatest { (jobs, syncing, count) ->
                resolveLanded(jobs, syncing, count)
                AddLedger(jobs, resolved.toMap(), resolvedAlbums.toMap(), syncing)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AddLedger.EMPTY)

    /**
     * Looks for the rows that finished adds became.
     *
     * Only when something that could change the answer has: progress ticks
     * arrive every second while a track downloads, and asking Room the same
     * question each time would find the same nothing.
     */
    private suspend fun resolveLanded(jobs: List<DownloadStatus>, syncing: Boolean, count: Int) {
        fun unresolved(): List<DownloadStatus> = jobs.filter { job ->
            val url = job.request?.url
            job.succeeded && url != null && url !in resolved
        }

        val waiting = unresolved()
        if (Triple(waiting.map { it.id }.toSet(), syncing, count) == resolvedFor) return

        for (job in waiting) {
            val request = job.request ?: continue
            val id = holdings.resolveTrackId(request) ?: continue
            resolved[request.url] = id
            resolvedAlbums.putIfAbsent(AddLedger.albumKey(request), id)
        }
        resolvedFor = Triple(unresolved().map { it.id }.toSet(), syncing, count)
    }

    private val _addingOpen = MutableStateFlow(false)
    /** The Adding list is up. Here rather than in the composition, so a rotation keeps it. */
    val addingOpen: StateFlow<Boolean> = _addingOpen.asStateFlow()

    fun showAdding() {
        _addingOpen.value = true
    }

    fun hideAdding() {
        _addingOpen.value = false
    }

    private val _failure = MutableStateFlow<AddState.Failed?>(null)
    /** A red row that was tapped: its reason, with Retry and Clear. */
    val failure: StateFlow<AddState.Failed?> = _failure.asStateFlow()

    fun showFailure(state: AddState.Failed) {
        _failure.value = state
    }

    fun dismissFailure() {
        _failure.value = null
    }

    // ---- the search ---------------------------------------------------------

    /** The term the results answer, or are on their way to answering. */
    private var term = ""
    private var searchJob: Job? = null
    private var releaseJob: Job? = null

    /** The rest of the song search, sliced into batches as "Show more" asks. */
    private var remaining: List<YoutubeResult> = emptyList()

    /**
     * Albums already found to be wholly yours, by album key. Remembered for
     * the term, so a pressing from the second catalogue to answer does not
     * bring back an album the first one's answer took out.
     */
    private val heldReleases = mutableSetOf<String>()
    private val mergeLock = Mutex()

    /**
     * Called on every keystroke, as the library's own search is. What costs
     * a request waits for the typing to stop, and nothing is sent for a
     * single letter: that is every artist in both catalogues.
     */
    fun onQueryChanged(query: String) {
        val wanted = query.trim().takeIf { it.length >= MIN_REMOTE_CHARS }.orEmpty()
        if (wanted == term) return
        term = wanted
        searchJob?.cancel()
        releaseJob?.cancel()
        remaining = emptyList()
        heldReleases.clear()

        val compilations = _results.value.compilationsOnly
        if (wanted.isEmpty()) {
            _results.value = CatalogueResults(compilationsOnly = compilations)
            return
        }

        // Cleared now rather than after the wait. Rows answering the last
        // term under a new one are wrong, and the flags say what is coming.
        _results.value = CatalogueResults(
            query = wanted,
            compilationsOnly = compilations,
            searchingArtists = true,
            searchingReleases = true,
            searchingSongs = true,
        )

        searchJob = viewModelScope.launch {
            delay(REMOTE_DEBOUNCE_MS)
            launch { searchArtists(wanted) }
            releaseJob = launch { searchReleases(wanted) }
            searchSongs(wanted)
        }
    }

    /** Applies a change only while the results still answer [forTerm]. */
    private fun amend(forTerm: String, change: (CatalogueResults) -> CatalogueResults) {
        _results.update { if (it.query == forTerm) change(it) else it }
    }

    private suspend fun usableSources(): List<ReleaseSource> =
        listOf(discogs, musicBrainz).filter { it.available() }

    private suspend fun availableSources(): List<MetadataSource> = usableSources().map { it.source }

    private fun clientFor(source: MetadataSource): ReleaseSource = when (source) {
        MetadataSource.DISCOGS -> discogs
        MetadataSource.MUSICBRAINZ -> musicBrainz
    }

    /**
     * Artists from one catalogue, not both: they list the same acts, and two
     * of every name is noise. Discogs when it can be asked, for its photos.
     *
     * An act the library already lists is left out by name, every act of that
     * name with it. Three bands called Nirvana are three bands, but from here
     * they cannot be told from the one you have, and your own is in the first
     * group already.
     */
    private suspend fun searchArtists(term: String) {
        val client = usableSources().firstOrNull()
        val found = client?.searchArtists(term, limit = ARTISTS_ASKED)
        val artists = found?.getOrNull().orEmpty()
            .distinctBy { Ids.normalise(it.name) }
            .filterNot { holdings.hasArtist(it.name) }
            .take(ARTISTS_SHOWN)
        amend(term) { it.copy(artists = artists, searchingArtists = false) }
    }

    /**
     * Releases from every catalogue that can be asked, each drawn as it
     * answers. Discogs is quick; MusicBrainz queues behind its one-a-second
     * limit, and waiting for both would make the fast one as slow as the slow.
     *
     * Both are asked because they disagree usefully: Discogs knows the
     * pressings and the mixtapes, MusicBrainz has the canonical tracklists.
     */
    private suspend fun searchReleases(term: String) = coroutineScope {
        val compilations = _results.value.compilationsOnly
        val sources = usableSources()
        var failures = 0
        sources.map { client ->
            launch {
                val found = client.searchReleases(term, limit = RELEASES_ASKED, compilationsOnly = compilations)
                if (found.isFailure) failures++
                mergeReleases(term, found.getOrNull().orEmpty())
            }
        }.joinAll()
        amend(term) {
            it.copy(searchingReleases = false, cataloguesUnreachable = failures == sources.size)
        }
    }

    /**
     * Folds one catalogue's answer into what is on screen.
     *
     * Append-only. A pressing joins the album it belongs to, a new album goes
     * at the end, and nothing already drawn moves -- the second catalogue
     * answers a second or two after the first, and a list that rearranges
     * itself then is a list somebody mis-taps.
     *
     * An album is left out when the library holds at least as many of its
     * tracks as the release has (docs/SEARCH.md). With fewer it stays, and
     * says how many. Decided when the album first appears, never after: a
     * later pressing with a different count must not take a row away.
     */
    private suspend fun mergeReleases(term: String, found: List<ReleaseMatch>) {
        if (found.isEmpty()) return
        mergeLock.withLock {
            val groups = _results.value.takeIf { it.query == term }?.releases?.toMutableList()
                ?: return
            for (match in found) {
                val placement = AlbumPlacement.from(match)
                val key = AddLedger.albumKey(placement.album, placement.filingArtist)
                if (key in heldReleases) continue

                val at = groups.indexOfFirst {
                    AddLedger.albumKey(it.placement.album, it.placement.filingArtist) == key
                }
                if (at >= 0) {
                    groups[at] = groups[at].copy(releases = groups[at].releases + match)
                    continue
                }

                val held = holdings.heldCount(placement.album, placement.filingArtist)
                if (match.trackCount in 1..held) {
                    heldReleases += key
                    continue
                }
                groups += ReleaseGroup(listOf(match), held)
            }
            amend(term) { it.copy(releases = groups) }
        }
    }

    fun setCompilationsOnly(value: Boolean) {
        val current = _results.value.query
        releaseJob?.cancel()
        heldReleases.clear()
        _results.update {
            it.copy(
                compilationsOnly = value,
                releases = emptyList(),
                searchingReleases = current.isNotEmpty(),
                cataloguesUnreachable = false,
            )
        }
        if (current.isEmpty()) return
        releaseJob = viewModelScope.launch { searchReleases(current) }
    }

    private suspend fun searchSongs(term: String) {
        val found = youtube.search(term)
        val failure = found.exceptionOrNull()
        if (failure != null) {
            // The real message, not a polite summary of it. yt-dlp says
            // exactly what went wrong -- a missing binary reads very
            // differently from a blocked request -- and throwing that away
            // leaves nothing to act on.
            amend(term) { it.copy(searchingSongs = false, songsError = failure.readable()) }
            return
        }
        // Shown from the flat listing, immediately. The album and the year
        // arrive afterwards; a title, a channel and a duration are enough to
        // choose from.
        remaining = found.getOrDefault(emptyList())
        showNextBatch(term)
    }

    fun showMore() {
        val current = _results.value.query.ifEmpty { return }
        _results.update { it.copy(loadingMore = true) }
        viewModelScope.launch { showNextBatch(current) }
    }

    /**
     * The next screenful, less what the flat listing already shows you own.
     *
     * Filled up to a batch rather than cut at one, so a page of songs you
     * have does not arrive as an empty "Show more".
     */
    private suspend fun showNextBatch(term: String) {
        val batch = mutableListOf<YoutubeResult>()
        while (batch.size < BATCH && remaining.isNotEmpty()) {
            if (_results.value.query != term) return
            val next = remaining.first()
            remaining = remaining.drop(1)
            if (holdings.heldSong(next.title, next.artist, next.album) == null) batch += next
        }

        amend(term) {
            it.copy(
                songs = it.songs + batch,
                searchingSongs = false,
                loadingMore = false,
                hasMore = remaining.isNotEmpty(),
            )
        }

        // The album and the year, filled in under the rows already on screen.
        // NOT cancelled when the next page is asked for: each batch enriches
        // its own rows, and cancelling would leave whatever is above still
        // showing a channel name where an artist belongs.
        viewModelScope.launch { enrichInPlace(term, batch) }
    }

    /**
     * Replaces rows in place once the real extraction answers.
     *
     * Matched on the video id rather than on position, because "Show more"
     * can be pressed while this is in flight. A failure is silent on purpose:
     * the flat row is already drawn and perfectly usable.
     *
     * The fuller answer can show a song to be yours that the flat row did not
     * -- a channel called "Oasis - Topic" is not the artist Oasis -- and those
     * turn into Play where they are.
     */
    private suspend fun enrichInPlace(term: String, rows: List<YoutubeResult>) {
        if (rows.isEmpty()) return
        val better = youtube.enrich(rows.map { it.videoId }).getOrNull().orEmpty()
        if (better.isEmpty()) return

        val held = better.mapNotNull { row ->
            holdings.heldSong(row.title, row.artist, row.album)?.let { row.videoId to it }
        }.toMap()
        val byId = better.associateBy { it.videoId }
        amend(term) { current ->
            current.copy(
                songs = current.songs.map { byId[it.videoId] ?: it },
                heldSongs = current.heldSongs + held,
            )
        }
    }

    // ---- adding -------------------------------------------------------------

    /**
     * The lookup behind the last add. A newer one replaces it rather than
     * racing it -- otherwise two quick taps open two dialogs, in whichever
     * order MusicBrainz happens to answer.
     */
    private var lookup: Job? = null

    /**
     * An add waiting on somebody to confirm where it goes.
     *
     * The requests are built first and the placement is applied to them on
     * confirm, rather than the dialog handing back a lambda: this is state that
     * survives a rotation, and a lambda is not.
     */
    private val _pending = MutableStateFlow<PendingDownload?>(null)
    val pending: StateFlow<PendingDownload?> = _pending.asStateFlow()

    fun cancelPlacement() {
        _pending.value = null
    }

    /**
     * Applies the confirmed placement to every request and queues them.
     *
     * One answer for the whole batch, which is the point -- an album is one
     * album however many tracks it has, and asking per track would be forty
     * dialogs to say the same thing forty times.
     */
    fun confirmPlacement(placement: AlbumPlacement) = viewModelScope.launch {
        val waiting = _pending.value ?: return@launch
        _pending.value = null

        val wifiOnly = settings.settings.first().wifiOnlyForLargeTransfers

        // A BATCH is an album, and an album's per-track credits came from the
        // catalogue -- overwriting them with one typed name would flatten every
        // guest feature on the record. A single track is the opposite case: the
        // person is looking at one row and correcting the channel name YouTube
        // gave it, so what they typed is the better answer.
        val single = waiting.requests.size == 1

        waiting.requests.forEach { request ->
            DownloadWorker.enqueue(
                app,
                wifiOnly = wifiOnly,
                request = request.copy(
                    // The track keeps its own credit unless there is only
                    // one of them. On a compilation this difference is the
                    // whole feature: the folder is Various Artists and every
                    // track still says who actually performed it.
                    artist = if (single) {
                        placement.trackArtist(request.artist)
                    } else {
                        request.artist.ifBlank { placement.filingArtist }
                    },
                    // Per request, because a track with no album is a single
                    // named for ITSELF -- one name for a batch would file every
                    // one of them under the first one's title.
                    album = placement.albumFor(request.title),
                    albumArtist = placement.filingArtist,
                    year = placement.year,
                    compilation = placement.compilation,
                ),
            )
        }
        _message.value = if (waiting.requests.size > 1) {
            "Adding ${waiting.requests.size} tracks"
        } else {
            "Adding ${waiting.requests.firstOrNull()?.title.orEmpty()}"
        }
    }

    private fun ask(placement: AlbumPlacement, requests: List<DownloadRequest>, note: String? = null) {
        if (requests.isEmpty()) return
        _pending.value = PendingDownload(placement, requests, note)
    }

    /** The dialog, opened on whatever the lookup made of a video. */
    private fun offer(identity: VideoIdentity) {
        ask(identity.placement, listOf(identity.request), note = identity.note())
    }

    /**
     * One line saying where the answers came from. The dialog has no title
     * field -- the track's title is not a placement question -- so this is
     * also the only place the title that will be written is shown.
     */
    private fun VideoIdentity.note(): String = when (this) {
        is VideoIdentity.Known -> "Found on MusicBrainz: “${request.title}” by ${request.artist}"
        is VideoIdentity.Unknown -> {
            val why = if (reached) "Not found on MusicBrainz" else "MusicBrainz could not be reached"
            "$why, so this is the video's own title: “$title” by ${request.artist}. Worth a check."
        }
    }

    /**
     * A song's +: works out what the video is, then asks where it belongs.
     *
     * A search result is the weakest starting point there is -- a title the
     * uploader typed, a channel name where an artist belongs, nothing
     * resembling an album. None of that is written anywhere: it is put to
     * MusicBrainz, and the dialog opens on the catalogue's answer. See
     * [TrackIdentifier].
     *
     * No duration guard on what is queued: this is a real video id the person
     * picked off a result they could see, not a blind search.
     */
    fun add(result: YoutubeResult) {
        lookup?.cancel()
        lookup = viewModelScope.launch {
            _message.value = "Looking up ${result.title}…"
            offer(identifier.identify(result))
        }
    }

    /**
     * A release's +: its tracklist, then only what is missing.
     *
     * Asked of the catalogue now rather than when the row was drawn -- drawing
     * a row fetches no tracklist -- and put through the same question as the
     * album sheet's own button, so the two can never add different things.
     */
    fun addRelease(group: ReleaseGroup) {
        lookup?.cancel()
        lookup = viewModelScope.launch {
            val match = group.shown
            _message.value = "Looking up ${match.title}…"
            val album = buildAlbum(match, group.releases, availableSources())
            if (album.tracks.isEmpty()) {
                _message.value = "Could not load the tracklist for ${match.title}"
                return@launch
            }
            askForMissing(album)
        }
    }

    /** The URL a track from a tracklist is queued under, and found again by. */
    fun searchUrlFor(track: ReleaseTrack, albumArtist: String): String =
        "${DownloadWorker.SEARCH_PREFIX}${track.artist.ifBlank { albumArtist }} ${track.title}"

    /**
     * What is neither yours nor already on its way -- a failure counts as
     * not on its way, since adding it again is how it is retried.
     *
     * Adding tracks you already have would waste the connection and then
     * land a second copy beside the first under a slightly different name --
     * the filenames come from the catalogue, the originals from whatever
     * ripped them.
     */
    private fun askForMissing(album: AlbumUiState) {
        val states = ledger.value
        val wanted = album.missing.filter { track ->
            when (states.forUrl(searchUrlFor(track, album.artist))) {
                AddState.NotAdded, is AddState.Failed -> true
                else -> false
            }
        }
        if (wanted.isEmpty()) {
            _message.value = if (album.missing.isEmpty()) {
                "You already have all of ${album.title}"
            } else {
                "All of ${album.title} is on its way"
            }
            return
        }
        // Asked ONCE for the batch. An album is one album however many
        // tracks it has, and a dialog per track would be forty ways of saying
        // the same thing.
        ask(album.placement(), wanted.map { requestFor(it, album) })
    }

    /**
     * What the catalogue already answered, so the dialog is a confirmation.
     *
     * The selected release carries the artist, the year and the compilation
     * flag; falling back to the album's own fields covers a release that was
     * opened before the candidate list arrived.
     */
    private fun AlbumUiState.placement(): AlbumPlacement {
        val match = candidates.firstOrNull { isSelected(it) }
        if (match != null) return AlbumPlacement.from(match)
        return AlbumPlacement(
            artist = artist,
            album = title,
            albumArtist = artist,
            year = year,
        )
    }

    /**
     * One track off a tracklist, searched for by name and artist: the
     * catalogue knows what the track IS, YouTube has to be asked where it is.
     */
    private fun requestFor(track: ReleaseTrack, album: AlbumUiState) = DownloadRequest(
        url = searchUrlFor(track, album.artist),
        title = track.title,
        artist = track.artist.ifBlank { album.artist },
        album = album.title,
        trackNo = track.position,
        // The two things only the catalogue knows. The length is one of the
        // four checks a search result must pass (ExactTrack), and without the
        // cover the album lands on Drive with no art beside it.
        durationMs = track.durationMs,
        coverUrl = album.coverUrl,
    )

    /** One track's + on the album sheet. */
    fun addTrack(track: ReleaseTrack) {
        val album = _album.value ?: return
        ask(album.placement(), listOf(requestFor(track, album)))
    }

    /** The album sheet's "Add N missing". */
    fun addMissing() {
        askForMissing(_album.value ?: return)
    }

    /**
     * Queues a video shared into Roam from another app.
     *
     * The id is looked up before queueing rather than after, and then put to
     * MusicBrainz like any search result. YouTube's own title is often "Song
     * (Official Video)" with the channel as the artist, and what is written
     * decides the FOLDER the file lands in on Drive -- which Roam cannot
     * rename afterwards without leaving a copy behind.
     *
     * Refuses rather than guessing when the lookup fails: a track filed under
     * "Unknown Artist" is worse than one that was never added, since the
     * first has to be found and fixed by hand.
     */
    fun addSharedVideo(videoId: String) {
        lookup?.cancel()
        lookup = viewModelScope.launch {
            _message.value = "Reading shared video…"

            val result = youtube.enrich(listOf(videoId)).getOrNull()?.firstOrNull()
            if (result == null) {
                _message.value = "Could not read that video"
                return@launch
            }

            offer(identifier.identify(result))
        }
    }

    // ---- the album sheet ----------------------------------------------------

    private val _album = MutableStateFlow<AlbumUiState?>(null)
    val album: StateFlow<AlbumUiState?> = _album.asStateFlow()

    /**
     * The album sheet's own work. One at a time, and gone with the sheet, so
     * a tracklist that answers after the sheet was closed cannot reopen it.
     */
    private var albumJob: Job? = null

    private fun albumWork(block: suspend () -> Unit) {
        albumJob?.cancel()
        albumJob = viewModelScope.launch { block() }
    }

    /** A release row, opened: its tracklist, with every pressing found to choose from. */
    fun openRelease(group: ReleaseGroup) = openRelease(group.shown, group.releases)

    /** One of a discography's releases. The artist sheet waits underneath. */
    fun openArtistRelease(match: ReleaseMatch) = openRelease(match, listOf(match))

    private fun openRelease(match: ReleaseMatch, candidates: List<ReleaseMatch>) = albumWork {
        _album.value = AlbumUiState(title = match.title, artist = match.artist, loading = true)
        _album.value = buildAlbum(match, candidates, availableSources())
    }

    /**
     * Opens the album a song belongs to.
     *
     * The tracklist comes from the catalogues rather than from YouTube. YouTube
     * knows what it happens to be hosting; a catalogue knows what the album
     * actually contains -- which is the only way a missing track can show up
     * as missing rather than simply not existing.
     */
    fun viewAlbum(result: YoutubeResult) = albumWork {
        val albumName = result.album ?: return@albumWork
        _album.value = AlbumUiState(title = albumName, artist = result.artist, loading = true)
        val query = listOf(albumName, result.artist).filter { it.isNotBlank() }.joinToString(" ")
        // Discogs first when it can be used: it knows about pressings and
        // one-off variants MusicBrainz has never heard of, which is the whole
        // reason for having it. MusicBrainz needs no account, so it is always
        // there as the fallback.
        search(query, usableSources(), albumName, result.artist)
    }

    /** Re-runs the current album against a different catalogue. */
    fun selectSource(source: MetadataSource) = albumWork {
        val album = _album.value ?: return@albumWork
        _album.value = album.copy(loading = true)
        search(
            query = listOf(album.title, album.artist).filter { it.isNotBlank() }.joinToString(" "),
            sources = listOf(clientFor(source)),
            fallbackTitle = album.title,
            fallbackArtist = album.artist,
            offered = album.sources,
        )
    }

    /**
     * Switches to a different pressing without re-running the search. By the
     * pressing's own catalogue: a row's pressings can come from both.
     */
    fun selectRelease(match: ReleaseMatch) = albumWork {
        val album = _album.value ?: return@albumWork
        _album.value = album.copy(loading = true, selectedId = match.id, source = match.source)
        _album.value = buildAlbum(match, album.candidates, album.sources)
    }

    private suspend fun search(
        query: String,
        sources: List<ReleaseSource>,
        fallbackTitle: String,
        fallbackArtist: String,
        offered: List<MetadataSource>? = null,
    ) {
        val available = offered ?: availableSources()

        for (client in sources) {
            val candidates = client.searchReleases(query, limit = 8).getOrDefault(emptyList())
            if (candidates.isEmpty()) continue
            _album.value = buildAlbum(candidates.first(), candidates, available)
            return
        }

        _album.value = AlbumUiState(
            title = fallbackTitle,
            artist = fallbackArtist,
            sources = available,
            loading = false,
            message = "No release found for this album",
        )
    }

    private suspend fun buildAlbum(
        match: ReleaseMatch,
        candidates: List<ReleaseMatch>,
        available: List<MetadataSource>,
    ): AlbumUiState {
        val detail = clientFor(match.source).release(match.id).getOrNull()
        val title = detail?.title ?: match.title
        val artist = detail?.artist ?: match.artist

        return AlbumUiState(
            title = title,
            artist = artist,
            year = detail?.year ?: match.year,
            // The release lookup's own image wins: Discogs ships a proper cover
            // there, and a search thumbnail is 150px.
            coverUrl = detail?.coverUrl ?: match.coverUrl,
            tracks = markHeld(detail?.tracks.orEmpty(), title),
            candidates = candidates,
            selectedId = match.id,
            source = match.source,
            sources = available,
            loading = false,
            message = if (detail == null) "Could not load the tracklist" else null,
        )
    }

    /**
     * Works out which of these tracks are already in the library, and which
     * row each one is, so the sheet can play them.
     *
     * Matched on normalised title rather than on ids. The content-derived album
     * id would only match if the user's tags agree with the catalogue exactly,
     * and the whole reason someone opens this sheet is that they might not.
     */
    private suspend fun markHeld(release: List<ReleaseTrack>, albumTitle: String): List<AlbumTrackRow> {
        if (release.isEmpty()) return emptyList()
        val held = holdings.heldTracks(albumTitle)
        return release.map { AlbumTrackRow(it, heldId = held[Ids.normalise(it.title)]) }
    }

    fun closeAlbum() {
        albumJob?.cancel()
        _album.value = null
    }

    // ---- the artist sheet ---------------------------------------------------

    private val _artist = MutableStateFlow<ArtistUiState?>(null)
    val artist: StateFlow<ArtistUiState?> = _artist.asStateFlow()

    private var artistJob: Job? = null

    private fun artistWork(block: suspend () -> Unit) {
        artistJob?.cancel()
        artistJob = viewModelScope.launch { block() }
    }

    /**
     * Everything a catalogue lists under a song's artist.
     *
     * Looked up by name and the first match taken, because a YouTube result
     * carries a name and nothing else. That is occasionally the wrong Nirvana
     * -- the fix is the artists in the second group, where the disambiguation
     * is on screen and the choice is yours.
     */
    fun viewArtist(name: String) = artistWork {
        if (name.isBlank()) return@artistWork
        _artist.value = ArtistUiState(name = name, loading = true)

        val client = usableSources().firstOrNull() ?: return@artistWork
        val match = client.searchArtists(name, limit = 5).getOrDefault(emptyList()).firstOrNull()
        if (match == null) {
            _artist.value = ArtistUiState(name = name, loading = false, message = "No artist found")
            return@artistWork
        }
        loadDiscography(match)
    }

    /** An artist from the second group, already the one that was meant. */
    fun openCatalogueArtist(match: ArtistMatch) = artistWork {
        _artist.value = ArtistUiState(
            name = match.name,
            detail = match.detail,
            imageUrl = match.imageUrl,
            source = match.source,
            loading = true,
        )
        loadDiscography(match)
    }

    private suspend fun loadDiscography(match: ArtistMatch) {
        // Generous, because this is a discography rather than a search result
        // and "every album" is the point. The catalogues cap it anyway.
        val releases = clientFor(match.source).releasesForArtist(match.id, limit = 200)
            .getOrDefault(emptyList())

        _artist.value = ArtistUiState(
            name = match.name,
            detail = match.detail,
            imageUrl = match.imageUrl,
            allReleases = releases,
            source = match.source,
            loading = false,
            message = if (releases.isEmpty()) "Nothing listed for this artist" else null,
        )
    }

    fun setReleaseSort(sort: ReleaseSort) {
        _artist.value = _artist.value?.copy(sort = sort)
    }

    fun setIncludeCompilations(include: Boolean) {
        _artist.value = _artist.value?.copy(includeCompilations = include)
    }

    fun closeArtist() {
        artistJob?.cancel()
        _artist.value = null
    }

    // ---- the Adding list's own actions --------------------------------------

    /** Clears finished rows only; anything still queued or running stays. */
    fun clearFinished() {
        workManager.pruneWork()
    }

    /** Stops an add that has not finished. Its row stays, marked cancelled. */
    fun cancel(download: DownloadStatus) {
        workManager.cancelWorkById(UUID.fromString(download.id))
    }

    /**
     * Cancels and re-queues in one move.
     *
     * Cancel first, because the unique name is derived from the request and
     * KEEP would otherwise treat the new job as a duplicate of the old one and
     * quietly do nothing.
     */
    fun restart(download: DownloadStatus) = viewModelScope.launch {
        val request = download.request ?: return@launch
        workManager.cancelWorkById(UUID.fromString(download.id))
        DownloadWorker.enqueue(app, request, settings.settings.first().wifiOnlyForLargeTransfers)
        _message.value = "Restarted ${request.title}"
    }

    /**
     * Re-queues failures from the requests stored on their tags. Red turns
     * back into a ring: WorkManager drops the failed record when the same
     * request is queued again.
     */
    fun retry(jobs: List<DownloadStatus>) = viewModelScope.launch {
        val requests = jobs.mapNotNull { it.request }
        if (requests.isEmpty()) return@launch
        val wifiOnly = settings.settings.first().wifiOnlyForLargeTransfers
        requests.forEach { DownloadWorker.enqueue(app, it, wifiOnly) }
        _message.value = requests.singleOrNull()?.let { "Retrying ${it.title}" }
            ?: "Retrying ${requests.size} tracks"
    }

    /** Dismisses failures: their rows leave the Adding list, and red turns back into +. */
    fun clear(jobs: List<DownloadStatus>) {
        cleared.clear(jobs.map { it.id }, known = allDownloads.value.map { it.id })
    }

    /**
     * Finds an added track in the library.
     *
     * Null until sync has run: the file reaches Drive first and the row only
     * appears when the catalogue next crawls, so "added" and "in your library"
     * are minutes apart and the screen has to say so rather than opening
     * nothing.
     */
    suspend fun resolveTrackId(request: DownloadRequest): Long? = holdings.resolveTrackId(request)

    fun reportNotInLibrary() {
        _message.value = "Not in your library yet — it appears after the next sync"
    }

    /**
     * The tick: added and synced, and not found under the name it was added
     * as. Looks once more -- a sync may have caught up since -- and hands back
     * the row, or says why there is none.
     */
    suspend fun findAdded(jobs: List<DownloadStatus>): Long? {
        for (job in jobs) {
            if (!job.succeeded) continue
            val id = job.request?.let { holdings.resolveTrackId(it) }
            if (id != null) return id
        }
        _message.value = "It's on Drive, but its tags name it something else. Search your library for it."
        return null
    }

    /** yt-dlp goes stale and quietly stops returning results when it does. */
    fun updateYtDlp() = viewModelScope.launch {
        _message.value = "Updating yt-dlp…"
        val result = youtube.update()
        _message.value = result.exceptionOrNull()?.readable() ?: "yt-dlp updated"
    }

    fun clearMessage() {
        _message.value = null
    }

    /**
     * yt-dlp's own errors are several lines of Python traceback. The last
     * non-blank line is the part that says what happened; the rest is where.
     */
    private fun Throwable.readable(): String {
        // Walks the chain: the useful sentence is usually on a cause, and the
        // outer wrapper often has no message at all.
        val detail = generateSequence(this) { it.cause }
            .mapNotNull { it.message }
            .flatMap { it.lines() }
            .lastOrNull { it.isNotBlank() }
            ?.trim()
            .orEmpty()

        // Never the class name. Under R8 that is a renamed nothing -- "w5.e"
        // -- which reads like a real error and tells you less than silence.
        return detail.take(300).ifBlank { "yt-dlp failed without saying why" }
    }

    private companion object {
        /**
         * Longer than the library's own 150ms: MusicBrainz allows one request
         * a second and Discogs sixty a minute, so asking on every keystroke
         * is not an option.
         */
        const val REMOTE_DEBOUNCE_MS = 600L

        /** One letter is every artist in both catalogues; two starts to mean something. */
        const val MIN_REMOTE_CHARS = 2

        /** A screenful, and a batch small enough to arrive while it is read. */
        const val BATCH = 12

        /** Asked for more than are shown, since the ones you have are taken out. */
        const val ARTISTS_ASKED = 6
        const val ARTISTS_SHOWN = 3

        /** Per catalogue. Grouped into albums, this is rarely more than a dozen rows. */
        const val RELEASES_ASKED = 12
    }
}
