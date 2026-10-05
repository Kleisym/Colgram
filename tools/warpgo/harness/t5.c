#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <dlfcn.h>

// Drives the native client on the device with no relay process on the host, so the client has to
// find its own route. argv[3] is the egress address the in-app relay binds its upstream socket to.
int main(int argc, char **argv) {
  void *m = dlopen("/data/local/uq/libcolgrammasque.so", 2);
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
  printf("version: %s\n", version());

  char bind[64] = {0};
  char edge[64] = {0};
  char port[8] = {0};
  char relayBind[64] = {0};
  if (argc > 1) snprintf(bind, sizeof bind, "%s", argv[1]);
  if (argc > 2) snprintf(edge, sizeof edge, "%s", argv[2]);
  if (argc > 3 && argv[3][0]) snprintf(relayBind, sizeof relayBind, "%s", argv[3]);

  // Passed through the library rather than the environment: a c-shared library snapshots the
  // environment when it is loaded, so a variable exported afterwards is invisible to it.
  const char *socks = getenv("WARP_SOCKS");
  if (socks && socks[0] && setSocks) {
    setSocks((char *)socks);
    printf("socks set: %s\n", socks);
  }

  char *r = measure(bind, edge, port, relayBind);
  if (!r) {
    printf("FAILED: %s\n", lastError());
    return 3;
  }
  printf("%s\n", r);
  return 0;
}
