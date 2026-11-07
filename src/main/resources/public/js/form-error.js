// Shows ?error=... (set by the server when a form is rejected) in the page's .error element.
const formError = new URLSearchParams(location.search).get("error");
if (formError) {
  document.querySelector(".error").textContent = formError;
}
