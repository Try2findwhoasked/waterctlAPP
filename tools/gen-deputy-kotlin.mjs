// Regenerates `DeputyData.kt` from the `deputy.wat` data segment of
// https://github.com/celesWuff/waterctl (default branch `2.x`, file src/deputy.wat).
//
// Usage:
//   node tools/gen-deputy-kotlin.mjs
//
// Inputs may be overridden through environment variables:
//   WATERCTL_SRC          path to a checkout of celesWuff/waterctl
//                         (defaults to ../waterctlEle)
//   WATERCTL_ANDROID_DIR  path to this Android project
//                         (defaults to ../waterctlAnd)
import { readFileSync, mkdirSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";

const WATERCTL_SRC =
  process.env.WATERCTL_SRC ?? "D:/Documents/VSCode/waterctlEle";
const ANDROID_DIR =
  process.env.WATERCTL_ANDROID_DIR ?? "D:/Documents/VSCode/waterctlAnd";

const SRC = resolve(join(WATERCTL_SRC, "src/deputy.wat"));
const OUT = resolve(
  join(
    ANDROID_DIR,
    "app/src/main/kotlin/com/waterctl/android/protocol/DeputyData.kt",
  ),
);

const src = readFileSync(SRC, "utf8");
const bytes = [];

// The data segment looks like:
//   (data (i32.const 0)
//     "\00\04\05\00\01\05\04\00\01\05\04\01\00\04\05\00"
//     ...
//   )
// WAT encodes bytes inside these strings as `\hh` (two hex digits).
let inData = false;
for (const line of src.split(/\r?\n/)) {
  if (!inData) {
    if (/\(data\s*\(i32\.const\s+0\)/.test(line)) inData = true;
    continue;
  }
  if (/^\s*\)/.test(line)) break;
  for (const m of line.matchAll(/\\([0-9a-fA-F]{2})/g)) {
    bytes.push(parseInt(m[1], 16));
  }
}

if (bytes.length !== 2096) {
  console.error(`expected 2096 bytes, got ${bytes.length}`);
  process.exit(1);
}

let out = "package com.waterctl.android.protocol\n\n";
out +=
  "// AUTO-GENERATED from https://github.com/celesWuff/waterctl" +
  " (deputy.wat data segment). Do not edit manually.\n";
out += "internal val DEPUTY_DATA: IntArray = intArrayOf(\n";
for (let i = 0; i < bytes.length; i += 16) {
  const row = bytes
    .slice(i, i + 16)
    .map((b) => "0x" + b.toString(16).padStart(2, "0"))
    .join(", ");
  out += "    " + row + ",\n";
}
out += ")\n";

mkdirSync(dirname(OUT), { recursive: true });
writeFileSync(OUT, out);
console.log(
  `generated ${OUT} (${bytes.length} bytes, ${Math.ceil(bytes.length / 16)} rows)`,
);