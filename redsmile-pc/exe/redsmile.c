/*
 * RedSmile PC v4 - Win32 natif, toujours premier plan
 * - Vrai logo RedSmile en bitmap (ressource embarquee)
 * - Clones qui rebondissent partout
 * - Popups Win32 avec messages flippants
 * - Sirene (Beep)
 * - Fond d'ecran change au lancement
 * - IMPOSSIBLE de fermer sauf barre des taches
 * - Toujours au-dessus de tout (TOPMOST)
 */
#define _WIN32_WINNT 0x0600
#include <windows.h>
#include <math.h>
#include <stdlib.h>
#include <time.h>

#define TIMER_ANIM   1
#define TIMER_POPUP  2
#define TIMER_BEEP   3
#define TIMER_TOPMOST 4
#define MAX_CLONES   35
#define MAX_POPUPS   40
#define PI 3.14159265f

/* Messages de popup */
static const wchar_t *MSGS[] = {
    L"Je te vois \U0001F441",
    L"Tu ne peux pas m'arrêter \U0001F608",
    L"RedSmile est partout",
    L"ERREUR 666 : trop tard \U0001F480",
    L"Ton PC m'appartient \U0001F608",
    L"Je reviens toujours...",
    L"Tu croyais pouvoir fuir ? \U0001F480",
    L"ALERTE ROUGE \U0001F534",
    L"Regarde derrière toi...",
    L"404 : sortie introuvable",
    L"SYSTEM COMPROMIS",
    L"RedSmile.exe a pris le contrôle",
    L"IMPOSSIBLE DE FERMER",
    L"Encore un popup ? \U0001F608\U0001F608",
    L"On s'amuse bien ?",
    L"Surprise ! \U0001F47B",
};
#define MSG_COUNT (sizeof(MSGS)/sizeof(MSGS[0]))

typedef struct {
    float x, y, vx, vy, size, rot, rotSpeed, alpha;
} Clone;

static Clone clones[MAX_CLONES];
static int numClones = 0;
static HBITMAP hSmileBmp = NULL;
static int bmpW = 0, bmpH = 0;
static HWND mainWnd = NULL;
static HWND popupWnds[MAX_POPUPS];
static int numPopups = 0;
static float mainPulse = 1.0f;
static int pulseDir = 1;
static int screenW, screenH;
static int beepOn = 1;
static int beepFreq = 420;
static int beepDir = 1;

static float randf(float a, float b) { return a + (float)rand() / RAND_MAX * (b - a); }

static void initClones(void) {
    numClones = MAX_CLONES;
    for (int i = 0; i < MAX_CLONES; i++) {
        float sz = 30.0f + randf(0, 80);
        float angle = randf(0, 2 * PI);
        float speed = randf(80, 300);
        clones[i].x = randf(0, (float)screenW);
        clones[i].y = randf(0, (float)screenH);
        clones[i].vx = cosf(angle) * speed;
        clones[i].vy = sinf(angle) * speed;
        clones[i].size = sz;
        clones[i].rot = randf(0, 360);
        clones[i].rotSpeed = randf(-150, 150);
        clones[i].alpha = 150 + rand() % 105;
    }
}

static void setRedWallpaper(void) {
    /* Creer un BMP rouge temporaire pour le fond d'ecran */
    wchar_t tmp[MAX_PATH], path[MAX_PATH];
    GetTempPathW(MAX_PATH, tmp);
    wsprintfW(path, L"%sRedSmile_wall.bmp", tmp);

    /* Bitmap rouge avec le smiley au centre */
    int w = screenW, h = screenH;
    BITMAPINFOHEADER bi = {0};
    bi.biSize = sizeof(bi);
    bi.biWidth = w;
    bi.biHeight = h;
    bi.biPlanes = 1;
    bi.biBitCount = 24;
    bi.biCompression = BI_RGB;
    int rowBytes = ((w * 3 + 3) & ~3);
    int dataSize = rowBytes * h;
    BITMAPFILEHEADER bf = {0};
    bf.bfType = 0x4D42;
    bf.bfOffBits = sizeof(bf) + sizeof(bi);
    bf.bfSize = bf.bfOffBits + dataSize;

    unsigned char *pixels = (unsigned char *)calloc(dataSize, 1);
    if (!pixels) return;
    /* Fond noir avec nuance rouge au centre */
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            float dx = (float)(x - w/2) / (w/2);
            float dy = (float)(y - h/2) / (h/2);
            float d = sqrtf(dx*dx + dy*dy);
            int r = (int)(30 * (1.0f - d * 0.5f));
            if (r < 0) r = 0;
            unsigned char *p = pixels + y * rowBytes + x * 3;
            p[0] = 0; p[1] = 0; p[2] = (unsigned char)r;
        }
    }

    /* Dessiner le smiley au centre du wallpaper */
    if (hSmileBmp) {
        HDC screenDC = GetDC(NULL);
        HDC memDC = CreateCompatibleDC(screenDC);
        HDC wallDC = CreateCompatibleDC(screenDC);
        HBITMAP wallBmp = CreateCompatibleBitmap(screenDC, w, h);
        SelectObject(wallDC, wallBmp);
        /* Remplir le fond */
        BITMAPINFO bmi = {0};
        bmi.bmiHeader = bi;
        SetDIBitsToDevice(wallDC, 0, 0, w, h, 0, 0, 0, h, pixels, &bmi, DIB_RGB_COLORS);
        /* Dessiner le logo au centre */
        SelectObject(memDC, hSmileBmp);
        int logoSize = min(w, h) / 3;
        int lx = (w - logoSize) / 2, ly = (h - logoSize) / 2;
        SetStretchBltMode(wallDC, HALFTONE);
        StretchBlt(wallDC, lx, ly, logoSize, logoSize, memDC, 0, 0, bmpW, bmpH, SRCCOPY);
        /* Sauvegarder comme BMP */
        BITMAPINFOHEADER bi2 = {0};
        bi2.biSize = sizeof(bi2); bi2.biWidth = w; bi2.biHeight = h;
        bi2.biPlanes = 1; bi2.biBitCount = 24; bi2.biCompression = BI_RGB;
        unsigned char *px2 = (unsigned char *)malloc(dataSize);
        GetDIBits(wallDC, wallBmp, 0, h, px2, &bmi, DIB_RGB_COLORS);
        HANDLE f = CreateFileW(path, GENERIC_WRITE, 0, NULL, CREATE_ALWAYS, 0, NULL);
        if (f != INVALID_HANDLE_VALUE) {
            DWORD wr;
            WriteFile(f, &bf, sizeof(bf), &wr, NULL);
            WriteFile(f, &bi2, sizeof(bi2), &wr, NULL);
            WriteFile(f, px2, dataSize, &wr, NULL);
            CloseHandle(f);
        }
        free(px2);
        DeleteDC(memDC);
        DeleteDC(wallDC);
        DeleteObject(wallBmp);
        ReleaseDC(NULL, screenDC);
    }
    free(pixels);

    SystemParametersInfoW(SPI_SETDESKWALLPAPER, 0, path, SPIF_UPDATEINIFILE | SPIF_SENDCHANGE);
}

/* Popup Win32 toujours au-dessus */
static LRESULT CALLBACK PopupProc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    switch (msg) {
    case WM_PAINT: {
        PAINTSTRUCT ps;
        HDC hdc = BeginPaint(hwnd, &ps);
        RECT rc; GetClientRect(hwnd, &rc);

        /* Barre rouge */
        RECT bar = {0, 0, rc.right, 30};
        HBRUSH brBar = CreateSolidBrush(RGB(142, 0, 0));
        FillRect(hdc, &bar, brBar); DeleteObject(brBar);
        SetBkMode(hdc, TRANSPARENT); SetTextColor(hdc, RGB(255,255,255));
        HFONT fBold = CreateFontW(14, 0, 0, 0, FW_BOLD, 0, 0, 0, DEFAULT_CHARSET, 0, 0, 0, 0, L"Segoe UI");
        SelectObject(hdc, fBold);
        TextOutW(hdc, 8, 7, L"RedSmile.exe", 12);
        DeleteObject(fBold);

        /* Body noir */
        RECT body = {0, 30, rc.right, rc.bottom};
        HBRUSH brBody = CreateSolidBrush(RGB(20, 20, 20));
        FillRect(hdc, &body, brBody); DeleteObject(brBody);

        /* Logo */
        if (hSmileBmp) {
            HDC mem = CreateCompatibleDC(hdc);
            SelectObject(mem, hSmileBmp);
            SetStretchBltMode(hdc, HALFTONE);
            StretchBlt(hdc, 10, 40, 50, 50, mem, 0, 0, bmpW, bmpH, SRCCOPY);
            DeleteDC(mem);
        }

        /* Message */
        wchar_t *txt = (wchar_t *)GetWindowLongPtrW(hwnd, GWLP_USERDATA);
        if (txt) {
            RECT tr = {70, 40, rc.right - 10, rc.bottom - 10};
            SetTextColor(hdc, RGB(255, 255, 255));
            HFONT fMsg = CreateFontW(16, 0, 0, 0, FW_NORMAL, 0, 0, 0, DEFAULT_CHARSET, 0, 0, 0, 0, L"Segoe UI");
            SelectObject(hdc, fMsg);
            DrawTextW(hdc, txt, -1, &tr, DT_WORDBREAK | DT_LEFT);
            DeleteObject(fMsg);
        }

        /* Croix */
        RECT xr = {rc.right - 28, 4, rc.right - 4, 26};
        HBRUSH brX = CreateSolidBrush(RGB(198, 40, 40));
        FillRect(hdc, &xr, brX); DeleteObject(brX);
        SetTextColor(hdc, RGB(255,255,255));
        DrawTextW(hdc, L"X", 1, &xr, DT_CENTER | DT_VCENTER | DT_SINGLELINE);

        EndPaint(hwnd, &ps);
        return 0;
    }
    case WM_LBUTTONDOWN: {
        /* Click sur X = spawn 2 de plus */
        POINT pt; GetCursorPos(&pt); ScreenToClient(hwnd, &pt);
        RECT rc; GetClientRect(hwnd, &rc);
        if (pt.x > rc.right - 28 && pt.y < 26) {
            DestroyWindow(hwnd);
            /* 2 popups en plus seront créés par le timer */
        }
        return 0;
    }
    case WM_CLOSE: return 0; /* Impossible de fermer */
    case WM_DESTROY: {
        for (int i = 0; i < numPopups; i++) {
            if (popupWnds[i] == hwnd) { popupWnds[i] = popupWnds[--numPopups]; break; }
        }
        return 0;
    }
    }
    return DefWindowProcW(hwnd, msg, wp, lp);
}

static void spawnPopup(void) {
    if (numPopups >= MAX_POPUPS) return;
    int pw = 300, ph = 110;
    int px = rand() % max(1, screenW - pw);
    int py = rand() % max(1, screenH - ph);
    const wchar_t *msg = MSGS[rand() % MSG_COUNT];

    HWND hw = CreateWindowExW(
        WS_EX_TOPMOST | WS_EX_TOOLWINDOW,
        L"RedSmilePopup", L"RedSmile.exe",
        WS_POPUP | WS_VISIBLE,
        px, py, pw, ph,
        NULL, NULL, GetModuleHandleW(NULL), NULL
    );
    if (!hw) return;
    SetWindowLongPtrW(hw, GWLP_USERDATA, (LONG_PTR)msg);
    popupWnds[numPopups++] = hw;
    InvalidateRect(hw, NULL, TRUE);
}

/* Fenetre principale */
static LRESULT CALLBACK WndProc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    switch (msg) {
    case WM_PAINT: {
        PAINTSTRUCT ps;
        HDC hdc = BeginPaint(hwnd, &ps);
        RECT rc; GetClientRect(hwnd, &rc);

        /* Fond noir */
        HBRUSH brBg = CreateSolidBrush(RGB(0, 0, 0));
        FillRect(hdc, &rc, brBg); DeleteObject(brBg);

        HDC mem = CreateCompatibleDC(hdc);
        SetStretchBltMode(hdc, HALFTONE);

        /* Clones */
        if (hSmileBmp) {
            SelectObject(mem, hSmileBmp);
            for (int i = 0; i < numClones; i++) {
                Clone *c = &clones[i];
                int sz = (int)c->size;
                int cx = (int)c->x - sz/2;
                int cy = (int)c->y - sz/2;
                /* AlphaBlend pour transparence */
                BLENDFUNCTION bf = {AC_SRC_OVER, 0, (BYTE)c->alpha, 0};
                HDC tmpDC = CreateCompatibleDC(hdc);
                HBITMAP tmpBmp = CreateCompatibleBitmap(hdc, sz, sz);
                SelectObject(tmpDC, tmpBmp);
                StretchBlt(tmpDC, 0, 0, sz, sz, mem, 0, 0, bmpW, bmpH, SRCCOPY);
                AlphaBlend(hdc, cx, cy, sz, sz, tmpDC, 0, 0, sz, sz, bf);
                DeleteObject(tmpBmp);
                DeleteDC(tmpDC);
            }
        }

        /* Smiley principal au centre */
        if (hSmileBmp) {
            int mainSz = (int)(min(rc.right, rc.bottom) * 0.35f * mainPulse);
            int mx = rc.right/2 - mainSz/2;
            int my = rc.bottom/2 - mainSz/2;
            SelectObject(mem, hSmileBmp);
            StretchBlt(hdc, mx, my, mainSz, mainSz, mem, 0, 0, bmpW, bmpH, SRCCOPY);
        }

        /* Titre */
        SetBkMode(hdc, TRANSPARENT);
        SetTextColor(hdc, RGB(229, 57, 53));
        HFONT fTitle = CreateFontW(56, 0, 0, 0, FW_BOLD, 0, 0, 0, DEFAULT_CHARSET, 0, 0, 0, 0, L"Segoe UI");
        SelectObject(hdc, fTitle);
        RECT tr = {0, 20, rc.right, 90};
        DrawTextW(hdc, L"RedSmile", -1, &tr, DT_CENTER | DT_SINGLELINE);
        DeleteObject(fTitle);

        DeleteDC(mem);
        EndPaint(hwnd, &ps);
        return 0;
    }
    case WM_TIMER:
        if (wp == TIMER_ANIM) {
            float dt = 0.03f;
            /* Pulse */
            mainPulse += pulseDir * dt * 0.5f;
            if (mainPulse > 1.15f) pulseDir = -1;
            if (mainPulse < 0.95f) pulseDir = 1;

            /* Move clones */
            for (int i = 0; i < numClones; i++) {
                Clone *c = &clones[i];
                c->x += c->vx * dt;
                c->y += c->vy * dt;
                c->rot += c->rotSpeed * dt;
                /* Bounce off edges */
                if (c->x < 0) { c->x = 0; c->vx = fabsf(c->vx); }
                if (c->x > screenW) { c->x = (float)screenW; c->vx = -fabsf(c->vx); }
                if (c->y < 0) { c->y = 0; c->vy = fabsf(c->vy); }
                if (c->y > screenH) { c->y = (float)screenH; c->vy = -fabsf(c->vy); }
                /* Random kicks */
                if (rand() % 60 == 0) {
                    float a = randf(0, 2 * PI);
                    c->vx += cosf(a) * 200;
                    c->vy += sinf(a) * 200;
                }
                /* Speed limit */
                float sp = sqrtf(c->vx*c->vx + c->vy*c->vy);
                if (sp > 500) { c->vx *= 500/sp; c->vy *= 500/sp; }
            }
            InvalidateRect(hwnd, NULL, FALSE);
        }
        else if (wp == TIMER_POPUP) {
            spawnPopup();
        }
        else if (wp == TIMER_BEEP) {
            if (beepOn) {
                Beep(beepFreq, 80);
                beepFreq += beepDir * 40;
                if (beepFreq > 900) beepDir = -1;
                if (beepFreq < 420) beepDir = 1;
            }
        }
        else if (wp == TIMER_TOPMOST) {
            /* Rester au-dessus de tout */
            SetWindowPos(hwnd, HWND_TOPMOST, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE);
            SetForegroundWindow(hwnd);
        }
        return 0;
    case WM_CLOSE:
    case WM_SYSCOMMAND:
        if (msg == WM_SYSCOMMAND && (wp & 0xFFF0) == SC_CLOSE) return 0;
        if (msg == WM_CLOSE) return 0;
        break;
    case WM_KEYDOWN:
        if (wp == VK_ESCAPE || (wp == VK_F4 && GetAsyncKeyState(VK_MENU))) {
            spawnPopup(); spawnPopup();
            return 0;
        }
        break;
    case WM_ERASEBKGND: return 1;
    }
    return DefWindowProcW(hwnd, msg, wp, lp);
}

int WINAPI WinMain(HINSTANCE hInst, HINSTANCE prev, LPSTR cmd, int show) {
    srand((unsigned)time(NULL));
    screenW = GetSystemMetrics(SM_CXSCREEN);
    screenH = GetSystemMetrics(SM_CYSCREEN);

    /* Charger le bitmap */
    hSmileBmp = LoadBitmapW(hInst, MAKEINTRESOURCEW(1));
    if (hSmileBmp) {
        BITMAP bm; GetObject(hSmileBmp, sizeof(bm), &bm);
        bmpW = bm.bmWidth; bmpH = bm.bmHeight;
    }

    /* Avertissement */
    int r = MessageBoxW(NULL,
        L"⚠️ RedSmile PC\n\n"
        L"Plusieurs popups vont apparaître, des images vont\n"
        L"se balader partout et un son fort va se déclencher.\n\n"
        L"Le fond d'écran va changer.\n\n"
        L"C'est juste pour le fun : rien n'est endommagé.\n"
        L"Pour arrêter : Ctrl+Alt+Suppr > Gestionnaire des tâches.\n\n"
        L"Lancer RedSmile ?",
        L"RedSmile", MB_OKCANCEL | MB_ICONWARNING);
    if (r != IDOK) return 0;

    /* Changer le fond d'ecran */
    setRedWallpaper();

    /* Classes */
    WNDCLASSEXW wc = {sizeof(wc)};
    wc.lpfnWndProc = WndProc;
    wc.hInstance = hInst;
    wc.lpszClassName = L"RedSmileMain";
    wc.hCursor = LoadCursorW(NULL, IDC_ARROW);
    RegisterClassExW(&wc);

    WNDCLASSEXW pc = {sizeof(pc)};
    pc.lpfnWndProc = PopupProc;
    pc.hInstance = hInst;
    pc.lpszClassName = L"RedSmilePopup";
    pc.hCursor = LoadCursorW(NULL, IDC_ARROW);
    RegisterClassExW(&pc);

    /* Fenetre plein ecran toujours au-dessus */
    mainWnd = CreateWindowExW(
        WS_EX_TOPMOST | WS_EX_TOOLWINDOW,
        L"RedSmileMain", L"RedSmile",
        WS_POPUP | WS_VISIBLE,
        0, 0, screenW, screenH,
        NULL, NULL, hInst, NULL
    );

    initClones();

    SetTimer(mainWnd, TIMER_ANIM, 30, NULL);     /* ~33fps */
    SetTimer(mainWnd, TIMER_POPUP, 800, NULL);    /* popup toutes les 800ms */
    SetTimer(mainWnd, TIMER_BEEP, 300, NULL);     /* sirene */
    SetTimer(mainWnd, TIMER_TOPMOST, 500, NULL);  /* rester devant */

    MSG m;
    while (GetMessageW(&m, NULL, 0, 0)) {
        TranslateMessage(&m);
        DispatchMessageW(&m);
    }
    return 0;
}
