#!/usr/bin/env node
// Drives lab runs on the phone and collects their results.
//
//   node tools/lab.mjs run   --load fp32_gemm [--seconds 60] [--idle 10] [--tag x] [--nice 0] [--batch 20]
//                            [--brightness 0.2] [--cool 38]
//   node tools/lab.mjs sweep --loads dry,fp32_gemm,bf16_mmla [--repeat 2] [same options as run]
//   node tools/lab.mjs temps
//
// Every run first waits until the hottest CPU zone is below --cool degrees, so
// candidates are compared from the same starting temperature (a hot chip leaks
// more and would flatter whichever kernel ran later). Results land in
// tools/out/. The device is the only one adb sees, or ADB_SERIAL.

import { execFileSync } from "node:child_process";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const OUT = join(dirname(fileURLToPath(import.meta.url)), "out");
const ADB = process.env.ADB ?? join(process.env.LOCALAPPDATA ?? "", "Android/Sdk/platform-tools/adb.exe");
const PACKAGE = "dev.ozcan.stress";

function adb(args, options = {}) {
    const serial = process.env.ADB_SERIAL ? ["-s", process.env.ADB_SERIAL] : [];
    return execFileSync(ADB, [...serial, ...args], { encoding: "utf8", maxBuffer: 64 << 20, ...options });
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function parseArgs(argv) {
    const args = { _: [] };
    for (let i = 0; i < argv.length; i++) {
        if (argv[i].startsWith("--")) args[argv[i].slice(2)] = argv[++i];
        else args._.push(argv[i]);
    }
    return args;
}

/** Zone temperatures in °C for the CPU and GPU groups, read through the shell (which may read them all). */
function readTemps() {
    const script =
        'for z in /sys/class/thermal/thermal_zone*; do t=$(cat $z/type); ' +
        'case $t in cpu-0-*|cpu-1-*|gpuss-*|ddr) echo "$t $(cat $z/temp)";; esac; done';
    const temps = {};
    for (const line of adb(["shell", script]).split(/\r?\n/)) {
        const [type, milli] = line.trim().split(/\s+/);
        if (!type || !milli) continue;
        const group = type.startsWith("cpu-1-") ? "A715" : type.startsWith("cpu-0-") ? "A510" : type.startsWith("gpuss-") ? "GPU" : "DDR";
        temps[group] = Math.max(temps[group] ?? -Infinity, Number(milli) / 1000);
    }
    return temps;
}

async function coolDown(limit) {
    const started = Date.now();
    for (;;) {
        const t = readTemps();
        const hottest = Math.max(t.A715 ?? 0, t.A510 ?? 0);
        if (hottest <= limit) {
            if (Date.now() - started > 1000) process.stdout.write("\n");
            return t;
        }
        process.stdout.write(`\r  soğuma bekleniyor: CPU ${hottest.toFixed(1)} °C > ${limit} °C (${Math.round((Date.now() - started) / 1000)} sn)   `);
        await sleep(5000);
    }
}

async function run(opts) {
    const load = opts.load ?? (() => { throw new Error("--load gerekli"); })();
    const idle = Number(opts.idle ?? 10);
    const seconds = Number(opts.seconds ?? 60);
    const tag = opts.tag ?? load.replace(/[^A-Za-z0-9._-]/g, "_");
    const startTemps = await coolDown(Number(opts.cool ?? 38));

    adb(["logcat", "-c"]);
    const extras = { load, idle, seconds, tag, nice: opts.nice, batch: opts.batch, brightness: opts.brightness };
    const extraArgs = Object.entries(extras)
        .filter(([, v]) => v !== undefined)
        .flatMap(([k, v]) => ["--es", `lab.${k}`, String(v)]);
    adb(["shell", "am", "start", "-S", "-n", `${PACKAGE}/.MainActivity`, ...extraArgs]);
    console.log(`▶ ${tag}: ${idle} sn boşta + ${seconds} sn yük (başlangıç CPU ${Math.max(startTemps.A715, startTemps.A510).toFixed(1)} °C)`);

    const deadline = Date.now() + (idle + seconds + 90) * 1000;
    let line;
    while (Date.now() < deadline) {
        await sleep(2000);
        const log = adb(["logcat", "-d", "-s", "STRESS_LAB:*"]);
        line = log.split(/\r?\n/).find((l) => l.includes("done file=") || l.includes("error spec="));
        if (line) break;
    }
    if (!line) throw new Error(`${tag}: sonuç gelmedi (zaman aşımı)`);
    if (line.includes("error spec=")) throw new Error(`${tag}: ${line}`);

    const remote = /done file=(\S+)/.exec(line)[1];
    mkdirSync(OUT, { recursive: true });
    const local = join(OUT, remote.split("/").pop());
    adb(["pull", remote, local]);
    adb(["pull", remote.replace(/\.json$/, ".csv"), local.replace(/\.json$/, ".csv")]);
    const result = JSON.parse(readFileSync(local, "utf8"));
    printSummary(result);
    return result;
}

const fmt = (v, d = 2) => (v === null || v === undefined ? "—" : Number(v).toFixed(d));

function prefix(v) {
    if (v === null || v === undefined) return "—";
    for (const [s, p] of [[1e12, "T"], [1e9, "G"], [1e6, "M"]]) if (v >= s) return `${(v / s).toFixed(1)} ${p}`;
    return v.toFixed(0);
}

function clusterRates(result) {
    const groups = { A510: [0, 1, 2, 3], A715: [4, 5, 6], prime: [7] };
    return Object.fromEntries(
        Object.entries(groups).map(([name, cpus]) => {
            const rates = cpus.map((c) => result.cpus[c]?.meanRate).filter((r) => r !== null && r !== undefined);
            return [name, rates.length ? rates.reduce((a, b) => a + b, 0) : null];
        }),
    );
}

function printSummary(r) {
    const counterRatio = r.load.chargeCounterAmps && r.load.meanDischargeAmps ? r.load.chargeCounterAmps / r.load.meanDischargeAmps : null;
    const unit = r.cpus.find((c) => c.unit)?.unit ?? "";
    const rates = clusterRates(r);
    console.log(
        `  güç: yük ${fmt(r.load.meanWatts)} W · boşta ${fmt(r.idle.meanWatts)} W · fark ${fmt(r.loadAboveIdleWatts)} W · ` +
            `ilk30 ${fmt(r.loadFirst30sWatts)} · son30 ${fmt(r.loadLast30sWatts)} · en iyi 5 sn ${fmt(r.loadMax5sWatts)}`,
    );
    console.log(
        `  akım ${fmt(r.load.meanDischargeAmps, 3)} A · sayaç ${fmt(r.load.chargeCounterAmps, 3)} A (oran ${fmt(counterRatio, 3)}) · ` +
            `${fmt(r.load.meanVolts, 3)} V · şarjda: ${r.pluggedDuringRun ? "EVET (geçersiz)" : "hayır"}`,
    );
    console.log(
        `  iş: A510 ${prefix(rates.A510)}${unit} · A715 ${prefix(rates.A715)}${unit} · prime ${prefix(rates.prime)}${unit} · hata ${r.computationErrors}`,
    );
    console.log(
        `  frekans (ort/son30 MHz): ${r.clusters.map((c) => `${fmt(c.meanMhz, 0)}/${fmt(c.last30sMeanMhz, 0)} of ${fmt(c.maxMhz, 0)}`).join(" · ")}`,
    );
    console.log(`  en yüksek sıcaklık: ${Object.entries(r.maxTemperatures).map(([k, v]) => `${k} ${fmt(v, 1)}`).join(" · ")}`);
    console.log(
        `  ritim: örnek ${fmt(r.cadence.sampleIntervalSeconds, 3)} sn · akım ${fmt(r.cadence.currentChangeSeconds, 3)} sn · ` +
            `sayaç ${fmt(r.cadence.chargeCounterChangeSeconds, 3)} sn · gerilim ${fmt(r.cadence.voltageChangeSeconds, 3)} sn · yayın ${r.cadence.batteryBroadcasts}`,
    );
}

function shuffle(list) {
    const a = [...list];
    for (let i = a.length - 1; i > 0; i--) {
        const j = Math.floor(Math.random() * (i + 1));
        [a[i], a[j]] = [a[j], a[i]];
    }
    return a;
}

async function sweep(opts) {
    const loads = (opts.loads ?? "").split(",").map((s) => s.trim()).filter(Boolean);
    if (!loads.length) throw new Error("--loads gerekli");
    const repeat = Number(opts.repeat ?? 2);
    const order = shuffle(loads.flatMap((l) => Array(repeat).fill(l)));
    const results = [];
    for (const [i, load] of order.entries()) {
        console.log(`\n[${i + 1}/${order.length}]`);
        results.push(await run({ ...opts, load, tag: undefined }));
    }
    const table = loads.map((load) => {
        const runs = results.filter((r) => r.assignment === load || r.tag === load.replace(/[^A-Za-z0-9._-]/g, "_"));
        const mean = (f) => {
            const v = runs.map(f).filter((x) => x !== null && x !== undefined);
            return v.length ? v.reduce((a, b) => a + b, 0) / v.length : null;
        };
        return {
            load,
            runs: runs.length,
            loadW: mean((r) => r.load.meanWatts),
            aboveIdleW: mean((r) => r.loadAboveIdleWatts),
            first30W: mean((r) => r.loadFirst30sWatts),
            max5sW: mean((r) => r.loadMax5sWatts),
            spreadW: runs.length > 1 ? Math.max(...runs.map((r) => r.load.meanWatts)) - Math.min(...runs.map((r) => r.load.meanWatts)) : null,
            errors: runs.reduce((a, r) => a + r.computationErrors, 0),
        };
    });
    table.sort((a, b) => (b.first30W ?? 0) - (a.first30W ?? 0));
    console.log("\n=== sıralama (ilk 30 sn ortalaması) ===");
    for (const t of table) {
        console.log(
            `${t.load.padEnd(28)} ilk30 ${fmt(t.first30W)} W · tüm ${fmt(t.loadW)} W · boşta üstü ${fmt(t.aboveIdleW)} W · ` +
                `en iyi 5 sn ${fmt(t.max5sW)} W · tekrar farkı ${fmt(t.spreadW)} W · hata ${t.errors}`,
        );
    }
    const file = join(OUT, `sweep-${new Date().toISOString().replace(/[:.]/g, "-")}.json`);
    writeFileSync(file, JSON.stringify({ options: opts, table, results }, null, 2));
    console.log(`\nkayıt: ${file}`);
}

const args = parseArgs(process.argv.slice(2));
const command = args._[0];
try {
    if (command === "run") await run(args);
    else if (command === "sweep") await sweep(args);
    else if (command === "temps") console.log(readTemps());
    else {
        console.error("kullanım: node tools/lab.mjs run|sweep|temps [--seçenekler]");
        process.exit(2);
    }
} catch (e) {
    console.error(`HATA: ${e.message}`);
    process.exit(1);
}
