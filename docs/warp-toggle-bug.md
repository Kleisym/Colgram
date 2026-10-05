# Why the WARP switch only turned blue after leaving and re-entering the screen

## The cause

ColgramSettingsActivity.startWarpTunnel() wrote the "WARP is on" flag in the success branch, AFTER
bringUp() returned without throwing:

    new Thread(() -> {
        try {
            ColgramWarpTunnel.bringUp(context.getApplicationContext());
            failure = null;
        } catch (Throwable t) { ... }
        h.post(() -> {
            if (result == null) {
                ColgramConfig.setWarpEnabled(true);   // <-- here
                ...
            }
        });
    }).start();

So the switch was gated on the tunnel actually coming up. And coming up is not a quick operation:
ColgramWarpMasqueTunnel.bringUp() opens a QUIC session, runs a TLS handshake and reads a response
through it, with a 75-second ceiling, and on a network whose egress the edge does not answer it
spends that whole budget before failing.

The visible result was exactly what was reported: tap the switch, wait, nothing happens, and the row
only appears active later - after the screen was left and re-entered, because by then a *previous*
start had either succeeded or been rolled back by the restore path in ColgramHookHandler.

## The fix

The flag is now written on the tap, in startWarpTunnel(), before the thread starts:

    ColgramConfig.setWarpEnabled(true);
    if (listAdapter != null) listAdapter.notifyDataSetChanged();

Optimistic, with a deadline rather than a switch that lies. Two things keep it honest:

-   a declined VPN consent or a missing backend still lands in the failure branch, which turns it
    back off and says why
-   watchWarpVerdict() now also turns it off when the route is judged dead. It previously only showed
    a toast, which was fine while the flag was written after success, but would leave the optimistic
    state in place forever. With the new order it has to write the flag itself, or the row would
    claim WARP is on when nothing is carrying it - the same half-applied state in the other
    direction.

## Verified

    :TMessagesProj:compileStandaloneJavaWithJavac   BUILD SUCCESSFUL in 50s

## Note on the rest of this item

The state line the row shows was already reading through ColgramWarpTunnel, which now prefers the
MASQUE path, so the switch and the verdict describe the same transport the native client actually
uses.
