"""Compare block-state histograms written by mca_hist.py.

usage: mca_diff.py <a.json> <b.json>                 two runs
       mca_diff.py <before1.json> <before2.json> <after.json>
                                                     a change, judged against run-to-run noise

Even with one chunk at a time on one worldgen thread, two runs of the same code differ in a few
chunks (features crossing chunk borders, mostly at the edge of the area). So a change is checked
with two "before" runs: every chunk the two agree on block for block must be identical after the
change, and the totals of the rest must stay within the spread the two before-runs show.
"""
import sys, json


def load(p):
    d = json.load(open(p))
    return d['chunks'], d['total']


def totals(ta, tb, only=None):
    for k in sorted(set(ta) | set(tb)):
        if ta.get(k, 0) != tb.get(k, 0) and (only is None or only(k)):
            print('  %-90s %9d %9d' % (k, ta.get(k, 0), tb.get(k, 0)))


if len(sys.argv) == 3:
    (ca, ta), (cb, tb) = load(sys.argv[1]), load(sys.argv[2])
    diff = [k for k in sorted(set(ca) | set(cb)) if ca.get(k) != cb.get(k)]
    print('chunks: %d / %d, differing: %d %s' % (len(ca), len(cb), len(diff), diff))
    totals(ta, tb)
else:
    (c1, t1), (c2, t2), (c3, t3) = (load(p) for p in sys.argv[1:4])
    stable = [k for k in c1 if c1[k] == c2.get(k)]
    broken = sorted(k for k in stable if c3.get(k) != c1[k])
    noisy = sorted(k for k in c1 if c1[k] != c2.get(k))
    print('chunks: %d, identical in both before-runs: %d, of those changed after: %d %s'
          % (len(c1), len(stable), len(broken), broken))
    print('chunks that differ between the before-runs (noise): %d, of those matching neither after: %d'
          % (len(noisy), sum(1 for k in noisy if c3.get(k) not in (c1[k], c2.get(k)))))
    outside = []
    for k in sorted(set(t1) | set(t2) | set(t3)):
        lo, hi = sorted((t1.get(k, 0), t2.get(k, 0)))
        v = t3.get(k, 0)
        if not lo <= v <= hi:
            outside.append((k, lo, hi, v))
    print('block states whose total after is outside the before range: %d' % len(outside))
    for k, lo, hi, v in outside:
        print('  %-90s before %d..%d  after %d' % (k, lo, hi, v))
    sys.exit(1 if broken else 0)
