#include <stdio.h>
#include <string.h>
#define N 64
static float a[N], b[N], c[N];
static double d[N];
static unsigned char u8[N];
int main(void){
    for(int i=0;i<N;i++){ a[i]=i*0.5f; b[i]=N-i; d[i]=i*1.25; u8[i]=(unsigned char)(i*7); }
    for(int i=0;i<N;i++) c[i]=a[i]*b[i]+1.0f;
    float s=0; for(int i=0;i<N;i++) s+=c[i]; printf("%.2f\n", s);
    double t=0; for(int i=0;i<N;i++) t+=d[i]*d[i]; printf("%.3f\n", t);
    unsigned sum=0; for(int i=0;i<N;i++) sum+=u8[i]; printf("%u\n", sum);
    int mx=0; for(int i=0;i<N;i++) if(u8[i]>mx) mx=u8[i]; printf("%d\n", mx);
    char x[64], y[64]; memset(x,7,64); memcpy(y,x,64); printf("%d\n", memcmp(x,y,64));
    float m=a[0]; for(int i=1;i<N;i++) m = a[i]>m?a[i]:m; printf("%.1f\n", m);
    printf("%d %d\n", (int)(s/3), (int)(float)t);
    return 0;
}
