const api = "/investor/api";
const $ = id => document.getElementById(id);
const notice = message => {
  const target = $(document.body.classList.contains("authenticated") ? "notice" : "login-notice");
  target.textContent = message;
  target.classList.toggle("hidden", !message);
};
let state = null;
let checkedName = "";
let checkSequence = 0;
let resetting = false;
const pendingOperations = new Map();
const key = () => crypto.randomUUID();

async function call(path, body) {
  const res = await fetch(api + path, {
    credentials: "same-origin", cache: "no-store",
    ...(body === undefined ? {} : {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Investor-Request": "1" },
      body: JSON.stringify(body)
    })
  });
  const data = await res.json();
  if (!res.ok) {
    const error = new Error(data.error || "Не удалось выполнить запрос");
    error.status = res.status;
    throw error;
  }
  return data;
}

function signedOut() {
  state = null;
  checkedName = "";
  checkSequence++;
  document.body.classList.remove("authenticated");
  $("account").classList.remove("hidden");
  $("account-view").classList.add("hidden");
  $("logout").classList.add("hidden");
  $("notice").textContent = "";
  $("notice").classList.add("hidden");
}

function countdown() {
  if (!state) return;
  const remaining = Math.max(0, Math.ceil((Date.parse(state.next_reset) - Date.now()) / 1000));
  $("stars-countdown").textContent = `${String(Math.floor(remaining / 3600)).padStart(2, "0")}:${String(Math.floor(remaining % 3600 / 60)).padStart(2, "0")}:${String(remaining % 60).padStart(2, "0")}`;
  if (remaining === 0 && !resetting) {
    resetting = true;
    refresh().catch(error => {
      if (error.status === 401) signedOut();
      else notice(error.message);
    }).finally(() => { resetting = false; });
  }
}

async function refresh() {
  state = await call("/state");
  $("stars-remaining").textContent = Number(state.remaining_today).toLocaleString("ru-RU");
  $("stars-amount").max = state.remaining_today;
  $("stars-form").querySelector("button").disabled = state.remaining_today < 1;
  $("names-remaining").textContent = state.username_remaining;
  $("verified-status").textContent = state.verified ? "Верификация вашего аккаунта включена." : "Верификация вашего аккаунта отключена.";
  $("verified-toggle").textContent = state.verified ? "Снять верификацию ↗" : "Выдать верификацию ↗";
  $("verified-toggle").disabled = false;
  $("mint-submit").disabled = state.username_remaining < 1 || !checkedName;
  const list = $("owned-names");
  list.replaceChildren();
  if (!state.owned_names.length) {
    const item = document.createElement("li");
    item.textContent = "У вас пока нет NFT-имён.";
    list.append(item);
  }
  for (const name of state.owned_names) {
    const item = document.createElement("li");
    item.textContent = `@${name}`;
    list.append(item);
  }
  countdown();
}

async function render(profile) {
  $("account").classList.add("hidden");
  $("account-view").classList.remove("hidden");
  $("logout").classList.remove("hidden");
  document.body.classList.add("authenticated");
  $("login-notice").classList.add("hidden");
  $("account-name").textContent = profile.name || "Инвестор";
  $("account-username").textContent = `${profile.login} · ${profile.username ? "@" + profile.username + " · " : ""}ID ${profile.id}`;
  window.scrollTo(0, 0);
  try { await refresh(); } catch (error) {
    if (error.status === 401) signedOut();
    else notice(error.message);
  }
}

call("/me").then(render).catch(error => {
  if (error.status !== 401) notice(error.message);
});
$("login-form").addEventListener("submit", async event => {
  event.preventDefault();
  const button = event.currentTarget.querySelector("button");
  button.disabled = true;
  $("login-notice").classList.add("hidden");
  try {
    await call("/login", { login: $("login").value.trim(), password: $("password").value });
    $("password").value = "";
    await render(await call("/me"));
  } catch (error) {
    signedOut();
    notice(error.message);
  }
  finally { button.disabled = false; }
});
$("logout").addEventListener("click", async () => {
  try { await call("/logout", {}); } catch { /* Session may have expired. */ }
  pendingOperations.clear();
  signedOut();
  $("login-notice").classList.add("hidden");
  window.scrollTo(0, 0);
});

document.querySelectorAll("[data-tab]").forEach(button => {
  button.addEventListener("click", () => {
    document.querySelectorAll("[data-tab]").forEach(tab => {
      const active = tab === button;
      tab.setAttribute("aria-selected", String(active));
      $(tab.getAttribute("aria-controls")).classList.toggle("hidden", !active);
    });
    notice("");
    button.focus();
  });
});

async function submit(action, payload, button) {
  button.disabled = true; notice("");
  const fingerprint = JSON.stringify([action, payload]);
  if (!pendingOperations.has(fingerprint)) pendingOperations.set(fingerprint, key());
  const operationKey = pendingOperations.get(fingerprint);
  try {
    await call(action, { ...payload, key: operationKey });
  } catch (error) {
    if (error.status === 401) {
      pendingOperations.clear();
      signedOut();
      notice("Сессия завершилась. Войдите снова.");
      return false;
    }
    notice(`${error.message} Если результат неизвестен, повторите действие с теми же данными.`);
    return false;
  } finally {
    if (action === "/mint") button.disabled = !checkedName || !state || state.username_remaining < 1;
    else button.disabled = false;
  }
  pendingOperations.delete(fingerprint);
  try {
    await refresh();
    notice("Готово. Изменения сохранены для вашего аккаунта.");
  } catch {
    notice("Операция выполнена, но обновить данные не удалось. Перезагрузите страницу для проверки.");
  }
  return true;
}

$("stars-form").addEventListener("submit", async event => {
  event.preventDefault();
  const amount = Number($("stars-amount").value);
  if (!Number.isSafeInteger(amount) || amount < 1 || !state || amount > state.remaining_today) {
    notice("Введите количество в пределах доступного остатка."); return;
  }
  await submit("/stars", { amount }, event.currentTarget.querySelector("button"));
});

$("mint-name").addEventListener("input", async event => {
  checkedName = "";
  $("mint-submit").disabled = true;
  const name = event.target.value.trim().toLowerCase();
  const sequence = ++checkSequence;
  if (!/^[a-z][a-z0-9_]{3,31}$/.test(name)) {
    $("name-availability").textContent = "От 4 до 32 символов: латинские буквы, цифры и _, первая — буква.";
    return;
  }
  $("name-availability").textContent = "Проверяем имя…";
  try {
    const result = await call("/username-availability?name=" + encodeURIComponent(name));
    if (sequence !== checkSequence) return;
    checkedName = result.available ? name : "";
    $("name-availability").textContent = result.available ? "Имя свободно. Перед выдачей проверим ещё раз." : "Это имя уже занято.";
    $("mint-submit").disabled = !checkedName || !state || state.username_remaining < 1;
  } catch (error) {
    if (sequence === checkSequence) $("name-availability").textContent = error.message;
  }
});

$("names-form").addEventListener("submit", async event => {
  event.preventDefault();
  const name = $("mint-name").value.trim().toLowerCase();
  if (!checkedName || checkedName !== name) { notice("Сначала проверьте доступность имени."); return; }
  if (await submit("/mint", { username: name }, $("mint-submit"))) {
    $("mint-name").value = "";
    checkedName = "";
    $("mint-submit").disabled = true;
    $("name-availability").textContent = "Введите имя для проверки доступности.";
  }
});

$("verified-toggle").addEventListener("click", async event => {
  if (state) await submit("/verification", { verified: !state.verified }, event.currentTarget);
});

setInterval(countdown, 1000);
if (!matchMedia("(prefers-reduced-motion: reduce)").matches && matchMedia("(pointer: fine)").matches) {
  document.querySelectorAll("[data-glass]").forEach(element => {
    let scheduled = false, x = 0, y = 0;
    element.addEventListener("pointermove", event => {
      const box = element.getBoundingClientRect();
      x = ((event.clientX - box.left) / box.width * 100).toFixed(1) + "%";
      y = ((event.clientY - box.top) / box.height * 100).toFixed(1) + "%";
      if (!scheduled) {
        scheduled = true;
        requestAnimationFrame(() => {
          element.style.setProperty("--light-x", x);
          element.style.setProperty("--light-y", y);
          scheduled = false;
        });
      }
    }, { passive: true });
  });
}
