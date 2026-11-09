#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <ctype.h>
struct P { char name[16]; int age; double score; };
static int cmp_age(const void*a,const void*b){ return ((const struct P*)a)->age-((const struct P*)b)->age; }
static int cmp_int(const void*a,const void*b){ return *(const int*)a-*(const int*)b; }
static int fib(int n){ return n<2?n:fib(n-1)+fib(n-2); }
int main(void){
    struct P ps[5]={{"ann",31,1.5},{"bob",22,2.5},{"cy",45,3.5},{"di",19,4.5},{"ed",38,5.5}};
    qsort(ps,5,sizeof ps[0],cmp_age);
    for(int i=0;i<5;i++) printf("%s %d %.1f\n", ps[i].name, ps[i].age, ps[i].score);
    int a[]={9,3,7,1,8,2,6}; qsort(a,7,sizeof(int),cmp_int);
    for(int i=0;i<7;i++) printf("%d ", a[i]); printf("\n");
    int key=7; int*r=bsearch(&key,a,7,sizeof(int),cmp_int); printf("found %d at %d\n", *r, (int)(r-a));
    char buf[64]; sprintf(buf,"%s-%03d-%c-%x","abc",42,'z',255); puts(buf);
    printf("%d %d %ld\n", atoi("  123abc"), (int)strtol("ff",NULL,16), strtol("-77",NULL,10));
    char s[32]="Hello, World"; for(char*p=s;*p;p++) *p=toupper(*p); puts(s);
    printf("%d %s %s\n", (int)strlen(s), strchr(s,'W'), strstr(s,"LO"));
    char *m=malloc(100); memset(m,'x',99); m[99]=0; printf("%d\n",(int)strlen(m));
    char *m2=malloc(16); strcpy(m2,"dup"); strcat(m2,"!"); puts(m2); free(m); free(m2);
    printf("fib(24)=%d\n", fib(24));
    long sum=0; for(int i=0;i<200000;i++){ char*t=malloc(16+(i%50)); t[0]=i; sum+=t[0]; free(t);} printf("sum=%ld\n",sum);
    printf("%s|%10s|%-6s|%.2s\n","a","right","left","trunc");
    return 0;
}
