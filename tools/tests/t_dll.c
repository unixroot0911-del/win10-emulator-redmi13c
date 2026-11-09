#include <stdio.h>
__declspec(dllimport) int add(int,int);
__declspec(dllimport) const char* greet(void);
__declspec(dllimport) int bump(void);
int main(void){ printf("%d %s %d %d\n", add(40,2), greet(), bump(), bump()); return 0; }
