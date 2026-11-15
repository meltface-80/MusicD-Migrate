package com.musicd.migrate

/**
 * What the destination already has.
 *
 * Indexed by ISRC and by a canonical "title|artist" key, because the same
 * recording carries different ids on the two services and the id is the one
 * thing that cannot be compared. Only the kinds actually being migrated are
 * read — a playlists-only run has no reason to page someone's 4,000 saved
 * tracks. Read once, up front, which is most of why a second run is fast.
 */
internal class Existing {
    val trackIsrc = HashSet<String>()
    val trackKey = HashSet<String>()
    val albumUpc = HashSet<String>()
    val albumKey = HashSet<String>()
    val artistKey = HashSet<String>()

    companion object {
        fun read(target: MusicTarget, options: MigrationOptions,
                 checkCancelled: () -> Unit): Existing {
            val out = Existing()
            if (options.doTracks) {
                for (t in target.savedTracks()) {
                    if (t.isrc.isNotEmpty()) out.trackIsrc.add(t.isrc.uppercase())
                    out.trackKey.add(Migration.trackKey(t))
                }
            }
            checkCancelled()
            if (options.doAlbums) {
                for (a in target.savedAlbums()) {
                    if (a.upc.isNotEmpty()) out.albumUpc.add(Migration.digits(a.upc))
                    out.albumKey.add(Migration.albumKey(a))
                }
            }
            checkCancelled()
            if (options.doArtists) {
                for (a in target.followedArtists()) out.artistKey.add(Canon.canon(a.name))
            }
            return out
        }
    }
}
