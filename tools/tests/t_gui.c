#include <windows.h>
static int clicks=0, ticks=0; static char typed[32]; static int nt=0;
static HWND btn;
LRESULT CALLBACK Wp(HWND h,UINT m,WPARAM w,LPARAM l){
  switch(m){
  case WM_CREATE: btn=CreateWindowA("BUTTON","Press",WS_CHILD|WS_VISIBLE,10,100,80,24,h,(HMENU)5,0,0); SetTimer(h,1,50,0); return 0;
  case WM_LBUTTONDOWN: clicks++; InvalidateRect(h,0,1); return 0;
  case WM_COMMAND: if(LOWORD(w)==5){clicks+=10; InvalidateRect(h,0,1);} return 0;
  case WM_CHAR: if(nt<31){typed[nt++]=(char)w; typed[nt]=0;} InvalidateRect(h,0,1); return 0;
  case WM_TIMER: ticks++; if(ticks==20) PostMessage(h,WM_CLOSE,0,0); return 0;
  case WM_PAINT:{ PAINTSTRUCT ps; HDC dc=BeginPaint(h,&ps); char b[100];
    HBRUSH br=CreateSolidBrush(RGB(200,30,30)); RECT r={10,10,110,60}; FillRect(dc,&r,br);
    Ellipse(dc,120,10,200,60);
    wsprintfA(b,"clicks=%d typed=%s",clicks,typed); TextOutA(dc,10,70,b,lstrlenA(b));
    EndPaint(h,&ps); return 0;}
  case WM_DESTROY: PostQuitMessage(7); return 0;
  }
  return DefWindowProcA(h,m,w,l);
}
int main(void){
  WNDCLASSA wc={0}; wc.lpfnWndProc=Wp; wc.hInstance=GetModuleHandleA(0); wc.lpszClassName="T"; wc.hbrBackground=(HBRUSH)(COLOR_WINDOW+1);
  RegisterClassA(&wc);
  HWND h=CreateWindowA("T","Test",WS_OVERLAPPEDWINDOW|WS_VISIBLE,50,50,300,220,0,0,wc.hInstance,0);
  ShowWindow(h,SW_SHOW); UpdateWindow(h);
  MSG m; while(GetMessage(&m,0,0,0)){TranslateMessage(&m);DispatchMessage(&m);}
  MessageBoxA(0,"done","t",0);
  return (int)m.wParam;
}
