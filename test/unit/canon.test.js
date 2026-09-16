"use strict";
const test = require("node:test");
const assert = require("node:assert");
const { canon, stripVersion, primaryArtist, artistSet, splitArtists, similarity, overlap } =
  require("../../lib/canon");

test("canon folds case, accents, punctuation and ampersands", () => {
  assert.strictEqual(canon("Björk"), "bjork");
  assert.strictEqual(canon("Don't Stop"), "dont stop");
  assert.strictEqual(canon("Don’t Stop"), "dont stop");
  assert.strictEqual(canon("Simon & Garfunkel"), "simon and garfunkel");
  assert.strictEqual(canon("  A   B  "), "a b");
  assert.strictEqual(canon(null), "");
});

test("stripVersion removes edition suffixes", () => {
  assert.strictEqual(stripVersion("Blue Monday - 2016 Remaster"), "Blue Monday");
  assert.strictEqual(stripVersion("Blue Monday (Remastered)"), "Blue Monday");
  assert.strictEqual(stripVersion("Rumours [Deluxe Edition]"), "Rumours");
  assert.strictEqual(stripVersion("Song (feat. Someone) - 2011 Remaster"), "Song");
});

test("stripVersion KEEPS suffixes that name a different recording", () => {
  // The whole safety property of the module: these are not editions.
  assert.strictEqual(stripVersion("Paranoid Android (Live)"), "Paranoid Android (Live)");
  assert.strictEqual(stripVersion("Song (Acoustic)"), "Song (Acoustic)");
  assert.strictEqual(stripVersion("Song (Radio Edit)"), "Song (Radio Edit)");
  assert.strictEqual(stripVersion("Song - Extended Mix"), "Song - Extended Mix");
  assert.strictEqual(stripVersion("Song (Someone Remix)"), "Song (Someone Remix)");
});

test("stripVersion never empties the title", () => {
  assert.strictEqual(stripVersion("(Remastered)"), "(Remastered)");
});

test("primaryArtist takes the lead credit", () => {
  assert.strictEqual(primaryArtist("Calvin Harris feat. Rihanna"), "calvin harris");
  assert.strictEqual(primaryArtist("Calvin Harris, Rihanna"), "calvin harris");
  assert.strictEqual(primaryArtist("Jay-Z & Kanye West"), "jay z");
  assert.strictEqual(primaryArtist("Portishead"), "portishead");
});

test("artistSet holds the parts AND the name exactly as written", () => {
  // Both, deliberately. `overlap` needs one shared entry, so carrying the
  // whole string alongside the pieces lets two spellings of the same name
  // agree — see the "Siouxsie" test below, which is 42 albums of a real
  // library — without admitting an artist the string never named.
  const feat = artistSet("Calvin Harris feat. Rihanna");
  assert.ok(feat.has("calvin harris"));
  assert.ok(feat.has("rihanna"));
  assert.ok(feat.has("calvin harris feat rihanna"), "the credit as written");
  assert.ok(!feat.has("drake"));

  assert.deepStrictEqual([...artistSet(["Calvin Harris", "Rihanna"])],
    ["calvin harris", "rihanna"]);
});

test("one name written two ways still agrees with itself", () => {
  // 42 albums of a real library were refused for this: Roon writes
  // "Siouxsie and the Banshees", the service writes "Siouxsie & The
  // Banshees", canon turns "&" into "and" so the two ARE the same string —
  // but the split ran first and cut the service's name into "Siouxsie" and
  // "The Banshees", leaving nothing to agree with.
  for (const [ours, theirs] of [
    ["Siouxsie and the Banshees", "Siouxsie & The Banshees"],
    ["Derek and the Dominos", "Derek & The Dominos"],
    ["King Gizzard and the Lizard Wizard", "King Gizzard & The Lizard Wizard"],
    ["To Die For", "To/Die/For"],
  ]) {
    assert.ok(overlap(artistSet(ours), artistSet(theirs)) > 0,
      `${ours} must agree with ${theirs}`);
  }
});

test("a leading article and an ensemble word are not different artists", () => {
  const same = (a, b) => overlap(artistSet(a), artistSet(b)) > 0;
  assert.ok(same("The Modern Jazz Quartet", "Modern Jazz Quartet"), "an article");
  assert.ok(same("A Certain Ratio", "Certain Ratio"));
  assert.ok(same("Vijay Iyer Trio", "Vijay Iyer"), "how jazz bills a leader");
  assert.ok(same("The Dave Brubeck Quartet", "Dave Brubeck"));

  // The guard that matters: a tribute act IS somebody else, and no amount of
  // tidying may let one through.
  assert.ok(!same("Portishead", "Portishead Tribute"));
  assert.ok(!same("Metallica", "Apocalyptica"));
  assert.ok(!same("Nirvana", "Nirvana UK"), "a different band of the same name");
});

test("a band whose own name contains & or / is not cut in half", () => {
  // 88 albums of a real library came back "not found" because the splitter
  // searched for "AC" and compared "Belle" — the fix that made a jazz trio's
  // slash-joined credit searchable had cut single names up too.
  assert.deepStrictEqual(splitArtists("AC/DC"), ["AC/DC"]);
  assert.deepStrictEqual(splitArtists("Belle & Sebastian"), ["Belle & Sebastian"]);
  assert.deepStrictEqual(splitArtists("Earth, Wind & Fire"), ["Earth, Wind & Fire"]);
  assert.deepStrictEqual(splitArtists("Siouxsie & The Banshees"),
    ["Siouxsie & The Banshees"]);

  // And it still splits what is genuinely a list: first name and last name on
  // every side of the separator.
  assert.deepStrictEqual(splitArtists("Carla Bley/Steve Swallow/Andy Sheppard"),
    ["Carla Bley", "Steve Swallow", "Andy Sheppard"]);
  assert.deepStrictEqual(splitArtists("Miles Davis, John Coltrane"),
    ["Miles Davis", "John Coltrane"]);
  assert.deepStrictEqual(splitArtists("Vincent Peirani & Emile Parisien"),
    ["Vincent Peirani", "Emile Parisien"]);
  // A collaboration marker is never part of a name, so it always splits.
  assert.deepStrictEqual(splitArtists("Terence Blanchard featuring the E-Collective"),
    ["Terence Blanchard", "the E-Collective"]);
  assert.deepStrictEqual(splitArtists("/"), []);
});

test("similarity is 1 for equal and 0 for empty", () => {
  assert.strictEqual(similarity("abc", "abc"), 1);
  assert.strictEqual(similarity("", "abc"), 0);
  assert.ok(similarity("rumours", "rumors") > 0.5);
  assert.ok(similarity("rumours", "nevermind") < 0.2);
});

test("overlap measures the smaller set", () => {
  assert.strictEqual(overlap(new Set(["a"]), new Set(["a", "b"])), 1);
  assert.strictEqual(overlap(new Set(["a"]), new Set(["b"])), 0);
  assert.strictEqual(overlap(new Set(), new Set(["a"])), 0);
});
