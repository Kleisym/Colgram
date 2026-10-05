# Three reported bugs, 2026-10-02

## 1. The WARP toggle never turned on

It was set on the tap and then immediately overwritten:

```java
textCheckCell.setChecked(true);
startWarpFromProxyScreen();
...
updateRows(true);          // rebuilds every row from the stored flag
```

updateRows re-binds warpRow as

```java
checkCell.setChecked(ColgramConfig.isWarpEnabled());
```

and that flag is deliberately not written until the tunnel is really up, so a second later the
row is drawn off again. The switch snapped back on every attempt, which reads as a toggle that
does nothing. Both bind sites now include the start in flight:

```java
checkCell.setChecked(ColgramConfig.isWarpEnabled() || warpStartPending);
```

The pending state was already tracked -- the tap handler reads it to tell a first tap from a
cancel -- it was simply never drawn.

There is also no WARP toggle at all in the Colgram settings activity: cloudflareWarpRow is a
status line, drawn with setTextAndValue and false for the checkmark. The real toggle lives in the
proxy list, which is where you are already looking when proxies are the problem.

## 2. No subscription field and no bypass in the proxy menu

Both existed and worked, in ColgramSettingsActivity. The proxy list had neither:

```
useProxyRow / callsRow / warpRow / connectionsHeaderRow   <- that was all
```

Added there now: a subscription row with its status line, and a built-in bypass row with its
status line, above the server list.

The subscription row pastes a link and reports what it produced -- servers and profile name --
rather than that a link is stored, because a link that parses to nothing is stored and accepted
and would otherwise read as connected. The bypass row toggles immediately and drops any selected
proxy so the next connection takes the bypass path.

## 3. Almost no proxy works

Not a Colgram bug, and the logs name the part that is not:

```
proxy harvest: 193 -> 241 Telegram candidates; reachable relays=16
ColgramProxyChain: SOCKS5 front 127.0.0.1:35037 -> socks5://184.178.172.5:15303
Connect timed out; SOCKS server general failure; failed to connect after 1800ms
```

The app harvests hundreds of public SOCKS5 servers, finds a couple of dozen that answer, and the
rest time out. That is what free public proxies do; the harvest is working. The ones that answered
then failed from /172.16.0.2, which is the tunnel source address, so those that worked before the
tunnel stopped being usable once it came up.

The built-in bypass is the route that does not depend on somebody else server. The in-process
front reaches the edge and the resolvers from this device alone:

```
I ColgramUdpTunnel: connected to 1.1.1.1:443 from /10.0.2.15:20913
I ColgramUdpTunnel: connected to 162.159.198.2:443 from /10.0.2.15:60281
```

It is on the same screen now, one tap from the list that does not work.

## Verified, and not

Verified by reading the code and building it: the toggle draw order, and the four new rows. The
build is green and the APK is installed.

Not verified by a tap: this account is not authenticated, so every screen behind the login is
unreachable on this device. The last screenshot shows the phone-number entry, and that is as far
as the app goes without a session.
