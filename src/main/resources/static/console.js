const $ = (id) => document.getElementById(id);

const OS_USER = {
  auto: "",
  ubuntu: "ubuntu",
  debian: "debian",
  rhel: "root",
  rocky: "rocky",
  alma: "ec2-user",
  fedora: "fedora",
  alpine: "root",
  amazon: "ec2-user",
};

function humanBytes(n) {
  if (!n || n <= 0) return "—";
  const gb = n / (1024 ** 3);
  if (gb >= 1) return gb.toFixed(1) + " Go";
  return Math.round(n / (1024 ** 2)) + " Mo";
}

function pct(used, total) {
  if (!total) return 0;
  return Math.max(4, Math.min(100, Math.round((used / total) * 100)));
}

function usageTone(used, total) {
  if (!total) return "unknown";
  const p = used / total;
  if (p >= 0.9) return "alert";
  if (p >= 0.7) return "warn";
  return "ok";
}

function clearTone(el) {
  if (!el) return;
  el.classList.remove("on", "off", "unknown", "alert", "tone-ok", "tone-warn", "tone-alert");
}

function setCardTone(id, tone) {
  const el = $(id);
  if (!el) return;
  clearTone(el);
  if (tone === "ok") el.classList.add("tone-ok");
  if (tone === "warn") el.classList.add("tone-warn");
  if (tone === "alert") el.classList.add("tone-alert");
}

function setSoft(id, present) {
  const el = $(id);
  if (!el) return;
  const wrap = el.closest(".sw");
  if (!wrap) return;
  clearTone(wrap);
  if (typeof present === "boolean") {
    el.textContent = present ? "présent" : "absent";
    wrap.classList.add(present ? "on" : "off");
  } else {
    el.textContent = "inconnu";
    wrap.classList.add("unknown");
  }
}

function setFirewall(ufwStatus, fail2banStatus) {
  const el = $("swUfw");
  const wrap = el.closest(".sw");
  clearTone(wrap);
  const ufw = (ufwStatus || "inconnu").toLowerCase();
  const f2b = (fail2banStatus || "inconnu").toLowerCase();
  el.textContent = "ufw " + (ufwStatus || "inconnu") + " · f2b " + (fail2banStatus || "inconnu");
  if (ufw === "active" || ufw === "enabled" || f2b === "active" || f2b === "installed") {
    wrap.classList.add("on");
    return;
  }
  if (ufw === "inactive" || ufw === "disabled") {
    wrap.classList.add("alert");
    return;
  }
  wrap.classList.add("unknown");
}

function target() {
  return {
    name: $("vpsName").value.trim(),
    host: $("sshHost").value.trim(),
    user: $("sshUser").value.trim(),
    port: Number($("sshPort").value) || 22,
    os: $("vpsOs").value || "auto",
  };
}

function isMockSnapshot(snapshot, mode) {
  if (!snapshot) return true;
  if (mode === "fixture" || mode === "lab-fixture") return true;
  const src = String(snapshot.source || "");
  const host = String(snapshot.host || "");
  return src.startsWith("fixture") || host === "fixture" || host.startsWith("203.0.113.");
}

function paint(snapshot, report, mode) {
  if (isMockSnapshot(snapshot, mode)) {
    $("board").classList.add("hidden");
    $("installCard").classList.add("hidden");
    $("appsCard").classList.add("hidden");
    $("stackCard").classList.add("hidden");
    $("empty").classList.remove("hidden");
    return false;
  }
  $("empty").classList.add("hidden");
  $("board").classList.remove("hidden");
  $("stackCard").classList.remove("hidden");
  $("installCard").classList.remove("hidden");
  if ($("appsCard")) $("appsCard").classList.remove("hidden");
  $("osPretty").textContent = snapshot.osPretty || snapshot.osId || "—";
  $("hostLine").textContent = [
    snapshot.profileName,
    snapshot.host,
    snapshot.hostname,
    snapshot.osFamily,
    snapshot.arch,
  ].filter(Boolean).join(" · ");
  $("cpu").textContent = snapshot.cpu?.vcpus ? snapshot.cpu.vcpus + " vCPU" : "—";
  $("cpuModel").textContent = snapshot.cpu?.model || "—";
  setCardTone("cardCpu", snapshot.cpu?.vcpus ? "ok" : "unknown");

  const mem = snapshot.memory || {};
  const usedRam = (mem.totalBytes || 0) - (mem.availableBytes || 0);
  $("ram").textContent = humanBytes(mem.totalBytes);
  const ramTone = usageTone(usedRam, mem.totalBytes);
  $("ramBar").style.width = pct(usedRam, mem.totalBytes) + "%";
  $("ramBar").parentElement.classList.remove("tone-warn", "tone-alert");
  if (ramTone !== "ok") $("ramBar").parentElement.classList.add("tone-" + ramTone);
  setCardTone("cardRam", mem.totalBytes ? ramTone : "unknown");

  const disk = snapshot.disk || {};
  $("disk").textContent = humanBytes(disk.totalBytes);
  const diskTone = usageTone(disk.usedBytes, disk.totalBytes);
  $("diskBar").style.width = pct(disk.usedBytes, disk.totalBytes) + "%";
  $("diskBar").parentElement.classList.remove("tone-warn", "tone-alert");
  if (diskTone !== "ok") $("diskBar").parentElement.classList.add("tone-" + diskTone);
  setCardTone("cardDisk", disk.totalBytes ? diskTone : "unknown");

  const listening = !!snapshot.ssh?.listening;
  $("sshListen").textContent = listening ? "écoute" : "fermé";
  $("sshDetail").textContent =
    "root=" + (snapshot.ssh?.permitRootLogin || "?") +
    " · mdp=" + (snapshot.ssh?.passwordAuthentication || "?");
  setCardTone("cardSsh", snapshot.ssh ? (listening ? "ok" : "alert") : "unknown");

  setSoft("swDocker", snapshot.docker?.present);
  setSoft("swNginx", snapshot.nginx?.present);
  setSoft("swMariadb", snapshot.mariadb?.present);
  setSoft("swRedis", snapshot.redis?.present);
  setSoft("swTraefik", snapshot.traefik?.present);
  setSoft("swDeploy", snapshot.deploy?.present);
  setFirewall(snapshot.firewall?.ufwStatus, snapshot.firewall?.fail2banStatus);
  renderAppsGate(snapshot);
  renderLiveWarnings(snapshot.warnings);
  $("report").textContent = report || "—";
  lastAppsRunning = Array.isArray(snapshot.apps) ? snapshot.apps : [];
  if (mode) $("modePill").textContent = mode === "ssh" ? "SSH réel" : "Prêt pour le VPS";
  loadStack().then(loadPlan);
  loadApps();
  return true;
}

function currentView() {
  const hash = (location.hash || "#home").replace("#", "");
  if (hash === "guide") return "help";
  return ["home", "help", "cockpit"].includes(hash) ? hash : "home";
}

function showView(name) {
  const view = name === "guide" ? "help" : (["home", "help", "cockpit"].includes(name) ? name : "home");
  $("view-home").classList.toggle("hidden", view !== "home");
  $("view-help").classList.toggle("hidden", view !== "help");
  $("view-cockpit").classList.toggle("hidden", view !== "cockpit");
  document.querySelectorAll(".nav a[data-view]").forEach((a) => {
    a.classList.toggle("active", a.dataset.view === view);
  });
  if (location.hash.replace("#", "") !== view) {
    history.replaceState(null, "", "#" + view);
  }
}

function fillKey(pub) {
  const box = $("guidePubKey");
  const hint = $("pubKeyHint");
  if (pub) {
    box.textContent = pub;
    hint.textContent = "Clé de cet agent — à mettre sur le VPS, pas dans le catalogue OVH.";
    $("btnCopyRail").classList.remove("hidden");
    $("genStatus").classList.remove("hidden");
  } else {
    box.textContent = "Aucune clé trouvée. Lance la commande de l’étape 1, puis recharge.";
    hint.textContent = "Pas de clé publique : génère-la dans le guide SSH.";
    $("btnCopyRail").classList.add("hidden");
    $("genStatus").classList.add("hidden");
  }
}

async function copyText(text, btn) {
  const value = (text || "").trim();
  if (!value) return;
  try {
    await navigator.clipboard.writeText(value);
  } catch {
    const ta = document.createElement("textarea");
    ta.value = value;
    document.body.appendChild(ta);
    ta.select();
    document.execCommand("copy");
    ta.remove();
  }
  if (btn) {
    const old = btn.textContent;
    btn.textContent = "Copié";
    setTimeout(() => { btn.textContent = old; }, 1400);
  }
}

function pwshSnippet() {
  const host = $("sshHost").value.trim() || "IP";
  const user = $("sshUser").value.trim() || "ubuntu";
  return "ssh " + user + "@" + host
    + " \"mkdir -p ~/.ssh; chmod 700 ~/.ssh; touch ~/.ssh/authorized_keys; chmod 600 ~/.ssh/authorized_keys\"\n"
    + "type $HOME\\.ssh\\vps-autopilot.pub | ssh " + user + "@" + host + " \"cat >> ~/.ssh/authorized_keys\"";
}

function refreshPwsh() {
  $("pwshCmd").textContent = pwshSnippet();
}

function renderLiveWarnings(warnings) {
  const box = $("liveWarnings");
  if (!box) return;
  const list = Array.isArray(warnings) ? warnings.filter(Boolean) : [];
  if (!list.length) {
    box.classList.add("hidden");
    box.innerHTML = "";
    return;
  }
  box.classList.remove("hidden");
  box.innerHTML = "<p class=\"kicker\">Alertes sécurité</p>"
    + list.map((w) => "<div class=\"warn-item POLICY_BREAK\">" + w + "</div>").join("");
}

function setStatus(text, kind) {
  const el = $("statusLine");
  el.textContent = text;
  el.classList.toggle("err", kind === "err");
  el.classList.toggle("ok", kind === "ok");
}

let lastAppsRunning = [];
let busyTimer = null;
let busyStartedAt = 0;

function formatElapsed(ms) {
  const s = Math.max(0, Math.floor(ms / 1000));
  if (s < 60) return s + " s";
  const m = Math.floor(s / 60);
  const r = s % 60;
  return m + " min " + String(r).padStart(2, "0") + " s";
}

function renderBusySteps(steps, current) {
  const list = $("busySteps");
  if (!steps || !steps.length) {
    list.classList.add("hidden");
    list.innerHTML = "";
    return;
  }
  list.classList.remove("hidden");
  list.innerHTML = "";
  steps.forEach((text, i) => {
    const li = document.createElement("li");
    li.textContent = text;
    if (i < current) li.className = "done";
    else if (i === current) li.className = "current";
    list.appendChild(li);
  });
}

function showBusy({ risk, title, detail, steps, currentStep }) {
  $("busyRisk").textContent = risk || "Traitement";
  $("busyTitle").textContent = title || "Travail en cours";
  $("busyDetail").textContent = detail || "";
  renderBusySteps(steps, currentStep || 0);
  $("busyElapsed").textContent = "0 s";
  const overlay = $("busyOverlay");
  overlay.classList.remove("hidden");
  overlay.setAttribute("aria-hidden", "false");
  document.body.classList.add("is-busy");
  ["btnDiscover", "btnTrust", "btnInstallKey"].forEach((id) => {
    if ($(id)) $(id).disabled = true;
  });
  document.querySelectorAll("#installActions button, #appList button").forEach((b) => { b.disabled = true; });
  if ($("btnAppSave")) $("btnAppSave").disabled = true;
  busyStartedAt = Date.now();
  if (busyTimer) clearInterval(busyTimer);
  busyTimer = setInterval(() => {
    $("busyElapsed").textContent = "En cours depuis " + formatElapsed(Date.now() - busyStartedAt);
  }, 250);
}

function updateBusy({ title, detail, currentStep, steps } = {}) {
  if (title) $("busyTitle").textContent = title;
  if (detail) $("busyDetail").textContent = detail;
  if (typeof currentStep === "number") {
    renderBusySteps(steps || Array.from($("busySteps").children).map((li) => li.textContent), currentStep);
  }
}

function hideBusy() {
  if (busyTimer) {
    clearInterval(busyTimer);
    busyTimer = null;
  }
  const overlay = $("busyOverlay");
  overlay.classList.add("hidden");
  overlay.setAttribute("aria-hidden", "true");
  document.body.classList.remove("is-busy");
  ["btnDiscover", "btnTrust", "btnInstallKey"].forEach((id) => {
    if ($(id)) $(id).disabled = false;
  });
  document.querySelectorAll("#installActions button, #appList button").forEach((b) => { b.disabled = false; });
  if ($("btnAppSave")) $("btnAppSave").disabled = false;
}

function showResult({ ok, kicker, title, detail, next }) {
  const overlay = $("resultOverlay");
  const card = $("resultCard");
  card.classList.toggle("ok", !!ok);
  card.classList.toggle("err", !ok);
  $("resultKicker").textContent = kicker || (ok ? "Réussi" : "Échec");
  $("resultTitle").textContent = title || (ok ? "Opération terminée" : "Opération interrompue");
  $("resultDetail").textContent = detail || "";
  const nextEl = $("resultNext");
  nextEl.textContent = next || "";
  nextEl.hidden = !next;
  overlay.classList.remove("hidden");
  overlay.setAttribute("aria-hidden", "false");
  document.body.classList.add("is-busy");
  $("btnResultClose").focus();
}

function hideResult() {
  const overlay = $("resultOverlay");
  overlay.classList.add("hidden");
  overlay.setAttribute("aria-hidden", "true");
  document.body.classList.remove("is-busy");
}

async function loadRuntime() {
  const r = await fetch("/api/v1/runtime");
  const j = await r.json();
  $("modePill").textContent = j.sshConfigured ? "SSH réel" : "Prêt pour le VPS";
  $("phasePill").textContent = j.phase || "V0";
  if (j.host) $("sshHost").value = j.host;
  if (j.user) $("sshUser").value = j.user;
  if (j.port) $("sshPort").value = j.port;
  fillKey(j.publicKey || "");
  refreshPwsh();
  return j;
}

async function acceptHost() {
  const t = target();
  if (!t.host) {
    setStatus("Indique l’IP du VPS.", "err");
    return;
  }
  showBusy({
    risk: "READ",
    title: "Empreinte SSH",
    detail: "Lecture de la clé d’hôte de " + t.host + ". Rien n’est écrit sur le VPS.",
  });
  setStatus("Lecture de l’empreinte…");
  try {
    const r = await fetch("/api/v1/ssh/accept-host", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(t),
    });
    const j = await r.json();
    if (!r.ok) throw new Error(j.error || r.statusText);
    setStatus("Empreinte enregistrée : " + (j.fingerprint || "ok") + ". Tu peux analyser.", "ok");
  } catch (e) {
    setStatus(e.message, "err");
  } finally {
    hideBusy();
  }
}

async function discover() {
  const t = target();
  if (!t.host || !t.user) {
    setStatus("Renseigne l’IP et l’utilisateur SSH avant d’analyser.", "err");
    return;
  }
  const discoverSteps = [
    "Enregistrer l’empreinte d’hôte",
    "Lire le VPS en SSH (OS, CPU, services, logiciels)",
    "Construire le tableau et le plan d’install",
  ];
  showBusy({
    risk: "READ",
    title: "Analyse du VPS",
    detail: "Connexion à " + t.user + "@" + t.host + ". Lecture seule : aucune installation, aucun paquet.",
    steps: discoverSteps,
    currentStep: 0,
  });
  setStatus("Analyse en cours…");
  try {
    const trust = await fetch("/api/v1/ssh/accept-host", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(t),
    });
    const tj = await trust.json();
    if (!trust.ok) throw new Error(tj.error || trust.statusText);
    updateBusy({
      currentStep: 1,
      steps: discoverSteps,
      detail: "Empreinte OK. Script de discovery read-only en cours sur " + t.host + ".",
    });
    const r = await fetch("/api/v1/discover", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(t),
    });
    const j = await r.json();
    if (!r.ok) throw new Error(j.error || r.statusText);
    updateBusy({ currentStep: 2, steps: discoverSteps, detail: "Réponse reçue. Mise à jour du tableau." });
    if (!paint(j.snapshot, j.report, j.mode)) {
      setStatus("Réponse ignorée (pas une discovery SSH réelle).", "err");
      return;
    }
    setStatus("Discovery SSH terminée. Rien n’a été modifié sur le VPS.", "ok");
  } catch (e) {
    setStatus(e.message, "err");
  } finally {
    hideBusy();
  }
}

async function installKey() {
  const t = target();
  const passwordInput = $("sshPassword");
  const password = passwordInput.value;
  passwordInput.classList.remove("need");
  if (!t.host) {
    setStatus("Indique l’IP du VPS.", "err");
    return;
  }
  if (!t.user) {
    setStatus("Indique l’utilisateur SSH (ubuntu ou root).", "err");
    return;
  }
  if (!password) {
    passwordInput.classList.add("need");
    passwordInput.focus();
    setStatus("Colle le mot de passe du mail OVH, puis réessaie. Il n’est pas enregistré.", "err");
    return;
  }
  const plan =
    "CHANGE : écrire 1 ligne dans ~/.ssh/authorized_keys de "
    + t.user + "@" + t.host + ":" + t.port
    + "\n\nUniquement la clé de l’agent. Le mot de passe n’est pas enregistré.\n\nContinuer ?";
  if (!window.confirm(plan)) {
    return;
  }
  showBusy({
    risk: "CHANGE",
    title: "Installation de la clé SSH",
    detail: "Écriture d’une ligne dans ~/.ssh/authorized_keys de "
      + t.user + "@" + t.host + ". Le mot de passe n’est pas enregistré.",
    steps: ["Connexion avec le mot de passe OVH", "Ajouter la clé de l’agent si elle manque"],
    currentStep: 0,
  });
  setStatus("Installation de la clé…");
  try {
    const r = await fetch("/api/v1/ssh/install-key", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        host: t.host,
        port: t.port,
        user: t.user,
        password,
        confirm: true,
      }),
    });
    const j = await r.json();
    if (!r.ok) throw new Error(j.error || r.statusText);
    passwordInput.value = "";
    const already = j.outcome === "KEY_ALREADY_PRESENT";
    const msg = already
      ? "Clé déjà présente. Tu peux analyser."
      : "Clé installée. Clique « Analyser le VPS ».";
    setStatus(msg, "ok");
    hideBusy();
    showResult({
      ok: true,
      kicker: "CHANGE · réussi",
      title: already ? "Clé déjà sur ce compte" : "Clé SSH installée",
      detail: already
        ? "La clé de l’agent était déjà dans authorized_keys. Rien n’a été dupliqué."
        : "Une ligne a été ajoutée dans ~/.ssh/authorized_keys. Le mot de passe n’est pas enregistré.",
      next: "Clique « Analyser le VPS » pour lire ce serveur.",
    });
  } catch (e) {
    setStatus(e.message, "err");
    hideBusy();
    showResult({
      ok: false,
      kicker: "CHANGE · échec",
      title: "La clé n’a pas été installée",
      detail: e.message,
      next: "Vérifie l’IP, l’utilisateur et le mot de passe du mail, puis réessaie.",
    });
  }
}

document.querySelectorAll("[data-view]").forEach((el) => {
  el.addEventListener("click", (e) => {
    const view = el.getAttribute("data-view");
    if (!view) return;
    e.preventDefault();
    showView(view);
  });
});
window.addEventListener("hashchange", () => showView(currentView()));

$("vpsOs").addEventListener("change", () => {
  const hint = OS_USER[$("vpsOs").value] || "";
  const current = $("sshUser").value.trim();
  const known = Object.values(OS_USER);
  if (!current || known.includes(current)) {
    $("sshUser").value = hint;
  }
});
$("btnDiscover").addEventListener("click", discover);
$("btnTrust").addEventListener("click", acceptHost);
$("btnInstallKey").addEventListener("click", installKey);
if ($("btnAppSave")) $("btnAppSave").addEventListener("click", saveApp);
$("btnResultClose").addEventListener("click", hideResult);
document.addEventListener("keydown", (e) => {
  if (e.key !== "Escape") return;
  if (!$("resultOverlay").classList.contains("hidden")) {
    hideResult();
  }
});
$("btnCopyKey").addEventListener("click", (e) => copyText($("guidePubKey").textContent, e.currentTarget));
$("btnCopyRail").addEventListener("click", (e) => copyText($("guidePubKey").textContent, e.currentTarget));
$("btnCopyGen").addEventListener("click", (e) => copyText($("genCmd").textContent, e.currentTarget));
$("btnCopyKvm").addEventListener("click", (e) => copyText($("kvmCmd").textContent, e.currentTarget));
$("btnCopyPwsh").addEventListener("click", (e) => copyText($("pwshCmd").textContent, e.currentTarget));
$("sshHost").addEventListener("input", refreshPwsh);
$("sshUser").addEventListener("input", refreshPwsh);
$("sshPassword").addEventListener("input", () => $("sshPassword").classList.remove("need"));

function esc(text) {
  return String(text || "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

function explainBlock(why, what) {
  const details = document.createElement("details");
  details.className = "stack-explain";
  details.innerHTML = "<summary>Pourquoi, et ce que ça fera</summary>"
    + "<p><strong>Pourquoi.</strong> " + esc(why) + "</p>"
    + "<p><strong>Ce que l’agent fera.</strong> " + esc(what) + "</p>";
  return details;
}

function renderStack(view) {
  const list = $("stackList");
  const warnBox = $("stackWarnings");
  if (!list || !view) return;
  const enabled = new Set(view.enabled || []);
  const chosen = view.details || {};
  const presentMap = view.presentDetails || {};
  const openFolds = new Set(
    Array.from(list.querySelectorAll("details.stack-details-fold[open]")).map((el) => el.dataset.component)
  );
  list.innerHTML = "";
  (view.catalog || []).forEach((c) => {
    const section = document.createElement("article");
    section.className = "stack-section" + (c.discouragedWhenOn ? " discouraged" : "");
    const row = document.createElement("label");
    row.className = "stack-item";
    const box = document.createElement("input");
    box.type = "checkbox";
    box.className = "stack-parent";
    box.checked = enabled.has(c.id);
    box.dataset.id = c.id;
    box.addEventListener("change", () => {
      section.querySelectorAll("input.stack-detail-box").forEach((child) => {
        const present = child.dataset.present === "1";
        child.disabled = !box.checked || present;
        if (box.checked && !present && !section.querySelector("input.stack-detail-box:checked")) {
          child.checked = true;
        }
      });
      if (box.checked) {
        const fold = section.querySelector("details.stack-details-fold");
        if (fold) fold.open = true;
      }
      saveStack();
    });
    const text = document.createElement("span");
    text.innerHTML = "<strong>" + esc(c.label) + "</strong><small>" + esc(c.phase) + " · " + esc(c.summary) + "</small>";
    row.append(box, text);
    section.appendChild(row);
    if (c.why || c.what) {
      section.appendChild(explainBlock(c.why, c.what));
    }
    const items = c.details || [];
    if (items.length) {
      const wrap = document.createElement("div");
      wrap.className = "stack-details";
      const already = new Set(presentMap[c.id] || []);
      const selected = new Set(
        (chosen[c.id] || items.filter((d) => d.defaultEnabled && !d.present && !already.has(d.id)).map((d) => d.id))
          .filter((id) => !already.has(id) && !items.some((d) => d.id === id && d.present))
      );
      let remaining = 0;
      items.forEach((d) => {
        const installed = d.present === true || already.has(d.id);
        if (!installed) remaining += 1;
        const block = document.createElement("div");
        block.className = "stack-detail" + (installed ? " installed" : "");
        const lab = document.createElement("label");
        const cb = document.createElement("input");
        cb.type = "checkbox";
        cb.className = "stack-detail-box";
        cb.dataset.parent = c.id;
        cb.dataset.detail = d.id;
        cb.dataset.present = installed ? "1" : "0";
        cb.checked = enabled.has(c.id) && !installed && selected.has(d.id);
        cb.disabled = installed || !enabled.has(c.id);
        cb.addEventListener("change", () => {
          const any = Array.from(section.querySelectorAll("input.stack-detail-box")).some((el) => el.checked);
          if (!any) {
            box.checked = false;
            section.querySelectorAll("input.stack-detail-box").forEach((el) => { el.disabled = true; });
          }
          saveStack();
        });
        const span = document.createElement("span");
        span.textContent = installed ? d.label + " · déjà installé" : d.label;
        lab.append(cb, span);
        block.appendChild(lab);
        if (d.why || d.what) {
          block.appendChild(explainBlock(d.why, d.what));
        }
        wrap.appendChild(block);
      });
      const fold = document.createElement("details");
      fold.className = "stack-details-fold";
      fold.dataset.component = c.id;
      if (openFolds.has(c.id)) {
        fold.open = true;
      }
      const summary = document.createElement("summary");
      summary.textContent = remaining === 0
        ? "Détails · " + items.length + " · déjà sur ce VPS"
        : "Détails · " + remaining + " à installer — bouton dans Installations, au-dessus";
      fold.append(summary, wrap);
      section.appendChild(fold);
    }
    list.appendChild(section);
  });
  (view.notOffered || []).forEach((r) => {
    const note = document.createElement("article");
    note.className = "stack-section stack-refused";
    const title = document.createElement("p");
    title.className = "kicker";
    title.textContent = "Non proposé — pas une option d’install";
    const name = document.createElement("strong");
    name.textContent = r.label;
    note.append(title, name);
    if (r.why || r.what) {
      note.appendChild(explainBlock(r.why, r.what));
    }
    list.appendChild(note);
  });
  const warnings = view.warnings || [];
  if (!warnings.length) {
    warnBox.hidden = true;
    warnBox.innerHTML = "";
    return;
  }
  warnBox.hidden = false;
  warnBox.innerHTML = warnings.map((w) =>
    '<div class="warn-item ' + (w.impact || "WARNING") + '">' + esc(w.message || "") + "</div>"
  ).join("");
}

function selectedStackIds() {
  return Array.from(document.querySelectorAll("#stackList input.stack-parent"))
    .filter((el) => el.checked)
    .map((el) => el.dataset.id);
}

function selectedStackDetails() {
  const out = {};
  document.querySelectorAll("#stackList input.stack-parent:checked").forEach((parent) => {
    const id = parent.dataset.id;
    out[id] = Array.from(document.querySelectorAll('#stackList input.stack-detail-box[data-parent="' + id + '"]:checked'))
      .map((el) => el.dataset.detail);
  });
  return out;
}

async function loadStack() {
  const r = await fetch("/api/v1/stack");
  if (!r.ok) return;
  renderStack(await r.json());
}

async function saveStack() {
  const enabled = selectedStackIds();
  const details = selectedStackDetails();
  let r = await fetch("/api/v1/stack", {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ enabled, details, acknowledgeWarnings: false }),
  });
  let j = await r.json();
  if (r.status === 409 && j.policyBreak) {
    const lines = (j.warnings || []).map((w) => "• " + w.message).join("\n");
    const ok = window.confirm(
      "Cette sélection contredit la politique.\n\n" + lines + "\n\nEnregistrer quand même ? (rien n’est installé maintenant)"
    );
    if (!ok) {
      await loadStack();
      return;
    }
    r = await fetch("/api/v1/stack", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ enabled, details, acknowledgeWarnings: true }),
    });
    j = await r.json();
  }
  if (!r.ok) {
    setStatus(j.error || "Pile non enregistrée.", "err");
    await loadStack();
    return;
  }
  renderStack(j);
  setStatus(j.policyBreak
    ? "Pile enregistrée avec écarts de politique (vus ci-dessus)."
    : "Pile voulue enregistrée. Analyse puis installe ce qui manque.", "ok");
  if (!$("board").classList.contains("hidden")) {
    loadPlan();
  }
}

function renderAppsGate(snapshot) {
  const gate = $("appsGate");
  if (!gate) return;
  if (!snapshot || !snapshot.deploy || !snapshot.deploy.present) {
    gate.hidden = false;
    gate.textContent = "Le compte deploy n’est pas encore sur ce VPS. Le bouton Installer est dans « Pile et installations », en haut de la liste (pas besoin d’ouvrir le menu).";
    return;
  }
  gate.hidden = true;
  gate.textContent = "";
}

function renderPlan(plan) {
  const box = $("installActions");
  if (!box) return;
  const actions = plan && plan.actions ? plan.actions.slice() : [];
  const rank = { READY: 0, PLANNED: 1, SKIP: 2, REFUSED: 3 };
  actions.sort((a, b) => (rank[a.status] ?? 9) - (rank[b.status] ?? 9));
  if (!actions.length) {
    box.innerHTML = "<p class=\"hint\">Rien à installer pour cette pile.</p>";
    return;
  }
  box.innerHTML = "";
  actions.forEach((a) => {
    const row = document.createElement("div");
    row.className = "action-row " + a.status;
    const info = document.createElement("div");
    const tag = a.status === "SKIP" ? "déjà installé" : a.status;
    info.innerHTML = "<span class=\"tag\">" + tag + "</span><strong>" + a.label + "</strong>"
      + "<p>" + (a.reason || "") + (a.status === "READY" && a.steps && a.steps.length ? " · " + a.steps.join(" → ") : "") + "</p>";
    row.appendChild(info);
    if (a.status === "READY") {
      const btn = document.createElement("button");
      btn.type = "button";
      btn.className = "change";
      btn.textContent = "Installer";
      btn.addEventListener("click", () => applyInstall(a));
      row.appendChild(btn);
    }
    box.appendChild(row);
  });
}

async function loadPlan() {
  const r = await fetch("/api/v1/plan");
  if (!r.ok) return;
  renderPlan(await r.json());
}

async function applyInstall(action) {
  const t = target();
  if (!t.host || !t.user) {
    setStatus("Indique IP et utilisateur SSH.", "err");
    return;
  }
  const planText = "CHANGE : " + action.label + " sur " + t.user + "@" + t.host
    + "\n\n" + (action.steps || []).map((s) => "• " + s).join("\n")
    + "\n\nRôle versionné, idempotent. Continuer ?";
  if (!window.confirm(planText)) {
    return;
  }
  const extras = {
    security: "Baseline : UFW (22 seulement), fail2ban sshd, mises à jour auto, SSH clés seulement (plus de mot de passe, root fermé). Ne change pas le port SSH.",
    docker: "apt installe docker.io + compose, active le service, ajoute l’utilisateur SSH au groupe docker.",
    traefik: "Traefik dans Docker, réseau proxy, UFW 80/443. Aucune app exposée. Pull d’image : jusqu’à 5 minutes.",
    mariadb: "Conteneur MariaDB interne (pas de port public). Pull d’image : jusqu’à 5 minutes.",
    deploy: "Compte deploy : groupe docker, pas de sudo, mot de passe verrouillé. La clé de l’agent est copiée. AllowUsers inclut deploy. Aucune app n’est lancée.",
  };
  showBusy({
    risk: "CHANGE",
    title: "Installation — " + action.label,
    detail: (extras[action.id] || "Rôle versionné, idempotent.")
      + " Cible " + t.user + "@" + t.host + ". "
      + (action.id === "traefik" || action.id === "mariadb" ? "Jusqu’à 5 minutes (pull d’image). " : "1 à 3 minutes. ")
      + "Ne ferme pas cette page.",
    steps: action.steps || [],
    currentStep: 0,
  });
  setStatus("Installation de " + action.label + "…");
  const nextHint = {
    security: "Si Docker manque encore sur ce VPS, le bouton Installer reste visible.",
    docker: "Ensuite : Traefik, seulement si la sécurité est déjà conforme ici.",
    traefik: "Ensuite : MariaDB si elle manque. Traefik n’expose aucune app tant qu’un routeur n’existe pas.",
    mariadb: "Le mot de passe root est uniquement sur le VPS. Tes apps viendront plus tard.",
    deploy: "Les apps (Git → image) viendront ensuite, sous ce compte, pas en root.",
  };
  try {
    const r = await fetch("/api/v1/install", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        component: action.id,
        host: t.host,
        port: t.port,
        user: t.user,
        confirm: true,
      }),
    });
    const j = await r.json();
    if (!r.ok) throw new Error(j.error || r.statusText);
    updateBusy({
      detail: action.label + " terminé. Nouvelle analyse du VPS…",
      currentStep: (action.steps || []).length,
      steps: action.steps || [],
    });
    paint(j.snapshot, j.report, "ssh");
    const elapsed = formatElapsed(Date.now() - busyStartedAt);
    setStatus(action.label + " : terminé. Nouvelle analyse affichée.", "ok");
    hideBusy();
    showResult({
      ok: true,
      kicker: "CHANGE · réussi",
      title: action.label + " installé",
      detail: "Le rôle versionné s’est terminé en " + elapsed + ". Le tableau a été rafraîchi à partir d’une nouvelle analyse.",
      next: nextHint[action.id] || "Tu peux analyser à nouveau si tu veux vérifier.",
    });
  } catch (e) {
    setStatus(e.message, "err");
    hideBusy();
    showResult({
      ok: false,
      kicker: "CHANGE · échec",
      title: action.label + " n’a pas abouti",
      detail: e.message,
      next: "L’état déjà connu de ce VPS est conservé. Vérifie le message, puis réessaie.",
    });
  }
}

async function loadApps() {
  const box = $("appList");
  if (!box) return;
  try {
    const r = await fetch("/api/v1/apps");
    const j = await r.json();
    renderAppList(j.apps || []);
  } catch {
    box.innerHTML = "<p class=\"hint\">Liste d’apps injoignable.</p>";
  }
}

function renderAppList(apps) {
  const box = $("appList");
  if (!box) return;
  const running = new Set(lastAppsRunning);
  if (!apps.length) {
    box.innerHTML = "<p class=\"hint\">Aucune app enregistrée. Remplis le formulaire puis Enregistrer.</p>";
    return;
  }
  box.innerHTML = "";
  apps.forEach((a) => {
    const row = document.createElement("div");
    const on = running.has(a.id);
    row.className = "action-row " + (on ? "SKIP" : "READY");
    const info = document.createElement("div");
    info.innerHTML = "<span class=\"tag\">" + (on ? "en cours" : "déclarée") + "</span><strong>"
      + esc(a.id) + "</strong><p>" + esc(a.hostname) + " · " + esc(a.gitUrl) + " @ " + esc(a.gitRef)
      + " · port " + a.port + "</p>";
    row.appendChild(info);
    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "change";
    btn.textContent = on ? "Mettre à jour" : "Déployer";
    btn.addEventListener("click", () => deployApp(a));
    row.appendChild(btn);
    box.appendChild(row);
  });
}

async function saveApp() {
  const spec = {
    id: $("appId").value.trim(),
    gitUrl: $("appGit").value.trim(),
    gitRef: $("appRef").value.trim() || "main",
    hostname: $("appHostName").value.trim(),
    port: Number($("appPort").value) || 8080,
  };
  try {
    const cur = await fetch("/api/v1/apps").then((r) => r.json());
    const apps = (cur.apps || []).filter((a) => a.id !== spec.id);
    apps.push(spec);
    const r = await fetch("/api/v1/apps", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ apps }),
    });
    const j = await r.json();
    if (!r.ok) throw new Error(j.error || r.statusText);
    renderAppList(j.apps || []);
    setStatus("App " + spec.id + " enregistrée. Rien n’est déployé tant que tu n’as pas confirmé Déployer.", "ok");
  } catch (e) {
    setStatus(e.message, "err");
  }
}

async function deployApp(app) {
  const t = target();
  if (!t.host || !t.user) {
    setStatus("Indique IP et utilisateur SSH.", "err");
    return;
  }
  const planText = "CHANGE : déployer " + app.id + " sur " + t.user + "@" + t.host
    + "\n\nGit " + app.gitUrl + " (" + app.gitRef + ")"
    + "\nHôte public " + app.hostname + " · port interne " + app.port
    + "\n\nClone + build + Traefik. Aucun port publié. DNS à ta charge. Continuer ?";
  if (!window.confirm(planText)) {
    return;
  }
  showBusy({
    risk: "CHANGE",
    title: "Déploiement — " + app.id,
    detail: "Clone HTTPS, image Docker, labels Traefik. Cible " + t.user + "@" + t.host
      + ". Jusqu’à 10 minutes (build). Ne ferme pas cette page.",
    steps: ["Cloner le dépôt", "Construire l’image", "Brancher Traefik (pas de port public)"],
    currentStep: 0,
  });
  setStatus("Déploiement de " + app.id + "…");
  try {
    const r = await fetch("/api/v1/apps/deploy", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        id: app.id,
        host: t.host,
        port: t.port,
        user: t.user,
        confirm: true,
      }),
    });
    const j = await r.json();
    if (!r.ok) throw new Error(j.error || r.statusText);
    paint(j.snapshot, j.report, "ssh");
    const elapsed = formatElapsed(Date.now() - busyStartedAt);
    hideBusy();
    setStatus(app.id + " : déployé. Nouvelle analyse affichée.", "ok");
    showResult({
      ok: true,
      kicker: "CHANGE · réussi",
      title: app.id + " déployé",
      detail: "Image construite et branchée sur Traefik en " + elapsed + ". Pointe le DNS de "
        + app.hostname + " vers ce VPS.",
      next: "Les grants MariaDB par projet viendront plus tard.",
    });
  } catch (e) {
    hideBusy();
    setStatus(e.message, "err");
    showResult({
      ok: false,
      kicker: "CHANGE · échec",
      title: app.id + " n’a pas abouti",
      detail: e.message,
      next: "Dockerfile à la racine, Git HTTPS public, compte deploy et Traefik déjà là.",
    });
  }
}

showView(currentView());
refreshPwsh();
loadRuntime().then(loadStack).catch(() => {
  setStatus("API injoignable.", "err");
});
