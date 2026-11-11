#!/bin/bash
# Differential tests: build each program with zig (Windows x86) and gcc (native), run the Windows build on the emulator, compare outputs.
# usage: tools/tests/run_tests.sh <emulator-classes-dir> <work-dir>
CLS=$1; W=$2; HERE=$(cd "$(dirname "$0")" && pwd); mkdir -p "$W"; cd "$W"
export ZIG_GLOBAL_CACHE_DIR=$W/zc ZIG_LOCAL_CACHE_DIR=$W/zl
pass=0; fail=0
for opt in -O0 -O2; do
  for t in t_math t_str t_file t_sse; do
    python3 -m ziglang cc -target x86-windows-gnu $opt -o $t$opt.exe $HERE/$t.c 2>zerr.txt || { echo "ZIG BUILD FAIL $t$opt"; head -3 zerr.txt; fail=$((fail+1)); continue; }
    gcc $opt -w -o $t$opt.native $HERE/$t.c -lm 2>/dev/null && ./$t$opt.native > $t$opt.expected 2>&1
    java -cp $CLS Test $t$opt.exe 2>&1 | grep -v "Picked up" | grep -v "^exit=" > $t$opt.actual
    if diff -q $t$opt.expected $t$opt.actual >/dev/null; then echo "PASS $t$opt"; pass=$((pass+1)); else echo "FAIL $t$opt"; diff $t$opt.expected $t$opt.actual | head -6; fail=$((fail+1)); fi
  done
done
python3 -m ziglang cc -target x86-windows-gnu -O1 -shared -o mydll.dll $HERE/mydll.c 2>zerr.txt && python3 -m ziglang cc -target x86-windows-gnu -O1 -o t_dll.exe $HERE/t_dll.c -L. -lmydll 2>>zerr.txt
java -cp $CLS Test t_dll.exe 2>&1 | grep -v "Picked up" | grep -v "^exit=" > dll.actual
printf '[dll attach]\n42 hello from mydll 1 2\n' > dll.expected
if diff -q dll.expected dll.actual >/dev/null; then echo "PASS dll"; pass=$((pass+1)); else echo "FAIL dll"; diff dll.expected dll.actual | head -6; fail=$((fail+1)); fi
echo "passed=$pass failed=$fail"
# WPF: python3 tools/make_dotnet.py wpf.exe wpf && java -cp out:g ClrGui wpf.exe wpf.png   (WPF_DEBUG=1 logs WPF API calls; CLR_DEBUG=1 prints native NPE traces)
