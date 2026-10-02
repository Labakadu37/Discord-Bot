/*
 * RedSmile PC - v2 (pour le fun, usage perso)
 * Plein ecran, au-dessus de tout, smiley rouge qui pulse.
 * La croix / Alt+F4 affichent un faux "Impossible de fermer" et la fenetre revient.
 * SEULE vraie sortie : le bouton "APPUIE POUR ARRETER TOUT".
 * Le gestionnaire des taches reste fonctionnel (securite) : rien n'est bloque, rien installe.
 */
#include <windows.h>
#include <math.h>

static RECT g_btn;          /* zone cliquable du bouton */
static int  g_phase = 0;    /* animation (pulsation) */
static int  g_quitting = 0; /* vrai = sortie demandee par le bouton */

static void draw(HDC hdc, int w, int h)
{
    /* Fond noir */
    RECT full = {0, 0, w, h};
    FillRect(hdc, &full, (HBRUSH)GetStockObject(BLACK_BRUSH));
    SetBkMode(hdc, TRANSPARENT);

    /* Smiley rouge qui pulse */
    int cx = w / 2, cy = h / 2 - h / 12;
    double pulse = 1.0 + 0.06 * sin(g_phase * 0.15);
    int r = (int)(((w < h ? w : h) / 5) * pulse);

    HPEN pen = CreatePen(PS_SOLID, r / 12 + 3, RGB(210, 20, 20));
    HPEN oldp = SelectObject(hdc, pen);
    SelectObject(hdc, GetStockObject(NULL_BRUSH));
    Arc(hdc, cx - r, cy - r, cx + r, cy + r, 0, 0, 0, 0);

    HBRUSH red = CreateSolidBrush(RGB(210, 20, 20));
    SelectObject(hdc, red);
    int ex = r / 3, ey = r / 3, es = r / 7;
    Ellipse(hdc, cx - ex - es, cy - ey - es, cx - ex + es, cy - ey + es);
    Ellipse(hdc, cx + ex - es, cy - ey - es, cx + ex + es, cy - ey + es);
    SelectObject(hdc, GetStockObject(NULL_BRUSH));
    Arc(hdc, cx - r / 2, cy - r / 4, cx + r / 2, cy + r / 2,
        cx + r / 2, cy + r / 4, cx - r / 2, cy + r / 4);
    SelectObject(hdc, oldp);
    DeleteObject(pen);
    DeleteObject(red);

    /* Titre */
    HFONT ft = CreateFont(h / 14, 0, 0, 0, FW_BOLD, 0, 0, 0, 0, 0, 0, 0, 0, "Impact");
    HFONT oldf = SelectObject(hdc, ft);
    SetTextColor(hdc, RGB(229, 57, 53));
    RECT tr = {0, (LONG)(h * 0.06), w, (LONG)(h * 0.06) + h / 12};
    DrawText(hdc, "RedSmile", -1, &tr, DT_CENTER | DT_SINGLELINE);
    SelectObject(hdc, oldf);
    DeleteObject(ft);

    /* Bouton rouge : seule vraie sortie */
    int bw = w / 3, bh = h / 10;
    if (bw < 420) bw = 420;
    if (bh < 80)  bh = 80;
    g_btn.left = cx - bw / 2; g_btn.right = cx + bw / 2;
    g_btn.top = (LONG)(h * 0.78); g_btn.bottom = g_btn.top + bh;
    HBRUSH btn = CreateSolidBrush(RGB(198, 40, 40));
    HPEN bpen = CreatePen(PS_SOLID, 3, RGB(255, 23, 68));
    SelectObject(hdc, btn); SelectObject(hdc, bpen);
    RoundRect(hdc, g_btn.left, g_btn.top, g_btn.right, g_btn.bottom, 24, 24);
    HFONT bf = CreateFont(bh / 3, 0, 0, 0, FW_BOLD, 0, 0, 0, 0, 0, 0, 0, 0, "Arial");
    SelectObject(hdc, bf);
    SetTextColor(hdc, RGB(255, 255, 255));
    DrawText(hdc, "APPUIE POUR ARRETER TOUT", -1, &g_btn, DT_CENTER | DT_VCENTER | DT_SINGLELINE);
    SelectObject(hdc, bf); DeleteObject(bf);
    DeleteObject(btn); DeleteObject(bpen);
}

LRESULT CALLBACK WndProc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp)
{
    switch (msg) {
        case WM_TIMER:
            g_phase++;
            InvalidateRect(hwnd, NULL, FALSE);
            /* Reste au premier plan */
            SetWindowPos(hwnd, HWND_TOPMOST, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE);
            return 0;
        case WM_PAINT: {
            PAINTSTRUCT ps;
            HDC hdc = BeginPaint(hwnd, &ps);
            RECT rc; GetClientRect(hwnd, &rc);
            HDC mem = CreateCompatibleDC(hdc);
            HBITMAP bmp = CreateCompatibleBitmap(hdc, rc.right, rc.bottom);
            HBITMAP oldbmp = SelectObject(mem, bmp);
            draw(mem, rc.right, rc.bottom);
            BitBlt(hdc, 0, 0, rc.right, rc.bottom, mem, 0, 0, SRCCOPY);
            SelectObject(mem, oldbmp);
            DeleteObject(bmp); DeleteDC(mem);
            EndPaint(hwnd, &ps);
            return 0;
        }
        case WM_LBUTTONDOWN: {
            int x = LOWORD(lp), y = HIWORD(lp);
            if (x >= g_btn.left && x <= g_btn.right && y >= g_btn.top && y <= g_btn.bottom) {
                g_quitting = 1;              /* le bouton = vraie sortie */
                DestroyWindow(hwnd);
            }
            return 0;
        }
        case WM_CLOSE:
            /* Croix / Alt+F4 : faux message, on ne ferme pas (seul le bouton ferme) */
            if (!g_quitting) {
                Beep(300, 120);
                MessageBox(hwnd, "Impossible de fermer :)\nUtilise le bouton rouge.",
                           "RedSmile", MB_OK | MB_ICONEXCLAMATION | MB_TOPMOST);
                SetForegroundWindow(hwnd);
                return 0;
            }
            DestroyWindow(hwnd);
            return 0;
        case WM_DESTROY:
            PostQuitMessage(0);
            return 0;
    }
    return DefWindowProc(hwnd, msg, wp, lp);
}

int WINAPI WinMain(HINSTANCE hInst, HINSTANCE prev, LPSTR cmd, int show)
{
    int ok = MessageBox(NULL,
        "RedSmile va s'ouvrir en plein ecran.\n"
        "Pour sortir : le bouton rouge \"APPUIE POUR ARRETER TOUT\".\n"
        "La croix affichera un faux message, c'est pour le fun.\n"
        "Rien n'est installe, rien n'est modifie sur le PC.\n"
        "(Secours : le gestionnaire des taches ferme toujours le programme.)\n\n"
        "Lancer RedSmile ?",
        "RedSmile - Avertissement", MB_OKCANCEL | MB_ICONWARNING);
    if (ok != IDOK) return 0;

    Beep(600, 150); Beep(400, 200);

    const char cls[] = "RedSmileWindow";
    WNDCLASS wc = {0};
    wc.lpfnWndProc = WndProc;
    wc.hInstance = hInst;
    wc.lpszClassName = cls;
    wc.hCursor = LoadCursor(NULL, IDC_ARROW);
    wc.hbrBackground = (HBRUSH)GetStockObject(BLACK_BRUSH);
    RegisterClass(&wc);

    int sw = GetSystemMetrics(SM_CXSCREEN);
    int sh = GetSystemMetrics(SM_CYSCREEN);
    HWND hwnd = CreateWindowEx(WS_EX_TOPMOST, cls, "RedSmile", WS_POPUP,
        0, 0, sw, sh, NULL, NULL, hInst, NULL);
    ShowWindow(hwnd, SW_SHOW);
    SetForegroundWindow(hwnd);
    SetTimer(hwnd, 1, 60, NULL);

    MSG m;
    while (GetMessage(&m, NULL, 0, 0) > 0) {
        TranslateMessage(&m);
        DispatchMessage(&m);
    }
    return 0;
}
