import "./style.css";
const result = document.querySelector("#result");
let nonce; let nextId = 0; const pending = new Map();
document.addEventListener("jdesk-message", ({detail}) => {
  const message = JSON.parse(detail);
  if (message.kind === "nonce") { nonce = message.nonce; window.__jdesk.post(JSON.stringify({v:1,kind:"hello",client:"@PROJECT_NAME@",clientVersion:"0.1.0",nonce})); }
  if (message.kind === "helloAck") result.textContent = message.ok ? "Connected" : message.error.message;
  if (message.kind === "result") { pending.get(message.id)?.(message); pending.delete(message.id); }
});
document.querySelector("#greet").addEventListener("submit", event => { event.preventDefault(); const id=`request-${++nextId}`; pending.set(id, message => result.textContent=message.ok?message.value.message:message.error.message); window.__jdesk.post(JSON.stringify({v:1,kind:"invoke",id,command:"greeting.greet",nonce,payload:{name:document.querySelector("#name").value}})); });
