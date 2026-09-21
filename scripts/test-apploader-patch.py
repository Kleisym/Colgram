"""Unit-test the re-entrant ApplicationLoader patch.

`patch_file` bails whenever the replacement text already exists, so a patch that must be
able to EXTEND previously-applied content has to do its own guarded insertion. This
verifies the three states that matter:
  1. fresh file           -> gets both pieces
  2. has piece 1 (the real, broken case) -> gains piece 2 without duplicating piece 1
  3. both present         -> unchanged (idempotent)

The replacer is a closure inside main(), so it is extracted from source. It must be
de-indented to zero before exec, and the fixture must be the REAL upstream shape
(the LauncherIconController/ProxyRotationController anchor plus the init block).
"""
import textwrap

src = open(r'C:\Colgram\scripts\apply-patches.py', encoding='utf-8').read()
lines = src.split('\n')

start = next(i for i, l in enumerate(lines)
             if l.startswith('    def app_loader_replacer(content):'))
end = start + 1
while end < len(lines):
    l = lines[end]
    if l.strip() and len(l) - len(l.lstrip()) < 8:
        break
    end += 1

body = textwrap.dedent('\n'.join(lines[start:end]))
assert body.lstrip().startswith('def app_loader_replacer'), body[:80]
# Sentinel for the re-entrant version: the piece-2 branch and the try/catch-aware
# inserter must both be present, or we are testing a stale copy of the function.
assert 'anchor_present' in body, "extracted a stale replacer (no anchor_present)"
assert '_match_block' in body, "extracted a stale replacer (no try/catch-aware inserter)"

ns = {}
exec(body, ns)
replacer = ns['app_loader_replacer']

# Real upstream shape: the anchor is present and the method has more statements.
FRESH = """    protected void onCreate() {
        super.onCreate();
        AndroidUtilities.checkDisplaySize(this, null);
        LauncherIconController.tryFixLauncherIconIfNeeded();
        ProxyRotationController.init();
    }

    public void anotherMethod() {
    }
"""

# Piece 1 already applied by an earlier version of this patch - the case that failed.
PIECE1 = """    protected void onCreate() {
        super.onCreate();
        AndroidUtilities.checkDisplaySize(this, null);
        LauncherIconController.tryFixLauncherIconIfNeeded();
        ProxyRotationController.init();

        // Colgram: initialise LAST, once AndroidUtilities, the native library and
        // the Java<->native bridge all exist. See the note in apply-patches.py -
        // this used to run before super.onCreate() and that was a real bug.
        try {
            org.colgram.core.ColgramHookHandler.init(applicationContext);
            org.colgram.core.ColgramUiBridge.install(this);
        } catch (Throwable ignore) {

        }
    }

    public void anotherMethod() {
    }
"""

results = []

r0 = replacer(FRESH)
results.append((
    "state 0 fresh",
    "ColgramUiBridge.install(this)" in r0 and "applyIpv4Policy" in r0
    and r0.count("{") == r0.count("}"),
    r0))

r1 = replacer(PIECE1)
results.append((
    "state 1 needs piece2",
    r1.count("ColgramUiBridge.install(this)") == 1
    and r1.count("applyIpv4Policy") == 1
    and "anotherMethod" in r1
    and r1.count("{") == r1.count("}"),
    r1))

r2 = replacer(r1)
results.append(("state 2 idempotent", r2 == r1, r2))

for name, ok, out in results:
    print("%-22s -> %-4s  braces %d/%d  install=%d ipv4=%d"
          % (name, "PASS" if ok else "FAIL", out.count("{"), out.count("}"),
             out.count("ColgramUiBridge.install(this)"), out.count("applyIpv4Policy")))

print()
print("---- state 1 result ----")
print(r1)
print()
print("ALL PASS" if all(ok for _, ok, _ in results) else "SOME FAILED")
