#include <stdio.h>
#include <string.h>
int main(void){
    FILE*f=fopen("out_test.txt","w"); if(!f){puts("open fail");return 1;}
    for(int i=0;i<5;i++) fprintf(f,"line %d: %s\n",i,"data");
    fwrite("RAW\0BYTES",1,9,f); fclose(f);
    f=fopen("out_test.txt","r"); char line[100]; int n=0;
    while(fgets(line,sizeof line,f)&&n<5){ fputs(line,stdout); n++; }
    long pos=ftell(f); printf("pos=%ld\n",pos);
    char raw[16]={0}; size_t got=fread(raw,1,9,f); printf("got=%d %s\n",(int)got,raw);
    fseek(f,0,SEEK_SET); int c=fgetc(f); printf("first=%c\n",c);
    fseek(f,0,SEEK_END); printf("size=%ld\n",ftell(f)); fclose(f);
    printf("missing=%d\n", fopen("nope_missing.txt","r")==NULL);
    remove("out_test.txt");
    return 0;
}
