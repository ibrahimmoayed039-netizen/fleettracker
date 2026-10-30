const { app, BrowserWindow, ipcMain, dialog, shell } = require('electron');
const path = require('path');
const fs = require('fs');

app.setName('Agrimatco - Fleet');

app.whenReady().then(() => {
  const w = new BrowserWindow({
    width: 1280, height: 800, minWidth: 980, minHeight: 600,
    autoHideMenuBar: true,
    title: 'Agrimatco - Fleet',
    icon: path.join(__dirname, 'assets', 'icon.png'),
    backgroundColor: '#eef3f0',
    webPreferences: { preload: path.join(__dirname, 'preload.js') }
  });
  w.on('page-title-updated', e => e.preventDefault()); // يبقى اسم النافذة ثابتاً
  w.loadFile('index.html');
});

// ترويسة/تذييل ملف PDF: الشعار + Agrimatco - Fleet + رقم الصفحة، تتكرر في كل الصفحات
function pdfTemplates() {
  let logo = '';
  try { logo = fs.readFileSync(path.join(__dirname, 'assets', 'logo-small.png')).toString('base64'); } catch (e) {}
  const header = `<div style="width:100%;box-sizing:border-box;padding:0 12mm;font-family:Segoe UI,Tahoma,sans-serif;font-size:10px;direction:rtl;-webkit-print-color-adjust:exact">
    <div style="display:flex;align-items:center;justify-content:space-between;border-bottom:2px solid #0a8f4b;padding-bottom:4px">
      <div style="display:flex;align-items:center;gap:8px">
        <img src="data:image/png;base64,${logo}" style="width:34px;height:34px">
        <b style="font-size:17px;color:#05643a;direction:ltr;letter-spacing:.4px">Agrimatco - Fleet</b>
      </div>
      <span style="color:#555">نظام متابعة المركبات — <span class="date"></span></span>
    </div></div>`;
  const footer = `<div style="width:100%;box-sizing:border-box;padding:0 12mm;font-family:Segoe UI,Tahoma,sans-serif;font-size:9px;color:#666;text-align:center;direction:rtl">
    Agrimatco - Fleet &nbsp;|&nbsp; صفحة <span class="pageNumber"></span> من <span class="totalPages"></span></div>`;
  return { header, footer };
}

// حفظ التقرير كملف PDF
ipcMain.handle('save-pdf', async (e, name) => {
  const w = BrowserWindow.fromWebContents(e.sender);
  const safe = String(name || 'Agrimatco - Fleet').replace(/[\\/:*?"<>|]/g, '-');
  const r = await dialog.showSaveDialog(w, {
    defaultPath: safe + '.pdf',
    filters: [{ name: 'PDF', extensions: ['pdf'] }]
  });
  if (r.canceled || !r.filePath) { try { w.focus(); w.webContents.focus(); } catch (e) {} return { ok: false }; }
  const t = pdfTemplates();
  const data = await w.webContents.printToPDF({
    printBackground: true, pageSize: 'A4',
    displayHeaderFooter: true, headerTemplate: t.header, footerTemplate: t.footer,
    margins: { top: 1.15, bottom: 0.65, left: 0.4, right: 0.4 }
  });
  fs.writeFileSync(r.filePath, data);
  shell.showItemInFolder(r.filePath);
  setTimeout(() => { try { w.focus(); w.webContents.focus(); } catch (e) {} }, 400); // يمنع تجمّد حقول الإدخال بعد نوافذ النظام
  return { ok: true };
});

app.on('window-all-closed', () => app.quit());
