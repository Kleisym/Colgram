#include <stdio.h>
#include <dlfcn.h>
int main(){
  printf("dlopen libbox...\n"); fflush(stdout);
  void*b=dlopen("/data/local/uq/libbox.so",2);
  printf("libbox: %s\n", b?"loaded":dlerror()); fflush(stdout);
  if(!b) return 1;
  printf("dlopen libcolgrammasque...\n"); fflush(stdout);
  void*m=dlopen("/data/local/uq/libcolgrammasque.so",2);
  printf("libcolgrammasque: %s\n", m?"loaded":dlerror()); fflush(stdout);
  if(!m) return 2;
  char*(*v)()=dlsym(m,"colgram_masque_version");
  printf("call version -> %s\n", v?v():"(null)"); fflush(stdout);
  return 0;
}
