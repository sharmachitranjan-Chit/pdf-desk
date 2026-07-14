# PDF Desk

A native Android PDF reader & editor. *A tool made by Chitranjan Sharma.*

## Phase 1 features
- **Reader** — open any PDF, scroll, pinch-zoom, night mode, page indicator.
- **Manage pages** — reorder (drag), rotate, delete, extract, split, merge another PDF.
- **Annotate** — highlight a region, freehand draw, and add sticky notes; saved into the PDF.
- Opens PDFs from other apps ("Open with → PDF Desk"), and can Save-a-copy / Share.

Phase 2 (planned): edit existing text in place (detect → cover → redraw).

## Build
No local Android SDK needed — the APK is built by GitHub Actions.

1. Create a new GitHub repo (e.g. `pdf-desk`).
2. Push this folder to it (see commands below).
3. GitHub → **Actions** tab → the *Build APK* workflow runs automatically.
4. When it finishes, open the run → **Artifacts** → download **PDFDesk-debug-apk**.
5. Unzip, transfer the `.apk` to your phone, and install (allow "install from unknown sources").

```bash
git init
git add .
git commit -m "PDF Desk — phase 1"
git branch -M main
git remote add origin https://github.com/<your-username>/pdf-desk.git
git push -u origin main
```

## Notes
- Debug APK is unsigned for Play but installs fine directly.
- Annotations on rotated pages are the least-tested path; verify before relying on them.
