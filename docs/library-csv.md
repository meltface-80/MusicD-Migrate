# Library CSV — the file Mandarin Migration imports

This is the format **Mandarin's library export should write**, and what
Mandarin Migration reads to add a local library's albums to Qobuz favourites.
Other software's exports work too — see [Other software](#other-software).

## The short version

One row per **track**, UTF-8, comma-separated, with a header row:

```csv
album_artist,album,disc,track,title,artist,barcode,isrc,duration,year
Metallica,Master Of Puppets,1,1,Battery,Metallica,075992736121,USEE10001992,312,1986
Metallica,Master Of Puppets,1,2,Master Of Puppets,Metallica,075992736121,USEE10001993,515,1986
Miles Davis,Kind Of Blue,1,1,So What,Miles Davis,,USSM15900113,562,1959
```

## Columns

| Column | Needed | What it is |
|---|---|---|
| `album` | **yes** | The album title as tagged. Edition tags like "(Remastered)" are fine. |
| `album_artist` | **yes**\* | The album's artist. For compilations, "Various Artists". |
| `artist` | \* | The track's artist. Used when `album_artist` is empty. |
| `title` | strongly recommended | The track title. Its presence is what makes the file "one row per track". |
| `disc` | recommended | Disc number (`1`, or `1/2`). Orders the track listing. |
| `track` | recommended | Track number (`3`, or `3/12`). Orders the track listing. |
| `barcode` | **strongly recommended** | The album's UPC/EAN as digits (`075992736121`). The same value on every track of the album. |
| `year` | optional | Release year (or a date starting with the year). |
| `isrc`, `duration` | optional | Reserved: not used to match albums today, but cheap to include and useful later. |

\* at least one of `album_artist` and `artist`.

Any other column is ignored, and the app lists the ignored columns after the
upload so nothing is silently misread.

## How it is matched, and why the columns matter

Rows are grouped into albums by album title and album artist. Each album is
then looked for on Qobuz under the app's usual rules:

1. **Barcode.** If the album has one and Qobuz has the same release, it is a
   match. This is the strongest evidence there is.
2. **Title, artist and track listing.** With no barcode, an album is accepted
   only if at least **70% of its tracks** (by title) are on the Qobuz album.
   "Greatest Hits" by almost anybody is several different records; two
   different records do not share eleven track titles.
3. Nothing else. An album with **no barcode and no track listing** (a file with
   one row per album and no barcode column) is not searched for at all. The
   report says why, so you can export again with more detail.

So: **include the barcode where the tags have one, and export one row per
track.** That is the difference between "found" and "could not tell".

## The details a writer should get right

- **Header row first.** Header names are matched case-insensitively, ignoring
  spaces, underscores and punctuation: `Album Artist`, `album_artist` and
  `ALBUMARTIST` are the same column.
- **Quote cells that contain the separator, a quote or a newline**, RFC 4180
  style: `"Live, at Last"`, and `""` for a quote inside a quoted cell.
- **Comma, semicolon or tab** separators all work; the header decides.
- **UTF-8.** A byte-order mark is fine.
- **Write the barcode as text.** If the file passes through a spreadsheet,
  `075992736121` can become `7.59927E+10`. The app ignores barcodes mangled like
  that and says how many it ignored.
- **Same album, same `album` and `album_artist` on every row.** A row that
  spells either differently becomes a separate album.
- **Keep library notes out of `album` if you can.** "(12 tracks)", "Disc 1",
  "[CD2]", "(1 of 2)", a trailing "(2003)" or "(2)" are recognised and left
  out when searching, and the discs of one set are then read as one album —
  but the export is cleaner without them, and the year has its own column.
- Size: tens of megabytes is fine (a ten-thousand-album library, one row per
  track, is about 20MB). The upload limit is 48MB and 400,000 rows.

## Other software

Common header names from other exporters are recognised:

| Field | Also accepted |
|---|---|
| album | `album title`, `release`, `release title` |
| album artist | `albumartist`, `album artists`, `release artist`, `band` |
| artist | `artists`, `artist name`, `track artist`, `performer` |
| title | `track title`, `track name`, `song`, `name` |
| track number | `track number`, `track no`, `track #`, `no`, `number` |
| disc | `disc number`, `disk`, `cd` |
| barcode | `upc`, `ean`, `gtin` |
| track count | `tracks`, `track count`, `total tracks` (one-row-per-album files) |
| year | `date`, `release date`, `original year` |

A bare `track` column is read as the **title** when nothing else names the
title, and as the track **number** when something does.

A file with **one row per album** (album, artist, barcode) is accepted too, but
only its albums with a barcode can be matched.
