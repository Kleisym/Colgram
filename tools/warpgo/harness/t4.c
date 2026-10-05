#include <stdio.h>
#include <dlfcn.h>
int main(){
  void*b=dlopen("/data/local/uq/libbox.so",2);
  printf("libbox: %s\n", b?"loaded":dlerror()); fflush(stdout);
  void*m=dlopen("/data/local/uq/libcolgrammasque.so",2);
  printf("masque: %s\n", m?"loaded":dlerror()); fflush(stdout);
  if(!m) return 2;
  char*(*me)(char*,char*,char*)=dlsym(m,"colgram_masque_measure");
  char*(*er)()=dlsym(m,"colgram_masque_last_error");
  char bind[64]="10.0.2.15", edge[64]="10.0.2.2:14501", port[8]="";
  printf("measuring with both runtimes loaded...\n"); fflush(stdout);
  char*r=me(bind,edge,port);
  printf("result: %s\n", r?r:er()); fflush(stdout);
  return r?0:3;
}
