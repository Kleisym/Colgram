#include <stdio.h>
#include <dlfcn.h>
int main(int argc,char**argv){
  void*h=dlopen("/data/local/uq/libcolgrammasque.so",2);
  if(!h){printf("dlopen FAIL: %s\n",dlerror());return 1;}
  char*(*m)(char*,char*,char*)=dlsym(h,"colgram_masque_measure");
  char*(*e)()=dlsym(h,"colgram_masque_last_error");
  char bind[64]={0}, edge[64]={0}, port[16]={0};
  if(argc>1) snprintf(bind,sizeof bind,"%s",argv[1]);
  if(argc>2) snprintf(edge,sizeof edge,"%s",argv[2]);
  if(argc>3) snprintf(port,sizeof port,"%s",argv[3]);
  printf("calling measure bind=%s edge=%s port=%s\n",bind,edge,port);
  char*r=m(bind,edge,port);
  if(!r){printf("measure FAILED: %s\n",e());return 2;}
  printf("--- trace ---\n%s\n",r);
  return 0;
}
