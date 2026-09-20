import { readFileSync, mkdirSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";

const SRC = resolve(
  "D:/Documents/VSCode/waterctlEle/crates/protocol/src/deputy_data.rs",
);
const OUT = resolve(
  "D:/Documents/VSCode/waterctlAnd/app/src/main/kotlin/com/waterctl/android/protocol/DeputyData.kt",
);

const src = readFileSync(SRC, "utf8");
const bytes = [];
for (const m of src.matchAll(/\b0x([0-9a-fA-F]{2})\b/g)) {
  bytes.push(parseInt(m[1], 16));
}

if (bytes.length !== 2096) {
  console.error(`expected 2096 bytes, got ${bytes.length}`);
  process.exit(1);
}

let out = "package com.waterctl.android.protocol\n\n";
out += "// AUTO-GENERATED from deputy.wat data segment. Do not edit manually.\n";
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