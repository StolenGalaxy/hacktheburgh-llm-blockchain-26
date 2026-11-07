// Show/hide password. Usage: <button type="button" data-toggle-password="inputId">Show</button>
document.querySelectorAll("[data-toggle-password]").forEach((button) => {
  const input = document.getElementById(button.dataset.togglePassword);
  button.addEventListener("click", () => {
    const hidden = input.type === "password";
    input.type = hidden ? "text" : "password";
    button.textContent = hidden ? "Hide" : "Show";
  });
});
