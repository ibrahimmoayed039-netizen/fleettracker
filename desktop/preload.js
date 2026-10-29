const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('fleetAPI', {
  savePdf: name => ipcRenderer.invoke('save-pdf', name)
});
