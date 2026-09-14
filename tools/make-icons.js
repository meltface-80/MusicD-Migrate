"use strict";
/*
 * make-icons.js — the app's icons, drawn rather than checked in as art.
 *
 * They are generated so that changing the mark is a change to eight lines here
 * and one command, rather than eight PNGs that drift apart the first time one
 * of them is edited. Run it with:  node tools/make-icons.js
 *
 * Two arrows, one going each way: this app migrates in both directions, and
 * the icon should say so at 48 pixels.
 *
 * No image library — a PNG is a zlib stream of filtered scanlines and three
 * CRC'd chunks, and pulling in a dependency to write 40 lines of that would be
 * a dependency on the critical path of a build for no reason.
 */

const fs = require("fs");
const path = require("path");
const zlib = require("zlib");

const BG = [0x13, 0x13, 0x14];
const FG = [0x3e, 0xa6, 0x72];
const OUT = path.join(__dirname, "..", "public", "icons");

/** @returns {Buffer} an RGBA raster, `size` square. */
function draw(size, maskable) {
  // A maskable icon is cropped to a circle by the launcher, so everything has
  // to sit inside the middle ~80%. Drawing one mark at two scales is why this
  // is a parameter rather than two functions.
  const inset = maskable ? size * 0.10 : 0;
  const radius = maskable ? 0 : size * 0.22;
  const px = Buffer.alloc(size * size * 4);

  const put = (x, y, c) => {
    if (x < 0 || y < 0 || x >= size || y >= size) return;
    const i = (y * size + x) * 4;
    px[i] = c[0]; px[i + 1] = c[1]; px[i + 2] = c[2]; px[i + 3] = 255;
  };

  // Background: a rounded square, or the full bleed a maskable icon needs.
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      if (radius && outsideRounded(x, y, size, radius)) continue;
      put(x, y, BG);
    }
  }

  // The two arrows. Each is a bar with a triangular head; the top one points
  // right and the bottom one left.
  const w = size - inset * 2;
  const barH = Math.max(2, Math.round(w * 0.11));
  const headH = Math.max(4, Math.round(w * 0.30));
  const headW = Math.max(3, Math.round(w * 0.20));
  const left = inset + w * 0.13;
  const right = inset + w * 0.87;
  const yTop = inset + w * 0.34;
  const yBottom = inset + w * 0.66;

  arrow(put, left, right, yTop, barH, headW, headH, 1);
  arrow(put, right, left, yBottom, barH, headW, headH, -1);

  return px;
}

/** One arrow from x0 to x1 at height y. `dir` is +1 for right, -1 for left. */
function arrow(put, x0, x1, y, barH, headW, headH, dir) {
  const tip = x1;
  const back = x1 - dir * headW;
  const barEnd = back;

  for (let yy = Math.round(y - barH / 2); yy <= Math.round(y + barH / 2); yy++) {
    const from = Math.round(Math.min(x0, barEnd));
    const to = Math.round(Math.max(x0, barEnd));
    for (let xx = from; xx <= to; xx++) put(xx, yy, FG);
  }

  // The head, as a triangle narrowing to the tip. Walking the long axis and
  // filling a shrinking span keeps it symmetrical at every size.
  const steps = Math.round(headW);
  for (let s = 0; s <= steps; s++) {
    const xx = Math.round(back + dir * s);
    const half = (headH / 2) * (1 - s / Math.max(steps, 1));
    for (let yy = Math.round(y - half); yy <= Math.round(y + half); yy++) put(xx, yy, FG);
  }
}

function outsideRounded(x, y, size, r) {
  const cx = x < r ? r : (x > size - r ? size - r : x);
  const cy = y < r ? r : (y > size - r ? size - r : y);
  const dx = x - cx, dy = y - cy;
  return dx * dx + dy * dy > r * r;
}

// ---------------------------------------------------------------- the PNG

function png(size, raster) {
  // Each scanline is prefixed with its filter type. 0 (none) throughout: the
  // image is flat colour and deflate handles it perfectly well without the
  // extra pass a real filter would need.
  const raw = Buffer.alloc(size * (size * 4 + 1));
  for (let y = 0; y < size; y++) {
    raw[y * (size * 4 + 1)] = 0;
    raster.copy(raw, y * (size * 4 + 1) + 1, y * size * 4, (y + 1) * size * 4);
  }

  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(size, 0);
  ihdr.writeUInt32BE(size, 4);
  ihdr[8] = 8;    // bit depth
  ihdr[9] = 6;    // colour type: RGBA
  ihdr[10] = 0;   // deflate
  ihdr[11] = 0;   // adaptive filtering
  ihdr[12] = 0;   // no interlace

  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk("IHDR", ihdr),
    chunk("IDAT", zlib.deflateSync(raw, { level: 9 })),
    chunk("IEND", Buffer.alloc(0)),
  ]);
}

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length, 0);
  const body = Buffer.concat([Buffer.from(type, "ascii"), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body) >>> 0, 0);
  return Buffer.concat([len, body, crc]);
}

let CRC_TABLE = null;
function crc32(buf) {
  if (!CRC_TABLE) {
    CRC_TABLE = new Int32Array(256);
    for (let n = 0; n < 256; n++) {
      let c = n;
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
      CRC_TABLE[n] = c;
    }
  }
  let c = -1;
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return c ^ -1;
}

// ------------------------------------------------------------------- main

fs.mkdirSync(OUT, { recursive: true });
const sizes = [
  ["icon-192.png", 192, false],
  ["icon-256.png", 256, false],
  ["icon-384.png", 384, false],
  ["icon-512.png", 512, false],
  ["apple-touch-icon.png", 180, false],
  ["favicon-64.png", 64, false],
  ["maskable-192.png", 192, true],
  ["maskable-512.png", 512, true],
];

for (const [name, size, maskable] of sizes) {
  fs.writeFileSync(path.join(OUT, name), png(size, draw(size, maskable)));
  console.log("wrote icons/" + name);
}
