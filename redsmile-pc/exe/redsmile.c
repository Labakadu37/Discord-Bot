/*
 * RedSmile PC - v1 (pour le fun, usage perso)
 * Fenetre noire avec le smiley rouge dessine. Avertissement d'abord, petit son,
 * Echap ou fermer pour quitter. Rien n'est installe, rien touche au systeme.
 * Compilation : x86_64-w64-mingw32-gcc redsmile.c -o RedSmile.exe -mwindows -lgdi32 -lwinmm
 */
#include <windows.h>
#include <math.h>

static void draw_smile(HDC hdc, int w, int h)
{
    /* Fond noir */
    RECT full = {0, 0, w, h};
    HBRUSH black = CreateSolidBrush(RGB(0, 0, 0));
    FillRect(hdc, &full, black);
    DeleteObject(black);

    int cx = w / 2, cy = h / 2;
    int r = (w < h ? w : h) / 4;           /* rayon du cercle */

    HPEN pen = CreatePen(PS_SOLID, r / 12 + 2, RGB(210, 20, 20));
    HPEN old = SelectObject(hdc, pen);
    HBRUSH hollow = GetStockObject(NULL_BRUSH);
    HBRUSH oldb = SelectObject(hdc, hollow);

    /* Cercle du visage */
    Arc(hdc, cx - r, cy - r, cx + r, cy + r, 0, 0, 0, 0);

    SelectObject(hdc, oldb);
    HBRUSH red = CreateSolidBrush(RGB(210, 20, 20));
    SelectObject(hdc, red);

    /* Yeux */
    int ex = r / 3, ey = r / 3, es = r / 7;
    Ellipse(hdc, cx - ex - es, cy - ey - es, cx - ex + es, cy - ey + es);
    Ellipse(hdc, cx + ex - es, cy - ey - es, cx + ex + es, cy - ey + es);

    /* Sourire (arc du bas) */
    SelectObject(hdc, hollow);
    Arc(hdc, cx - r / 2, cy - r / 4, cx + r / 2, cy + r / 2,
        cx + r / 2, cy + r / 4, cx - r / 2, cy + r / 4);

    SelectObject(hdc, old);
    SelectObject(hdc, oldb);
    DeleteObject(pen);
    DeleteObject(red);
}

LRESULT CALLBACK WndProc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp)
{
    switch (msg) {
        case WM_PAINT: {
            PAINTSTRUCT ps;
            HDC hdc = BeginPaint(hwnd, &ps);
            RECT rc; GetClientRect(hwnd, &rc);
            /* Double buffering pour eviter le clignotement */
            HDC mem = CreateCompatibleDC(hdc);
            HBITMAP bmp = CreateCompatibleBitmap(hdc, rc.right, rc.bottom);
            HBITMAP oldbmp = SelectObject(mem, bmp);
            draw_smile(mem, rc.right, rc.bottom);
            BitBlt(hdc, 0, 0, rc.right, rc.bottom, mem, 0, 0, SRCCOPY);
            SelectObject(mem, oldbmp);
            DeleteObject(bmp);
            DeleteDC(mem);
            EndPaint(hwnd, &ps);
            return 0;
        }
        case WM_KEYDOWN:
            if (wp == VK_ESCAPE) PostMessage(hwnd, WM_CLOSE, 0, 0);
            return 0;
        case WM_SIZE:
            InvalidateRect(hwnd, NULL, FALSE);
            return 0;
        case WM_DESTROY:
            PostQuitMessage(0);
            return 0;
    }
    return DefWindowProc(hwnd, msg, wp, lp);
}

int WINAPI WinMain(HINSTANCE hInst, HINSTANCE prev, LPSTR cmd, int show)
{
    /* 1. Avertissement : rien ne demarre avant le clic */
    int ok = MessageBox(NULL,
        "Plusieurs fenetres peuvent apparaitre et un son peut se declencher.\n"
        "C'est juste pour le fun : rien n'est installe, rien n'est modifie sur le PC.\n\n"
        "Appuie sur Echap ou ferme la fenetre pour quitter a tout moment.\n\n"
        "Lancer RedSmile ?",
        "RedSmile - Avertissement", MB_OKCANCEL | MB_ICONWARNING);
    if (ok != IDOK) return 0;

    /* 2. Petit son */
    Beep(600, 150); Beep(400, 200);

    const char cls[] = "RedSmileWindow";
    WNDCLASS wc = {0};
    wc.lpfnWndProc = WndProc;
    wc.hInstance = hInst;
    wc.lpszClassName = cls;
    wc.hCursor = LoadCursor(NULL, IDC_ARROW);
    wc.hbrBackground = (HBRUSH)GetStockObject(BLACK_BRUSH);
    RegisterClass(&wc);

    HWND hwnd = CreateWindow(cls, "RedSmile", WS_OVERLAPPEDWINDOW,
        CW_USEDEFAULT, CW_USEDEFAULT, 520, 560, NULL, NULL, hInst, NULL);
    ShowWindow(hwnd, show);
    UpdateWindow(hwnd);

    MSG m;
    while (GetMessage(&m, NULL, 0, 0) > 0) {
        TranslateMessage(&m);
        DispatchMessage(&m);
    }
    return 0;
}
