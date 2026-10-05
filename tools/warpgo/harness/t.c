#include <stdio.h>
#include <dlfcn.h>
int main(){
  void*h=dlopen("/data/local/uq/libcolgrammasque.so",2);
  if(!h){printf("dlopen FAIL: %s\n",dlerror());return 1;}
  char*(*v)()=dlsym(h,"colgram_masque_version");
  if(!v){printf("dlsym FAIL: %s\n",dlerror());return 2;}
  printf("loaded, version=%s\n",v());
  char*(*m)(char*,char*,char*)=dlsym(h,"colgram_masque_measure");
  if(!m){printf("measure sym missing\n");return 3;}
  char*(*e)()=dlsym(h,"colgram_masque_last_error");
  printf("measure sym ok\n");
  return 0;
}
