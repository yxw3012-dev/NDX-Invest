// Android back uses the frozen UI's existing controls; it never calls a business bridge.
(() => {
  if(document.querySelector("#fundNameEditor.is-open")) { window.V2FundNames.close(); return true; }
  if(document.querySelector('#wallpaperPage.is-open')) { window.V2Wallpaper.close(); return true; }
  if (document.querySelector('#buyRecurringSheet.is-open, #buyDateSheet.is-open, #buyFundPicker.is-mounted, #buyPage.is-open, #settingsPage.is-open, #frameRecords.is-open')) {
    document.dispatchEvent(new KeyboardEvent('keydown', {key: 'Escape', bubbles: true}));
    return true;
  }
  const frame = document.querySelector('#framePortfolio.is-active');
  const doc = frame?.contentDocument;
  if (!frame) return false;
  if (!doc) return true;
  if (doc.querySelector('#holdingSettings.is-open')) { doc.getElementById('hsBack').click(); return true; }
  const mode = frame.contentWindow.V2Portfolio?.getMode();
  if (!mode || mode === 'transitioning' || doc.querySelector('.level3-transition, .sort-mode')) {
    if (doc.querySelector('.sort-mode')) doc.getElementById('doneButton')?.click();
    return true;
  }
  if (mode === 'level3') { doc.getElementById('level3Back').click(); return true; }
  if (mode === 'level2') {
    doc.getElementById('sheetHandleZone').dispatchEvent(new frame.contentWindow.KeyboardEvent('keydown', {key: 'Enter', bubbles: true}));
    return true;
  }
  return false;
})()
