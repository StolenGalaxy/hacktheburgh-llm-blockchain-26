// Open a <dialog>: <button type="button" data-open-dialog="dialogId">...</button>
// Close one: put a <form method="dialog"><button>Cancel</button></form> inside it (no JS needed),
// or click the backdrop.
document.querySelectorAll("[data-open-dialog]").forEach((button) => {
  const dialog = document.getElementById(button.dataset.openDialog);
  button.addEventListener("click", () => dialog.showModal());
});

document.querySelectorAll("dialog").forEach((dialog) => {
  dialog.addEventListener("click", (event) => {
    if (event.target === dialog) dialog.close();
  });
});
