import csv
import os
import statistics as st
from collections import defaultdict

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "results")
OUT = os.path.join(ROOT, "plots")
os.makedirs(OUT, exist_ok=True)

SURFACE = "#fcfcfb"
INK = "#0b0b0b"
INK2 = "#52514e"
GRID = "#e4e3df"
SERIES = ["#2a78d6", "#eb6834", "#1baf7a", "#eda100", "#e87ba4", "#008300"]
MARKERS = ["o", "s", "^", "D", "v", "P"]

IMPL_LABEL = {"hashmap": "java.util.HashMap", "cuckoo1": "Cuckoo b=1 (load ≤ 0.45)", "cuckoo4": "Cuckoo b=4 (load ≤ 0.90)"}
IMPL_ORDER = ["hashmap", "cuckoo1", "cuckoo4"]
THEORY = {1: 0.5, 2: 0.8970, 4: 0.9804}

plt.rcParams.update({
    "figure.facecolor": SURFACE,
    "axes.facecolor": SURFACE,
    "savefig.facecolor": SURFACE,
    "axes.edgecolor": GRID,
    "axes.labelcolor": INK2,
    "axes.titlecolor": INK,
    "axes.titlesize": 11,
    "axes.titleweight": "bold",
    "axes.labelsize": 9.5,
    "xtick.color": INK2,
    "ytick.color": INK2,
    "xtick.labelsize": 8.5,
    "ytick.labelsize": 8.5,
    "axes.grid": True,
    "grid.color": GRID,
    "grid.linewidth": 0.7,
    "axes.spines.top": False,
    "axes.spines.right": False,
    "legend.frameon": False,
    "legend.fontsize": 8.5,
    "lines.linewidth": 2,
    "lines.markersize": 5.5,
    "font.family": "DejaVu Sans",
})


def read(name):
    path = os.path.join(RES, name)
    if not os.path.exists(path):
        return []
    with open(path) as f:
        return list(csv.DictReader(f))


def save(fig, name):
    fig.tight_layout()
    fig.savefig(os.path.join(OUT, name), dpi=160)
    plt.close(fig)
    print("wrote plots/" + name)


def size_label(n):
    n = int(n)
    if n >= 1 << 20:
        return f"{n >> 20}M"
    if n >= 1 << 10:
        return f"{n >> 10}K"
    return str(n)


def lookup_plots():
    rows = read("lookup.csv")
    if not rows:
        return
    d = defaultdict(list)
    for r in rows:
        d[(r["dist"], r["kind"], r["impl"], int(r["n"]))].append(float(r["ns_per_op"]))
    for dist in ["random", "sequential"]:
        fig, axes = plt.subplots(1, 2, figsize=(10, 3.9), sharey=True)
        for ax, kind in zip(axes, ["hit", "miss"]):
            for i, impl in enumerate(IMPL_ORDER):
                ns = sorted(n for (a, b, c, n) in d if a == dist and b == kind and c == impl)
                if not ns:
                    continue
                med = [st.median(d[(dist, kind, impl, n)]) for n in ns]
                lo = [min(d[(dist, kind, impl, n)]) for n in ns]
                hi = [max(d[(dist, kind, impl, n)]) for n in ns]
                ax.fill_between(ns, lo, hi, color=SERIES[i], alpha=0.15, linewidth=0)
                ax.plot(ns, med, color=SERIES[i], marker=MARKERS[i], label=IMPL_LABEL[impl])
            ax.set_xscale("log", base=2)
            ax.set_xticks(ns)
            ax.set_xticklabels([size_label(n) for n in ns])
            ax.set_xlabel("number of keys in map")
            ax.set_title(f"{'Successful' if kind == 'hit' else 'Unsuccessful'} lookups")
        axes[0].set_ylabel("ns per lookup (median of 7; band = min–max)")
        axes[0].set_ylim(bottom=0)
        axes[0].legend(loc="upper left")
        fig.suptitle(f"Lookup cost, {dist} Integer keys, {'random' if dist == 'random' else 'sequential'} query order",
                     color=INK, fontsize=12, fontweight="bold")
        save(fig, f"lookup_{dist}.png")


def insert_plot():
    rows = read("insert.csv")
    if not rows:
        return
    d = defaultdict(list)
    for r in rows:
        d[(r["dist"], r["impl"], int(r["n"]))].append(float(r["ns_per_op"]))
    fig, axes = plt.subplots(1, 2, figsize=(10, 3.9), sharey=True)
    for ax, dist in zip(axes, ["random", "sequential"]):
        for i, impl in enumerate(IMPL_ORDER):
            ns = sorted(n for (a, b, n) in d if a == dist and b == impl)
            med = [st.median(d[(dist, impl, n)]) for n in ns]
            lo = [min(d[(dist, impl, n)]) for n in ns]
            hi = [max(d[(dist, impl, n)]) for n in ns]
            ax.fill_between(ns, lo, hi, color=SERIES[i], alpha=0.15, linewidth=0)
            ax.plot(ns, med, color=SERIES[i], marker=MARKERS[i], label=IMPL_LABEL[impl])
        ax.set_xscale("log", base=2)
        ax.set_xticks(ns)
        ax.set_xticklabels([size_label(n) for n in ns])
        ax.set_xlabel("number of keys inserted (from an empty map)")
        ax.set_title(f"{dist.capitalize()} keys")
    axes[0].set_ylabel("ns per insert, incl. resizing")
    axes[0].set_ylim(bottom=0)
    axes[0].legend(loc="upper left")
    fig.suptitle("Building a map from scratch", color=INK, fontsize=12, fontweight="bold")
    save(fig, "insert.png")


def fill_plots():
    rows = read("fill.csv")
    if not rows:
        return
    d = defaultdict(list)
    for r in rows:
        d[(int(r["max_kicks"]), int(r["bucket_size"]), int(r["slots"]))].append(float(r["fail_load"]))

    fig, ax = plt.subplots(figsize=(8, 4.4))
    for i, b in enumerate([1, 2, 4, 8]):
        slots = sorted(s for (mk, bb, s) in d if mk == 500 and bb == b)
        med = [st.median(d[(500, b, s)]) for s in slots]
        lo = [min(d[(500, b, s)]) for s in slots]
        hi = [max(d[(500, b, s)]) for s in slots]
        ax.errorbar(slots, med, yerr=[[m - l for m, l in zip(med, lo)], [h - m for m, h in zip(med, hi)]],
                    color=SERIES[i], marker=MARKERS[i], capsize=3, label=f"b = {b}")
        if b in THEORY:
            ax.axhline(THEORY[b], color=SERIES[i], linestyle=":", linewidth=1.3)
            ax.annotate(f"theory {THEORY[b]:.3f}", xy=(slots[-1], THEORY[b]), xytext=(6, 2),
                        textcoords="offset points", color=INK2, fontsize=8)
    ax.set_xscale("log", base=2)
    ax.set_xticks([1024, 16384, 262144])
    ax.set_xticklabels(["1K slots", "16K slots", "256K slots"])
    ax.set_xlim(700, 900000)
    ax.set_ylabel("load factor at first failed insert")
    ax.set_title("Where a fixed-size table first fails (2 hash functions, max 500 kicks, no stash)")
    ax.legend(loc="lower right", ncol=4)
    save(fig, "fill_threshold.png")

    fig, ax = plt.subplots(figsize=(8, 4))
    for i, b in enumerate([1, 4]):
        mks = sorted(mk for (mk, bb, s) in d if bb == b and s == 65536)
        med = [st.median(d[(mk, b, 65536)]) for mk in mks]
        lo = [min(d[(mk, b, 65536)]) for mk in mks]
        hi = [max(d[(mk, b, 65536)]) for mk in mks]
        idx = SERIES.index(SERIES[[1, 2, 4, 8].index(b)])
        ax.errorbar(mks, med, yerr=[[m - l for m, l in zip(med, lo)], [h - m for m, h in zip(med, hi)]],
                    color=SERIES[idx], marker=MARKERS[idx], capsize=3, label=f"b = {b}")
        ax.axhline(THEORY[b], color=SERIES[idx], linestyle=":", linewidth=1.3)
        ax.annotate(f"theory {THEORY[b]:.3f}", xy=(mks[0], THEORY[b]), xytext=(0, 4),
                    textcoords="offset points", color=INK2, fontsize=8)
    ax.set_xscale("log")
    ax.set_xlabel("maximum kicks per insert before giving up (log scale)")
    ax.set_ylabel("load factor at first failure")
    ax.set_title("The kick limit decides how close you get to the theoretical threshold (64K slots)")
    ax.legend(loc="lower right")
    save(fig, "max_kicks.png")


def kicks_plot():
    rows = read("kicks.csv")
    if not rows:
        return
    fig, ax = plt.subplots(figsize=(8, 4.4))
    for i, b in enumerate([1, 2, 4, 8]):
        pts = [(float(r["load"]), float(r["mean_kicks"])) for r in rows if int(r["bucket_size"]) == b]
        pts = [(x, y) for x, y in pts if y > 0]
        if not pts:
            continue
        ax.plot([p[0] for p in pts], [p[1] for p in pts], color=SERIES[i], marker=MARKERS[i],
                markersize=3, label=f"b = {b}")
        if b in THEORY:
            ax.axvline(THEORY[b], color=SERIES[i], linestyle=":", linewidth=1.2)
    ax.set_yscale("log")
    ax.set_xlim(0, 1.0)
    ax.set_xlabel("current load factor when the insert happens")
    ax.set_ylabel("mean kicks per insert (log scale)")
    ax.set_title("Insert work explodes as load approaches the threshold (dotted lines)")
    ax.legend(loc="upper left")
    save(fig, "kicks_vs_load.png")


def stash_plot():
    rows = read("stash.csv")
    if not rows:
        return
    fig, ax = plt.subplots(figsize=(8, 4.4))
    for i, s in enumerate(sorted({int(r["stash"]) for r in rows})):
        pts = [(int(r["slots"]), float(r["failure_rate"]), int(r["failures"]))
               for r in rows if int(r["stash"]) == s]
        shown = [(x, y) for x, y, f in pts if f >= 3]
        if not shown:
            continue
        ax.plot([p[0] for p in shown], [p[1] for p in shown], color=SERIES[i], marker=MARKERS[i],
                label=f"stash = {s}")
        x0, y0 = shown[0]
        xs = [x0, x0 * 8]
        ax.plot(xs, [y0, y0 * (1 / 8) ** (s + 1)], color=SERIES[i], linestyle=":", linewidth=1.2)
    ax.set_xscale("log", base=2)
    ax.set_yscale("log")
    ax.set_xlabel("table size in slots (load 0.40, b = 1)")
    ax.set_ylabel("probability a build needs a rehash")
    ax.set_title("A stash of s slots should cut failures from Θ(1/n) to O(1/n^(s+1)) — dotted slopes")
    ax.legend(loc="lower left")
    ax.annotate("points with < 3 observed failures omitted", xy=(0.99, 0.98), xycoords="axes fraction",
                ha="right", va="top", color=INK2, fontsize=8)
    save(fig, "stash.png")


def memory_plot():
    rows = read("memory.csv")
    if not rows:
        return
    fig, ax = plt.subplots(figsize=(8, 4.2))
    for i, impl in enumerate(IMPL_ORDER):
        pts = sorted((int(r["n"]), float(r["bytes_per_entry"])) for r in rows if r["impl"] == impl)
        ax.plot([p[0] for p in pts], [p[1] for p in pts], color=SERIES[i], marker=MARKERS[i],
                markersize=3.5, label=IMPL_LABEL[impl])
    ax.set_xscale("log")
    ax.set_ylim(bottom=0)
    ax.set_xlabel("number of entries (log scale)")
    ax.set_ylabel("heap bytes per entry (excl. key/value objects)")
    ax.set_title("Memory overhead per entry: the sawtooth is the resize policy")
    ax.legend(loc="lower right")
    save(fig, "memory.png")


def loadsweep_plot():
    rows = read("loadsweep.csv")
    if not rows:
        return
    d = defaultdict(list)
    for r in rows:
        d[(int(r["bucket_size"]), float(r["load"]), r["kind"])].append(float(r["ns_per_op"]))
    fig, axes = plt.subplots(1, 2, figsize=(10, 3.9), sharey=True)
    for ax, kind in zip(axes, ["hit", "miss"]):
        for i, b in enumerate([1, 2, 4]):
            loads = sorted(l for (bb, l, k) in d if bb == b and k == kind)
            med = [st.median(d[(b, l, kind)]) for l in loads]
            ax.plot(loads, med, color=SERIES[i], marker=MARKERS[i], label=f"b = {b}")
        ax.set_xlim(0, 1)
        ax.set_xlabel("load factor (table size fixed at 4M slots)")
        ax.set_title("Successful lookups" if kind == "hit" else "Unsuccessful lookups")
    axes[0].set_ylabel("ns per containsKey (median of 5)")
    axes[0].set_ylim(bottom=0)
    axes[0].legend(loc="upper left")
    fig.suptitle("Theory says cuckoo lookup cost does not depend on load. Measured:", color=INK, fontsize=12,
                 fontweight="bold")
    save(fig, "lookup_vs_load.png")


def latency_plot():
    rows = read("latency_spikes.csv")
    if not rows:
        return
    fig, axes = plt.subplots(3, 1, figsize=(9, 6.2), sharex=True, sharey=True)
    for ax, (i, impl) in zip(axes, enumerate(IMPL_ORDER)):
        pts = [(int(r["index"]), float(r["micros"])) for r in rows if r["impl"] == impl and r["trial"] == "1"]
        ax.scatter([p[0] for p in pts], [p[1] for p in pts], s=9, color=SERIES[i])
        ax.set_yscale("log")
        ax.set_title(IMPL_LABEL[impl] + f"  ({len(pts)} inserts ≥ 50 µs)", loc="left", fontsize=10)
        ax.set_ylabel("µs")
    axes[-1].set_xlabel("insert number (4M random keys into an empty map, trial 1)")
    fig.suptitle("Individual slow inserts: resizes and rebuilds", color=INK, fontsize=12, fontweight="bold")
    save(fig, "latency_spikes.png")


if __name__ == "__main__":
    lookup_plots()
    insert_plot()
    fill_plots()
    kicks_plot()
    stash_plot()
    memory_plot()
    loadsweep_plot()
    latency_plot()
