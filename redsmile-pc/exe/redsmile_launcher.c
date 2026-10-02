/*
 * RedSmile PC Launcher - extrait le HTML embarqué et l'ouvre en plein écran.
 * Le HTML contient toute l'animation : clones qui rebondissent, popups, sirène, etc.
 */
#include <windows.h>
#include <stdio.h>

/* Le HTML est embarqué comme ressource (ID 101, type HTMLFILE) */
int WINAPI WinMain(HINSTANCE hInst, HINSTANCE prev, LPSTR cmd, int show) {
    /* Avertissement */
    int r = MessageBoxW(NULL,
        L"⚠️ RedSmile PC\n\n"
        L"Plusieurs popups vont apparaître, des images vont se balader\n"
        L"partout sur l'écran et un son fort va se déclencher.\n\n"
        L"C'est juste pour le fun : rien n'est installé,\n"
        L"rien n'est modifié sur ton PC.\n\n"
        L"Lancer RedSmile ?",
        L"RedSmile", MB_OKCANCEL | MB_ICONWARNING);
    if (r != IDOK) return 0;

    /* Extraire le HTML dans %TEMP% */
    HRSRC res = FindResourceW(hInst, MAKEINTRESOURCEW(101), L"HTMLFILE");
    if (!res) { MessageBoxW(NULL, L"Ressource introuvable", L"Erreur", MB_OK); return 1; }
    HGLOBAL hg = LoadResource(hInst, res);
    DWORD sz = SizeofResource(hInst, res);
    void *data = LockResource(hg);
    if (!data) return 1;

    wchar_t tmp[MAX_PATH], path[MAX_PATH];
    GetTempPathW(MAX_PATH, tmp);
    wsprintfW(path, L"%sRedSmile.html", tmp);

    HANDLE f = CreateFileW(path, GENERIC_WRITE, 0, NULL, CREATE_ALWAYS, 0, NULL);
    if (f == INVALID_HANDLE_VALUE) return 1;
    DWORD written;
    WriteFile(f, data, sz, &written, NULL);
    CloseHandle(f);

    /* Ouvrir en mode kiosk : essayer Edge puis Chrome puis navigateur par défaut */
    wchar_t cmdLine[2048];

    /* Edge */
    wsprintfW(cmdLine, L"cmd /c start msedge --kiosk \"%s\" --edge-kiosk-type=fullscreen --no-first-run", path);
    STARTUPINFOW si = { sizeof(si) };
    si.dwFlags = STARTF_USESHOWWINDOW;
    si.wShowWindow = SW_HIDE;
    PROCESS_INFORMATION pi;
    if (CreateProcessW(NULL, cmdLine, NULL, NULL, FALSE, CREATE_NO_WINDOW, NULL, NULL, &si, &pi)) {
        CloseHandle(pi.hProcess);
        CloseHandle(pi.hThread);
        return 0;
    }

    /* Chrome */
    wsprintfW(cmdLine, L"cmd /c start chrome --kiosk \"%s\" --no-first-run", path);
    if (CreateProcessW(NULL, cmdLine, NULL, NULL, FALSE, CREATE_NO_WINDOW, NULL, NULL, &si, &pi)) {
        CloseHandle(pi.hProcess);
        CloseHandle(pi.hThread);
        return 0;
    }

    /* Navigateur par défaut (pas kiosk mais ça marche) */
    ShellExecuteW(NULL, L"open", path, NULL, NULL, SW_SHOWMAXIMIZED);
    return 0;
}
