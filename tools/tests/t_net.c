#include <winsock2.h>
#include <ws2tcpip.h>
#include <stdio.h>
#include <string.h>
int main(int argc, char **argv) {
  WSADATA w; if (WSAStartup(MAKEWORD(2,2), &w)) { printf("wsa fail\n"); return 1; }
  struct addrinfo hints, *res; memset(&hints, 0, sizeof hints); hints.ai_family = AF_INET; hints.ai_socktype = SOCK_STREAM;
  if (getaddrinfo(argv[1], argv[2], &hints, &res)) { printf("getaddrinfo fail %d\n", WSAGetLastError()); return 2; }
  SOCKET s = socket(AF_INET, SOCK_STREAM, 0);
  if (connect(s, res->ai_addr, (int)res->ai_addrlen) != 0) { printf("connect fail %d\n", WSAGetLastError()); return 3; }
  const char *req = "GET /hello.txt HTTP/1.0\r\nHost: x\r\n\r\n";
  send(s, req, (int)strlen(req), 0);
  char buf[512]; int n, total = 0;
  while ((n = recv(s, buf, sizeof buf - 1, 0)) > 0) { buf[n] = 0; fputs(buf, stdout); total += n; }
  printf("\n[recv total %d]\n", total);
  closesocket(s); WSACleanup(); return 0;
}
