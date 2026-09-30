#!/usr/bin/env node
// Drives lab sessions on the phone and reads their results.
//
// The Honor 400 drops every adb connection, wired or wireless, when the cable
// comes out, so a session runs on the phone by itself: start it with the cable
// in, unplug, plug back in when it is done, then pull and report.
//
//   node tools/lab.mjs start  --loads "dry;fp32_gemm;0-3:dry,4-7:bf16_mmla" [--repeat 2] [--seconds 60]
//                             [--idle 10] [--cool 40] [--nice 0] [--batch 20] [--brightness 0.2]
//   node tools/lab.mjs run    --load fp32_gemm [same options]   one run on the cable, to check the pipeline
//                                                               (power is not valid while charging)
//   node tools/lab.mjs pull   [session]     copy a session (default: the newest) to tools/out/<session>/
//   node tools/lab.mjs report [session]     rank the loads of a pulled session (default: the newest)
//   node tools/lab.mjs temps                CPU/GPU/DDR temperatures now
//
// The device is the only one adb sees, or ADB_SERIAL.

import { execFileSync } from "node:child_process";
import { existsSync, mkdirSync, readdirSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const OUT = join(dirname(fileURLToPath(import.meta.url)), "out");
const ADB = process.env.ADB ?? join(process.env.LOCALAPPDATA ?? "", "Android/Sdk/platform-tools/adb.exe");
const PACKAGE = "dev.ozcan.stress";
const REMOTE = `/sdcard/Android/data/${PACKAGE}/files/lab`;

function adb(args) {
    const serial = process.env.ADB_SERIAL ? ["-s", process.env.ADB_SERIAL] : [];
    return execFileSync(ADB, [...serial, ...args], { encoding: "utf8", maxBuffer: 64 << 20 });
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

function launch(opts, loads, onBattery) {
    const extras = {
        load: loads,
        repeat: opts.repeat,
        idle: opts.idle,
        seconds: opts.seconds,
        cool: opts.cool,
        nice: opts.nice,
        batch: opts.batch,
        brightness: opts.brightness,
        battery: onBattery ? "1" : "0",
    };
    const extraArgs = Object.entries(extras)
        .filter(([, v]) => v !== undefined)
        // The value goes through the device shell, so it is single-quoted there.
        .flatMap(([k, v]) => ["--es", `lab.${k}`, `'${String(v)}'`]);
    adb(["logcat", "-c"]);
    adb(["shell", "am", "start", "-S", "-n", `${PACKAGE}/.MainActivity`, ...extraArgs]);
}

function sessions() {
    return adb(["shell", "ls", REMOTE]).split(/\s+/).filter((s) => /^\d{8}-\d{6}$/.test(s)).sort();
}

function pull(name) {
    const session = name ?? sessions().at(-1);
    if (!session) throw new Error("telefonda oturum yok");
    mkdirSync(OUT, { recursive: true });
    adb(["pull", `${REMOTE}/${session}`, OUT]);
    const complete = existsSync(join(OUT, session, "session.json"));
    console.log(`çekildi: tools/out/${session} ${complete ? "(tamamlanmış)" : "(YARIM: session.json yok)"}`);
    return session;
}

const fmt = (v, d = 2) => (v === null || v === undefined || Number.isNaN(v) ? "—" : Number(v).toFixed(d));
const mean = (values) => {
    const v = values.filter((x) => x !== null && x !== undefined);
    return v.length ? v.reduce((a, b) => a + b, 0) / v.length : null;
};
const si = (v) => {
    if (v === null || v === undefined) return "—";
    for (const [s, p] of [[1e12, "T"], [1e9, "G"], [1e6, "M"]]) if (v >= s) return `${(v / s).toFixed(1)}${p}`;
    return v.toFixed(0);
};

function clusterRate(result, cpus) {
    const rates = cpus.map((c) => result.cpus[c]?.meanRate).filter((r) => r !== null && r !== undefined);
    return rates.length ? rates.reduce((a, b) => a + b, 0) : null;
}

function report(name) {
    const session = name ?? readdirSync(OUT).filter((s) => /^\d{8}-\d{6}$/.test(s)).sort().at(-1);
    if (!session) throw new Error("tools/out altında oturum yok; önce pull");
    const dir = join(OUT, session);
    const runs = readdirSync(dir)
        .filter((f) => /^\d{2}_.*\.json$/.test(f))
        .sort()
        .map((f) => JSON.parse(readFileSync(join(dir, f), "utf8")));
    console.log(`oturum ${session}: ${runs.length} koşu\n`);

    for (const r of runs) {
        const warn = [r.pluggedDuringRun && "ŞARJDA", !r.cooledInTime && "sıcak başladı", r.computationErrors > 0 && `HATA ${r.computationErrors}`]
            .filter(Boolean)
            .join(" · ");
        const start = Math.max(r.startTemperatures.A715 ?? 0, r.startTemperatures.A510 ?? 0);
        const unit = r.cpus.find((c) => c.unit)?.unit ?? "";
        console.log(
            `${String(r.runIndex + 1).padStart(2)}. ${r.assignment.padEnd(26)} ilk30 ${fmt(r.loadFirst30sWatts)} W · ` +
                `tüm ${fmt(r.load.meanWatts)} · boşta ${fmt(r.idle.meanWatts)} · ` +
                `A510 ${si(clusterRate(r, [0, 1, 2, 3]))} A715 ${si(clusterRate(r, [4, 5, 6]))} prime ${si(clusterRate(r, [7]))} ${unit} · ` +
                `başlangıç ${fmt(start, 1)} °C · sayaç/akım ${fmt(r.load.chargeCounterAmps / r.load.meanDischargeAmps, 3)}` +
                (warn ? ` · ${warn}` : ""),
        );
    }

    const loads = [...new Set(runs.map((r) => r.assignment))];
    const table = loads.map((load) => {
        const rs = runs.filter((r) => r.assignment === load);
        const first30 = rs.map((r) => r.loadFirst30sWatts);
        return {
            load,
            n: rs.length,
            first30: mean(first30),
            spread: rs.length > 1 ? Math.max(...first30) - Math.min(...first30) : null,
            all: mean(rs.map((r) => r.load.meanWatts)),
            last30: mean(rs.map((r) => r.loadLast30sWatts)),
            above: mean(rs.map((r) => r.loadAboveIdleWatts)),
            freqs: rs[0].clusters.map((_, i) => mean(rs.map((r) => r.clusters[i].last30sMeanMhz))),
            maxA715: mean(rs.map((r) => r.maxTemperatures.A715)),
            errors: rs.reduce((a, r) => a + r.computationErrors, 0),
        };
    });
    table.sort((a, b) => (b.first30 ?? 0) - (a.first30 ?? 0));
    console.log("\n=== sıralama: ilk 30 sn ortalama güç ===");
    for (const t of table) {
        console.log(
            `${t.load.padEnd(26)} ilk30 ${fmt(t.first30)} W (±${fmt(t.spread)}) · tüm ${fmt(t.all)} · son30 ${fmt(t.last30)} · ` +
                `boşta üstü ${fmt(t.above)} · son30 MHz ${t.freqs.map((f) => fmt(f, 0)).join("/")} · A715 en çok ${fmt(t.maxA715, 1)} °C · hata ${t.errors}`,
        );
    }
}

async function runOnCable(opts) {
    if (!opts.load) throw new Error("--load gerekli");
    launch(opts, opts.load, false);
    console.log(`▶ ${opts.load} (kabloda; güç geçersiz)`);
    const deadline = Date.now() + (Number(opts.idle ?? 10) + Number(opts.seconds ?? 60) + 15 * 60 + 60) * 1000;
    while (Date.now() < deadline) {
        await sleep(3000);
        const log = adb(["logcat", "-d", "-s", "STRESS_LAB:*"]);
        if (log.includes("session-done")) return report(pull());
        const error = log.split(/\r?\n/).find((l) => l.includes("error spec="));
        if (error) throw new Error(error);
    }
    throw new Error("zaman aşımı");
}

const args = parseArgs(process.argv.slice(2));
try {
    switch (args._[0]) {
        case "start": {
            if (!args.loads) throw new Error("--loads gerekli");
            launch(args, args.loads, true);
            const n = args.loads.split(";").filter((s) => s.trim()).length * Number(args.repeat ?? 1);
            console.log(`oturum başlatıldı: ${n} koşu. Telefon kablonun çıkarılmasını bekliyor.`);
            break;
        }
        case "run":
            await runOnCable(args);
            break;
        case "pull":
            pull(args._[1]);
            break;
        case "report":
            report(args._[1]);
            break;
        case "temps":
            console.log(readTemps());
            break;
        default:
            console.error("kullanım: node tools/lab.mjs start|run|pull|report|temps [--seçenekler]");
            process.exit(2);
    }
} catch (e) {
    console.error(`HATA: ${e.message}`);
    process.exit(1);
}
