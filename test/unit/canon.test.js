"use strict";
const test = require("node:test");
const assert = require("node:assert");
const { canon, stripVersion, primaryArtist, artistSet, similarity, overlap } =
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

test("artistSet splits a string and accepts an array", () => {
  assert.deepStrictEqual([...artistSet("Calvin Harris feat. Rihanna")],
    ["calvin harris", "rihanna"]);
  assert.deepStrictEqual([...artistSet(["Calvin Harris", "Rihanna"])],
    ["calvin harris", "rihanna"]);
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
