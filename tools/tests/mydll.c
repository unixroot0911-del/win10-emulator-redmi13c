#include <stdio.h>
__declspec(dllexport) int add(int a,int b){ return a+b; }
__declspec(dllexport) const char* greet(void){ return "hello from mydll"; }
static int counter;
__declspec(dllexport) int bump(void){ return ++counter; }
int __stdcall DllMain(void*h,unsigned long r,void*x){ if(r==1) printf("[dll attach]\n"); return 1; }
