#!/bin/sh
# Compile RedSmile.exe (vrai .exe Windows) depuis Linux avec mingw-w64.
# Besoin : apt-get install mingw-w64
x86_64-w64-mingw32-gcc redsmile.c -o RedSmile.exe -mwindows -lgdi32 -lwinmm -O2 -s
