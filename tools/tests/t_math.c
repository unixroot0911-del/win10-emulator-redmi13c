#include <stdio.h>
#include <math.h>
#include <stdlib.h>
typedef long long ll;
static double poly(double x){ return ((2.5*x+1.25)*x-3.0)*x+0.125; }
int main(void){
    double x=0.5; float f=1.5f; ll big=1234567890123LL;
    printf("%f %f %f\n", sin(x), cos(x), sqrt(2.0));
    printf("%.4f %.4f %.4f\n", pow(2.0,10.5), floor(-2.5), fmod(10.5,3.0));
    printf("%e %g %g\n", 12345.6789, 0.0001234, 1e10);
    printf("%5.2f|%-8.3f|%08.3f\n", 3.14159, 2.71828, -1.5);
    printf("%lld %llx %d\n", big*7, big, (int)(big%1000));
    printf("%d %d\n", (int)(3.99), (int)(-3.99));
    printf("%.3f %.3f\n", poly(1.5), poly(-0.75)+f);
    double s=0; for(int i=1;i<=1000;i++) s+=1.0/i; printf("%.10f\n", s);
    ll fact=1; for(int i=1;i<=20;i++) fact*=i; printf("%lld\n", fact);
    unsigned u=4000000000u; printf("%u %u\n", u, u/7);
    printf("%.2f %.2f\n", atan2(1.0,2.0), exp(1.0));
    printf("%d\n", (int)lround(2.5));
    return 0;
}
