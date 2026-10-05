// Loads the MASQUE client out of an installed APK and runs it on the device.
//
// This exists because "warp=on from the device" and "warp=on from the app's own binary" are two
// different claims, and only the second one is the goal. The library a test harness dlopen()s off
// /data/local/tmp is a file someone copied onto the device; this one is extracted from the APK the
// device actually installed, so the bytes that answer are the bytes the app ships.
//
// argv[1] overrides the library path, which is what makes that checkable: the caller points it at
// the .so pulled out of /data/app/.../base.apk rather than at a local copy.
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <dlfcn.h>

static const char *LIB = "/data/local/uq/apkso/libcolgrammasque.so";

int main(int argc, char **argv) {
  const char *lib = (argc > 1 && argv[1][0]) ? argv[1] : LIB;
  void *m = dlopen(lib, RTLD_NOW);
  if (!m) {
    printf("dlopen FAIL: %s\n", dlerror());
    return 1;
  }
  char *(*measure)(char *, char *, char *, char *) = dlsym(m, "colgram_masque_measure");
  char *(*lastError)(void) = dlsym(m, "colgram_masque_last_error");
  char *(*version)(void) = dlsym(m, "colgram_masque_version");
  void (*setSocks)(char *) = dlsym(m, "colgram_masque_set_socks");
  if (!measure || !lastError || !version) {
    printf("dlsym FAIL: %s\n", dlerror());
    return 2;
  }
  printf("library : %s\n", lib);
  printf("version : %s\n", version());

  char bind[64] = {0};
  char edge[64] = {0};
  char port[8] = {0};
  char relayBind[64] = {0};
  if (argc > 2) snprintf(bind, sizeof bind, "%s", argv[2]);
  if (argc > 3) snprintf(edge, sizeof edge, "%s", argv[3]);
  if (argc > 4 && argv[4][0]) snprintf(relayBind, sizeof relayBind, "%s", argv[4]);

  // Through the library, not the environment. A c-shared library snapshots the environment when it
  // is loaded, so a variable exported afterwards is invisible to it - measured, not assumed: the
  // same process printed WARP_SOCKS="" while a shell in the same invocation printed the value.
  const char *socks = getenv("WARP_SOCKS");
  if (socks && socks[0] && setSocks) {
    setSocks((char *)socks);
    printf("socks   : %s\n", socks);
  }

  char *r = measure(bind, edge, port, relayBind);
  if (!r) {
    printf("FAILED: %s\n", lastError());
    return 3;
  }
  printf("%s\n", r);
  return 0;
}
