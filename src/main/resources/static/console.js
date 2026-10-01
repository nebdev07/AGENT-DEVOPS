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
    if ($("platformsCard")) $("platformsCard").classList.add("hidden");
    $("stackCard").classList.add("hidden");
    $("empty").classList.remove("hidden");
    return false;
  }
  $("empty").classList.add("hidden");
  $("board").classList.remove("hidden");
  $("stackCard").classList.remove("hidden");
  $("installCard").classList.remove("hidden");
  if ($("appsCard")) $("appsCard").classList.remove("hidden");
  if ($("platformsCard")) $("platformsCard").classList.remove("hidden");
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
  setSoft("swBackups", snapshot.backups?.present);
  setFirewall(snapshot.firewall?.ufwStatus, snapshot.firewall?.fail2banStatus);
  renderAppsGate(snapshot);
  renderLiveWarnings(snapshot.warnings);
  $("report").textContent = report || "—";
  lastAppsRunning = Array.isArray(snapshot.apps) ? snapshot.apps : [];
  if (mode) $("modePill").textContent = mode === "ssh" ? "SSH réel" : "Prêt pour le VPS";
  journey.snapshot = snapshot;
  document.body.classList.add("connected");
  renderJourney();
  loadStack().then(loadPlan);
  loadApps();
  loadPlatforms();
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
  document.querySelectorAll("#installActions button, #appList button, #platList button").forEach((b) => { b.disabled = true; });
  if ($("btnAppSave")) $("btnAppSave").disabled = true;
  if ($("btnPlatSave")) $("btnPlatSave").disabled = true;
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
  document.querySelectorAll("#installActions button, #appList button, #platList button").forEach((b) => { b.disabled = false; });
  if ($("btnAppSave")) $("btnAppSave").disabled = false;
  if ($("btnPlatSave")) $("btnPlatSave").disabled = false;
}

function showResult({ ok, kicker, title, detail, next, link }) {
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
  const wrap = $("resultLinkWrap");
  const a = $("resultLink");
  if (wrap && a) {
    if (link) {
      a.href = link;
      a.textContent = link;
      wrap.hidden = false;
    } else {
      a.removeAttribute("href");
      a.textContent = "";
      wrap.hidden = true;
    }
  }
  overlay.classList.remove("hidden");
  overlay.setAttribute("aria-hidden", "false");
  document.body.classList.add("is-busy");
  $("btnResultClose").focus();
}

function hideResult() {
  const overlay = $("resultOverlay");
  overlay.classList.add("hidden");
  overlay.setAttribute("aria-hidden", "true");
  if ($("confirmOverlay")?.classList.contains("hidden") && $("busyOverlay")?.classList.contains("hidden")) {
    document.body.classList.remove("is-busy");
  }
}

let confirmResolver = null;

function hideConfirm() {
  const overlay = $("confirmOverlay");
  if (!overlay) return;
  overlay.classList.add("hidden");
  overlay.setAttribute("aria-hidden", "true");
  if ($("resultOverlay")?.classList.contains("hidden") && $("busyOverlay")?.classList.contains("hidden")) {
    document.body.classList.remove("is-busy");
  }
}

/**
 * Popup CHANGE : liste ce qui sera fait. Résout true/false.
 */
function showConfirmPlan({ kicker, title, detail, actions, warnings, canProceed, blocker }) {
  return new Promise((resolve) => {
    confirmResolver = resolve;
    const overlay = $("confirmOverlay");
    $("confirmKicker").textContent = kicker || "CHANGE · confirmation";
    $("confirmTitle").textContent = title || "Confirmer";
    $("confirmDetail").textContent = detail || "Voici ce que l’agent va faire si tu confirmes.";
    const list = $("confirmActions");
    list.innerHTML = "";
    (actions || []).forEach((a) => {
      const li = document.createElement("li");
      li.textContent = a;
      list.appendChild(li);
    });
    const warn = $("confirmWarnings");
    if (warnings && warnings.length) {
      warn.hidden = false;
      warn.textContent = warnings.join("\n");
    } else {
      warn.hidden = true;
      warn.textContent = "";
    }
    const block = $("confirmBlocker");
    const okBtn = $("btnConfirmOk");
    if (blocker) {
      block.hidden = false;
      block.textContent = "Bloqué : " + blocker;
    } else {
      block.hidden = true;
      block.textContent = "";
    }
    okBtn.disabled = canProceed === false;
    okBtn.textContent = canProceed === false ? "Impossible de continuer" : "Confirmer le CHANGE";
    overlay.classList.remove("hidden");
    overlay.setAttribute("aria-hidden", "false");
    document.body.classList.add("is-busy");
    (canProceed === false ? $("btnConfirmCancel") : okBtn).focus();
  });
}

function settleConfirm(ok) {
  hideConfirm();
  if (confirmResolver) {
    const r = confirmResolver;
    confirmResolver = null;
    r(!!ok);
  }
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
if ($("btnAppClear")) $("btnAppClear").addEventListener("click", clearAppForm);
if ($("btnPublicDomain")) $("btnPublicDomain").addEventListener("click", savePublicDomain);
if ($("btnGitKeyGen")) $("btnGitKeyGen").addEventListener("click", generateGitDeployKey);
if ($("btnGitKeyCopy")) $("btnGitKeyCopy").addEventListener("click", copyGitDeployKey);
if ($("btnPlatAddSvc")) $("btnPlatAddSvc").addEventListener("click", () => addPlatServiceRow(null));
if ($("btnPlatSave")) $("btnPlatSave").addEventListener("click", savePlatform);
if ($("btnPlatClear")) $("btnPlatClear").addEventListener("click", clearPlatForm);
if ($("btnPlatArc")) $("btnPlatArc").addEventListener("click", fillArcPreset);
if ($("btnPlatRestore")) $("btnPlatRestore").addEventListener("click", restorePlatform);
if ($("platRouting")) $("platRouting").addEventListener("change", syncPlatRoutingHint);
$("btnResultClose").addEventListener("click", hideResult);
if ($("btnConfirmCancel")) $("btnConfirmCancel").addEventListener("click", () => settleConfirm(false));
if ($("btnConfirmOk")) $("btnConfirmOk").addEventListener("click", () => {
  if ($("btnConfirmOk").disabled) return;
  settleConfirm(true);
});
document.addEventListener("keydown", (e) => {
  if (e.key !== "Escape") return;
  if ($("confirmOverlay") && !$("confirmOverlay").classList.contains("hidden")) {
    settleConfirm(false);
    return;
  }
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
  journey.plan = plan;
  renderJourney();
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
    redis: "Conteneur Redis sur le réseau data (pas de port public), requirepass hors git.",
    backups: "Dump MariaDB chiffré sur le VPS, copie vers state/backups/ sur ce poste, preuve de déchiffrement locale.",
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
    const apps = j.apps || [];
    renderAppList(apps);
    if (apps.length && !$("appGit").value.trim() && !$("appId").value.trim()) {
      // Ne préremplit plus avec la 1re app : on ajoute souvent une 2e.
    }
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
    info.innerHTML = "<span class=\"tag\">" + (on ? "en ligne" : "déclarée") + "</span><strong>"
      + esc(a.id) + "</strong><p>"
      + (on
        ? '<a class="app-public-link" href="https://' + esc(a.hostname) + '/" target="_blank" rel="noopener noreferrer">https://'
          + esc(a.hostname) + '/</a> · '
        : esc(a.hostname) + " · ")
      + esc(a.gitUrl) + " @ " + esc(a.gitRef)
      + " · port " + a.port + "</p>";
    row.appendChild(info);
    const actions = document.createElement("div");
    actions.className = "row-actions";
    const btnEdit = document.createElement("button");
    btnEdit.type = "button";
    btnEdit.className = "ghost";
    btnEdit.textContent = "Modifier";
    btnEdit.title = "Remplir le formulaire pour changer cette ligne";
    btnEdit.addEventListener("click", () => editApp(a));
    const btnDel = document.createElement("button");
    btnDel.type = "button";
    btnDel.className = "ghost ghost-danger";
    btnDel.textContent = "Supprimer";
    btnDel.title = "Retirer de la liste locale (ne stoppe pas le conteneur VPS)";
    btnDel.addEventListener("click", () => deleteApp(a));
    const btnDeploy = document.createElement("button");
    btnDeploy.type = "button";
    btnDeploy.className = "change";
    btnDeploy.textContent = on ? "Mettre à jour" : "Déployer";
    btnDeploy.addEventListener("click", () => deployApp(a));
    actions.appendChild(btnEdit);
    actions.appendChild(btnDel);
    actions.appendChild(btnDeploy);
    row.appendChild(actions);
    box.appendChild(row);
  });
}

function suggestedAppHost(slug) {
  const base = $("publicBaseDomain") ? $("publicBaseDomain").value.trim().toLowerCase()
    .replace(/^https?:\/\//, "").replace(/\/$/, "").replace(/^\*\./, "") : "";
  const id = (slug || "app").toLowerCase().replace(/[^a-z0-9-]/g, "") || "app";
  if (base && /^[a-z0-9]([a-z0-9.-]*[a-z0-9])?$/.test(base)) {
    return id + "." + base;
  }
  const ip = $("sshHost") ? $("sshHost").value.trim() : "";
  if (!/^\d{1,3}(\.\d{1,3}){3}$/.test(ip)) {
    return "";
  }
  return id + "." + ip + ".sslip.io";
}

async function loadPublicDomain() {
  try {
    const r = await fetch("/api/v1/apps/public-domain");
    const j = await r.json();
    if (!r.ok) return;
    if ($("publicBaseDomain")) $("publicBaseDomain").value = j.baseDomain || "";
    if ($("publicDomainHint") && j.hint) $("publicDomainHint").textContent = j.hint;
  } catch (_) { /* ignore */ }
}

async function savePublicDomain() {
  const base = $("publicBaseDomain") ? $("publicBaseDomain").value.trim() : "";
  try {
    const r = await fetch("/api/v1/apps/public-domain", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ baseDomain: base }),
    });
    const j = await r.json();
    if (!r.ok) throw new Error(j.error || r.statusText);
    if ($("publicBaseDomain")) $("publicBaseDomain").value = j.baseDomain || "";
    if ($("publicDomainHint") && j.hint) $("publicDomainHint").textContent = j.hint;
    setStatus(j.baseDomain
      ? "Domaine public « " + j.baseDomain + " » enregistré. Les nouvelles apps proposeront https://id." + j.baseDomain + "/"
      : "Domaine public effacé — suggestions sslip.", "ok");
  } catch (e) {
    setStatus(e.message, "err");
  }
}

function looksLikeGitUrl(value) {
  const v = (value || "").trim().toLowerCase();
  return v.startsWith("http://") || v.startsWith("https://") || v.startsWith("git@")
    || v.startsWith("ssh://") || v.includes("github.com") || v.includes(".git");
}

function normalizeGitUrl(value) {
  let g = (value || "").trim();
  if (!g) return "";
  if (!g.endsWith(".git") && /^https:\/\/github\.com\/[^/]+\/[^/]+\/?$/i.test(g)) {
    g = g.replace(/\/$/, "") + ".git";
  }
  if (!g.endsWith(".git") && /^git@(github|gitlab)\.com:/i.test(g)) {
    g += ".git";
  }
  return g;
}

async function loadGitDeployKey() {
  const ta = $("gitKeyPub");
  const fp = $("gitKeyFp");
  const hint = $("gitKeyHint");
  if (!ta) return;
  try {
    const r = await fetch("/api/v1/git-deploy-key");
    const j = await r.json();
    if (!r.ok) throw new Error(j.error || r.statusText);
    ta.value = j.publicKey || "";
    if (fp) fp.textContent = j.fingerprint || "";
    if (hint && j.note) hint.textContent = j.note;
  } catch {
    ta.value = "";
    if (fp) fp.textContent = "";
  }
}

async function generateGitDeployKey() {
  const rotate = !!($("gitKeyPub") && $("gitKeyPub").value.trim());
  if (rotate && !window.confirm("Remplacer la clé Git locale ? Les Deploy keys déjà collées sur GitHub/GitLab ne marcheront plus.")) {
    return;
  }
  try {
    const r = await fetch("/api/v1/git-deploy-key", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ confirm: true, rotate }),
    });
    const j = await r.json();
    if (!r.ok) throw new Error(j.error || r.statusText);
    if ($("gitKeyPub")) $("gitKeyPub").value = j.publicKey || "";
    if ($("gitKeyFp")) $("gitKeyFp").textContent = j.fingerprint || "";
    if ($("gitKeyHint") && j.note) $("gitKeyHint").textContent = j.note;
    setStatus("Clé Git lecture seule prête. Copie-la en Deploy key (read-only).", "ok");
  } catch (e) {
    setStatus(e.message, "err");
  }
}

async function copyGitDeployKey() {
  const v = $("gitKeyPub") ? $("gitKeyPub").value.trim() : "";
  if (!v) {
    setStatus("Pas encore de clé publique — génère-la d’abord.", "err");
    return;
  }
  try {
    await navigator.clipboard.writeText(v);
    setStatus("Clé publique copiée.", "ok");
  } catch {
    setStatus("Copie impossible — sélectionne le champ et Ctrl+C.", "err");
  }
}

function fillAppForm(app) {
  if (!app) return;
  if ($("appId")) $("appId").value = app.id || "";
  if ($("appGit")) $("appGit").value = app.gitUrl || "";
  if ($("appRef")) $("appRef").value = app.gitRef || "main";
  if ($("appHostName")) $("appHostName").value = app.hostname || "";
  if ($("appPort") && app.port) $("appPort").value = String(app.port);
}

function clearAppForm() {
  if ($("appId")) $("appId").value = "";
  if ($("appGit")) $("appGit").value = "";
  if ($("appRef")) $("appRef").value = "main";
  if ($("appHostName")) $("appHostName").value = "";
  if ($("appPort")) $("appPort").value = "8080";
  setAppFormStatus("Formulaire vidé — saisis une nouvelle app ou clique Modifier sur une ligne.", "ok");
  setAppSaveLabel(false);
  if ($("appId")) $("appId").focus();
}

function setAppSaveLabel(editing) {
  const btn = $("btnAppSave");
  if (!btn) return;
  btn.textContent = editing ? "Enregistrer les modifications" : "Enregistrer dans la liste";
}

function editApp(app) {
  fillAppForm(app);
  setAppSaveLabel(true);
  setAppFormStatus(
    "Modification de « " + app.id + " ».\n"
      + "Où : formulaire au-dessus.\n"
      + "À faire : change le nom d’hôte / Git / port, puis « Enregistrer les modifications ». Ensuite « Mettre à jour » pour appliquer sur le VPS.",
    "ok"
  );
  setStatus("Modification « " + app.id + " » — formulaire Applications rempli.", "ok");
  const card = $("appsCard");
  if (card && card.scrollIntoView) {
    card.scrollIntoView({ behavior: "smooth", block: "nearest" });
  }
  if ($("appHostName")) $("appHostName").focus();
}

async function deleteApp(app) {
  const ok = window.confirm(
    "Supprimer « " + app.id + " » de la liste locale ?\n\n"
      + "Cela n’arrête PAS le conteneur sur le VPS.\n"
      + "Hostname actuel : " + app.hostname
  );
  if (!ok) {
    setStatus("Suppression annulée.", "ok");
    return;
  }
  try {
    const r = await fetch("/api/v1/apps?id=" + encodeURIComponent(app.id), { method: "DELETE" });
    const j = await r.json();
    if (!r.ok) throw new Error(j.error || r.statusText);
    renderAppList(j.apps || []);
    if ($("appId") && $("appId").value.trim().toLowerCase() === app.id) {
      clearAppForm();
    }
    const msg = "« " + app.id + " » retiré de la liste locale. Conteneur VPS inchangé.";
    setAppFormStatus(msg, "ok");
    setStatus(msg, "ok");
  } catch (e) {
    setAppFormStatus(e.message, "err");
    pointAppFormError();
  }
}

function setAppFormStatus(text, kind) {
  const el = $("appFormStatus");
  if (!el) return;
  el.hidden = !text;
  el.textContent = text || "";
  el.classList.remove("ok", "err");
  if (kind) el.classList.add(kind);
}

/** Pointe vers le formulaire apps : n’écrase pas la barre Analyser avec le détail. */
function pointAppFormError() {
  setStatus("Erreur formulaire Applications (à droite) — détail sous « Enregistrer ». Pas lié à Analyser.", "err");
}

function explainIdLooksLikeHost(rawId) {
  const host = rawId.trim().toLowerCase();
  const slug = host.split(".")[0].replace(/_/g, "-").replace(/[^a-z0-9-]/g, "") || "my-app";
  if (!$("appHostName").value.trim()) {
    $("appHostName").value = host;
  }
  $("appId").value = slug;
  setAppFormStatus(
    "Cause : tu as collé un nom d’hôte DNS (« " + host + " ») dans Identifiant.\n"
      + "Où : champ Identifiant (slug) vs champ Nom d’hôte (DNS complet).\n"
      + "À faire : Identifiant = « " + slug + " » ; Nom d’hôte = « " + host
      + " ». Les champs ont été pré-corrigés — vérifie puis reclique Enregistrer.",
    "err"
  );
  pointAppFormError();
  $("appId").focus();
}

async function saveApp() {
  setAppFormStatus("", null);
  let rawId = $("appId").value.trim();
  if (!rawId) {
    setAppFormStatus(
      "Cause : aucun identifiant saisi.\n"
        + "Où : champ Identifiant — bande Applications (pas Analyser).\n"
        + "À faire : ex. my-bourse (les _ deviennent des tirets). Pas de domaine ici.",
      "err"
    );
    pointAppFormError();
    $("appId").focus();
    return;
  }
  if (rawId.indexOf(".") >= 0) {
    explainIdLooksLikeHost(rawId);
    return;
  }
  let gitUrl = normalizeGitUrl($("appGit").value);
  let hostname = $("appHostName").value.trim();
  if (looksLikeGitUrl(hostname)) {
    if (!gitUrl) {
      gitUrl = normalizeGitUrl(hostname);
      $("appGit").value = gitUrl;
    }
    hostname = "";
    $("appHostName").value = "";
  }
  const normalizedId = rawId.toLowerCase().replace(/_/g, "-");
  if ($("appId")) $("appId").value = normalizedId;
  if (!hostname) {
    hostname = suggestedAppHost(normalizedId);
    if (hostname) {
      $("appHostName").value = hostname;
    }
  }
  if ($("appGit")) $("appGit").value = gitUrl;
  const spec = {
    id: normalizedId,
    gitUrl: gitUrl,
    gitRef: $("appRef").value.trim() || "main",
    hostname: hostname,
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
    setAppSaveLabel(false);
    const msg = "App « " + spec.id + " » enregistrée (hôte " + spec.hostname
      + "). Clique Déployer / Mettre à jour sur sa ligne.";
    setAppFormStatus(msg, "ok");
    setStatus(msg, "ok");
  } catch (e) {
    setAppFormStatus(e.message, "err");
    pointAppFormError();
    $("appId").focus();
  }
}

async function deployApp(app) {
  const t = target();
  if (!t.host || !t.user) {
    setStatus("Indique IP et utilisateur SSH.", "err");
    return;
  }
  let plan;
  try {
    const r = await fetch("/api/v1/apps/deploy-plan?id=" + encodeURIComponent(app.id));
    plan = await r.json();
    if (!r.ok) throw new Error(plan.error || r.statusText);
  } catch (e) {
    setStatus("Plan de déploiement : " + e.message, "err");
    return;
  }
  const accepted = await showConfirmPlan({
    kicker: "CHANGE · confirmation",
    title: "Déployer « " + app.id + " »",
    detail: "Cible " + t.user + "@" + t.host + ". Rien ne part tant que tu n’as pas confirmé.",
    actions: plan.actions || [],
    warnings: plan.warnings || [],
    canProceed: plan.canProceed !== false,
    blocker: plan.blocker || "",
  });
  if (!accepted) {
    setStatus("Déploiement annulé — aucune modification.", "ok");
    return;
  }
  const steps = (plan.actions || []).slice(0, 4);
  showBusy({
    risk: "CHANGE",
    title: "Déploiement — " + app.id,
    detail: "Exécution du plan confirmé sur " + t.user + "@" + t.host
      + ". Jusqu’à 10 minutes (build). Ne ferme pas cette page.",
    steps: steps.length ? steps : ["Cloner le dépôt", "Rattrapage si besoin", "Build + Traefik"],
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
        allowScaffold: true,
      }),
    });
    const j = await r.json();
    if (!r.ok) throw new Error(j.error || r.statusText);
    paint(j.snapshot, j.report, "ssh");
    const elapsed = formatElapsed(Date.now() - busyStartedAt);
    hideBusy();
    setStatus(app.id + " : déployé. Nouvelle analyse affichée.", "ok");
    const publicUrl = "https://" + app.hostname + "/";
    showResult({
      ok: true,
      kicker: "CHANGE · réussi",
      title: app.id + " déployé",
      detail: "Plan exécuté en " + elapsed + ". Lien public HTTPS ci-dessous (HTTP redirigé si Traefik HTTPS est actif).",
      link: publicUrl,
      next: plan.scaffold
        ? "Un Dockerfile template (« " + plan.scaffold.kind + " ») a pu être injecté sur le VPS — recopie-le dans Git quand tu peux."
        : "Pour un nom court : renseigne un domaine de base (ex. apps.mondomaine.com) + DNS wildcard.",
    });
  } catch (e) {
    hideBusy();
    setStatus("Déploiement « " + app.id + " » échoué — vois la fenêtre (Cause / Où / À faire).", "err");
    showResult({
      ok: false,
      kicker: "CHANGE · échec",
      title: app.id + " n’a pas abouti",
      detail: e.message,
      next: "Lis Cause / Où / À faire. Si « Failed to compile » / ESLint : le code du dépôt bloque le build (ou relance après maj template Next).",
    });
  }
}

let platEditingId = "";

function platNeedChecked(svc, key) {
  const n = (svc && svc.needs) || [];
  if (key === "uploads") return n.includes("volume:uploads") || n.includes("uploads");
  return n.includes(key);
}

function platRoutingMode() {
  return ($("platRouting") && $("platRouting").value === "path") ? "path" : "host";
}

function syncPlatRoutingHint() {
  const hint = $("platHostHint");
  if (!hint) return;
  if (platRoutingMode() === "path") {
    hint.innerHTML = "Un seul DNS pour le produit (<code>plateforme.domaine</code>). Chaque service a un chemin : <code>/</code>, <code>/api</code>, <code>/admin</code>. Vide = auto.";
  } else {
    hint.innerHTML = "Hôte d’un service si le champ est vide : <code>id.plateforme.domaine</code> — ou sslip si tu as analysé une IP.";
  }
}

function platPublicUrl(p, s) {
  if (!s || !s.hostname) return "";
  if (p && p.routing === "path" && s.pathPrefix && s.pathPrefix !== "/") {
    return "https://" + s.hostname + s.pathPrefix + "/";
  }
  return "https://" + s.hostname + "/";
}

function addPlatServiceRow(svc) {
  const box = $("platServices");
  if (!box) return;
  const s = svc || {};
  const wrap = document.createElement("div");
  wrap.className = "plat-svc";
  const tpl = s.template || "";
  const tplOpt = (v, label) => '<option value="' + v + '"' + (tpl === v ? " selected" : "") + ">" + label + "</option>";
  wrap.innerHTML = '<div class="plat-svc-head"><strong>Service</strong>'
    + '<button type="button" class="ghost ghost-danger plat-svc-del">Retirer</button></div>'
    + '<div class="row2 app-fields plat-idport">'
    + '<label class="field">Identifiant<input class="plat-svc-id" type="text" value="' + esc(s.id || "") + '" placeholder="api" autocomplete="off" /></label>'
    + '<label class="field">Port<input class="plat-svc-port" type="number" value="' + (s.port || 8080) + '" min="1" max="65535" /></label>'
    + "</div>"
    + '<label class="field">Git (HTTPS ou git@github.com:…)<input class="plat-svc-git" type="text" value="' + esc(s.gitUrl || "") + '" placeholder="https://github.com/org/api.git" autocomplete="off" /></label>'
    + '<div class="row2 app-fields">'
    + '<label class="field">Branche<input class="plat-svc-ref" type="text" value="' + esc(s.gitRef || "main") + '" autocomplete="off" /></label>'
    + '<label class="field">Sous-dossier (optionnel)<input class="plat-svc-ctx" type="text" value="' + esc(s.context || "") + '" placeholder="backend/api" autocomplete="off" /></label>'
    + "</div>"
    + '<div class="row2 app-fields">'
    + '<label class="field">Nom d’hôte (vide = auto)<input class="plat-svc-host" type="text" value="' + esc(s.hostname || "") + '" placeholder="api.ma-asso.apps.exemple.com" autocomplete="off" /></label>'
    + '<label class="field">Chemin URL (routage chemins)<input class="plat-svc-path" type="text" value="' + esc(s.pathPrefix || "") + '" placeholder="/api" autocomplete="off" /></label>'
    + "</div>"
    + '<div class="row2 app-fields">'
    + '<label class="field">Dockerfile<select class="plat-svc-tpl">'
    + tplOpt("", "Celui du dépôt")
    + tplOpt("next", "Modèle Next.js")
    + tplOpt("node", "Modèle Node")
    + tplOpt("spring-boot-11", "Modèle Spring Boot (Java 11)")
    + tplOpt("spring-boot-17", "Modèle Spring Boot (Java 17)")
    + tplOpt("spring-boot-21", "Modèle Spring Boot (Java 21)")
    + "</select></label>"
    + '<label class="field plat-keep">&nbsp;<span><input type="checkbox" class="plat-svc-keep"' + (s.keepPath ? " checked" : "") + " /> Garder le préfixe (basePath Next, context-path Spring)</span></label>"
    + "</div>"
    + '<label class="field">Variables de build (une par ligne, CLE=valeur — <code>{{HOST}}</code> = nom d’hôte)<textarea class="plat-svc-build" rows="2" placeholder="NEXT_PUBLIC_API_URL=https://{{HOST}}/api">' + esc(kvLines(s.buildArgs)) + "</textarea></label>"
    + '<label class="field">Variables d’exécution (CLE=valeur ; secrets masqués ********, laisse-les tels quels pour les garder)<textarea class="plat-svc-env" rows="3" placeholder="SPRING_PROFILES_ACTIVE=staging">' + esc(kvLines(s.env)) + "</textarea></label>"
    + '<div class="plat-needs">'
    + '<label><input type="checkbox" class="plat-need-mariadb"' + (platNeedChecked(s, "mariadb") ? " checked" : "") + " /> MariaDB</label>"
    + '<label><input type="checkbox" class="plat-need-redis"' + (platNeedChecked(s, "redis") ? " checked" : "") + " /> Redis partagé</label>"
    + '<label><input type="checkbox" class="plat-need-redis-private"' + (platNeedChecked(s, "redis-private") ? " checked" : "") + " /> Redis privé (sans mot de passe)</label>"
    + '<label><input type="checkbox" class="plat-need-uploads"' + (platNeedChecked(s, "uploads") ? " checked" : "") + " /> Volume fichiers</label>"
    + "</div>";
  wrap.querySelector(".plat-svc-del").addEventListener("click", () => wrap.remove());
  box.appendChild(wrap);
}

function collectPlatServices() {
  return Array.from(document.querySelectorAll("#platServices .plat-svc")).map((el) => {
    const needs = [];
    if (el.querySelector(".plat-need-mariadb").checked) needs.push("mariadb");
    if (el.querySelector(".plat-need-redis").checked) needs.push("redis");
    if (el.querySelector(".plat-need-redis-private").checked) needs.push("redis-private");
    if (el.querySelector(".plat-need-uploads").checked) needs.push("volume:uploads");
    const portRaw = el.querySelector(".plat-svc-port").value;
    return {
      id: el.querySelector(".plat-svc-id").value.trim(),
      gitUrl: el.querySelector(".plat-svc-git").value.trim(),
      gitRef: el.querySelector(".plat-svc-ref").value.trim() || "main",
      hostname: el.querySelector(".plat-svc-host").value.trim(),
      pathPrefix: el.querySelector(".plat-svc-path") ? el.querySelector(".plat-svc-path").value.trim() : "",
      port: portRaw ? Number(portRaw) : 8080,
      context: el.querySelector(".plat-svc-ctx").value.trim(),
      needs,
      buildArgs: parseKvLines(el.querySelector(".plat-svc-build").value),
      env: parseKvLines(el.querySelector(".plat-svc-env").value),
      template: el.querySelector(".plat-svc-tpl").value,
      keepPath: el.querySelector(".plat-svc-keep").checked,
    };
  });
}

function kvLines(map) {
  if (!map) return "";
  return Object.keys(map).sort().map((k) => k + "=" + map[k]).join("\n");
}

function parseKvLines(text) {
  const out = {};
  String(text || "").split(/\r?\n/).forEach((line) => {
    const t = line.trim();
    if (!t || t.startsWith("#")) return;
    const i = t.indexOf("=");
    if (i <= 0) return;
    out[t.slice(0, i).trim()] = t.slice(i + 1).trim();
  });
  return out;
}

function setPlatFormStatus(msg, kind) {
  const el = $("platFormStatus");
  if (!el) return;
  if (!msg) {
    el.hidden = true;
    el.textContent = "";
    return;
  }
  el.hidden = false;
  el.textContent = msg;
  el.className = "app-form-status" + (kind ? " " + kind : "");
}

function clearPlatForm() {
  platEditingId = "";
  if ($("platId")) $("platId").value = "";
  if ($("platRouting")) $("platRouting").value = "host";
  if ($("platBase") && $("publicBaseDomain")) $("platBase").value = $("publicBaseDomain").value.trim();
  const box = $("platServices");
  if (box) box.innerHTML = "";
  addPlatServiceRow({ id: "api", port: 8080, needs: ["mariadb"] });
  addPlatServiceRow({ id: "www", port: 3000, needs: [] });
  setPlatFormStatus("");
  syncPlatRoutingHint();
}

function fillPlatForm(p) {
  platEditingId = p.id;
  $("platId").value = p.id || "";
  $("platBase").value = p.baseDomain || "";
  if ($("platRouting")) $("platRouting").value = p.routing === "path" ? "path" : "host";
  const box = $("platServices");
  box.innerHTML = "";
  (p.services || []).forEach((s) => addPlatServiceRow(s));
  if (!box.children.length) addPlatServiceRow(null);
  syncPlatRoutingHint();
}

const ARC_PRESET = {
  id: "arc",
  routing: "path",
  services: [
    {
      id: "www", port: 3000, gitUrl: "git@github.com:nebdev07/arc-client.git", gitRef: "main",
      pathPrefix: "/", template: "next", needs: [],
      buildArgs: { NEXT_PUBLIC_API_URL: "https://{{HOST}}/arc-api" },
    },
    {
      id: "backoffice", port: 3000, gitUrl: "git@github.com:nebdev07/arc-backoffice.git", gitRef: "main",
      pathPrefix: "/arc-backoffice", template: "next", keepPath: true, needs: [],
      buildArgs: {
        NEXT_PUBLIC_BASE_PATH: "/arc-backoffice",
        NEXT_PUBLIC_API_URL: "https://{{HOST}}/arc-api",
        NEXT_PUBLIC_APP_URL: "https://{{HOST}}/arc-backoffice",
        NEXT_PUBLIC_CLIENT_URL: "https://{{HOST}}",
      },
    },
    {
      id: "api", port: 8080, gitUrl: "git@github.com:nebdev07/arc-backend.git", gitRef: "main", context: "src",
      pathPrefix: "/arc-api", template: "spring-boot-11", keepPath: true,
      needs: ["mariadb", "redis-private", "volume:uploads"],
      env: {
        SPRING_PROFILES_ACTIVE: "staging",
        SERVER_SERVLET_CONTEXT_PATH: "/arc-api",
        APP_PUBLIC_BASE_URL: "https://{{HOST}}/arc-api",
        APP_FILE_UPLOAD_BASE_PATH: "/app/data/nwanyo",
        FILE_UPLOAD_DIRECTORY: "/app/data",
        FILE_UPLOAD_DIR: "/app/data",
        SPRING_WEB_RESOURCES_STATIC_LOCATIONS: "file:/app/data/nwanyo/",
        APP_MEMBER_LOGIN_URL: "https://{{HOST}}/connexion",
        APP_LOGIN_URL: "https://{{HOST}}/arc-backoffice",
        LOGS_DIRECTORY: "/app/data/logs",
        REDIS_HOST: "platform-cache",
      },
    },
  ],
};

function fillArcPreset() {
  const box = $("platServices");
  if (box && box.querySelector(".plat-svc-git") && Array.from(box.querySelectorAll(".plat-svc-git")).some((i) => i.value.trim())
    && !window.confirm("Remplacer la fiche en cours par le modèle ARC ?")) {
    return;
  }
  platEditingId = "";
  $("platId").value = ARC_PRESET.id;
  if ($("platBase")) $("platBase").value = "";
  if ($("platRouting")) $("platRouting").value = ARC_PRESET.routing;
  if (box) box.innerHTML = "";
  ARC_PRESET.services.forEach((s) => addPlatServiceRow(s));
  syncPlatRoutingHint();
  setPlatFormStatus("Modèle ARC chargé : vérifie les dépôts et le domaine, puis Enregistrer. "
    + "Ensuite une clé Git par dépôt (liste ci-dessous) et la restauration des données avant d’ouvrir le site.", "ok");
}

async function loadRepoKeys() {
  const box = $("gitRepoKeys");
  if (!box) return;
  try {
    const r = await fetch("/api/v1/git-deploy-key/repos");
    const j = await r.json();
    if (!r.ok) throw new Error(j.error || r.statusText);
    renderRepoKeys(j);
  } catch {
    box.innerHTML = "<p class=\"hint\">Liste des clés Git injoignable.</p>";
  }
}

function renderRepoKeys(j) {
  const box = $("gitRepoKeys");
  if (!box) return;
  const repos = j.repos || [];
  journey.repos = repos;
  renderJourney();
  if (!repos.length) {
    box.innerHTML = "<p class=\"hint\">" + esc(j.note || "Aucun dépôt git@… enregistré.") + "</p>";
    return;
  }
  box.innerHTML = "";
  repos.forEach((k) => {
    const row = document.createElement("div");
    row.className = "action-row " + (k.present ? "READY" : "BLOCKED");
    const info = document.createElement("div");
    const state = k.present ? "clé prête" : (k.fallback ? "clé commune (repli)" : "pas de clé");
    info.innerHTML = "<span class=\"tag\">" + esc(state) + "</span><strong>" + esc(k.repo) + "</strong>"
      + "<p>" + esc((k.usedBy || []).join(" · ")) + (k.fingerprint ? " — " + esc(k.fingerprint) : "") + "</p>"
      + (k.publicKey ? "<textarea class=\"git-key-pub\" readonly rows=\"2\">" + esc(k.publicKey) + "</textarea>" : "")
      + (k.deployKeyPage ? "<p class=\"hint\"><a href=\"" + esc(k.deployKeyPage) + "\" target=\"_blank\" rel=\"noopener\">Ouvrir la page Deploy keys du dépôt</a> — titre libre, <em>Allow write access</em> décoché.</p>" : "");
    row.appendChild(info);
    const actions = document.createElement("div");
    actions.className = "row-actions";
    const btnGen = document.createElement("button");
    btnGen.type = "button";
    btnGen.className = "ghost";
    btnGen.textContent = k.present ? "Régénérer" : "Générer";
    btnGen.addEventListener("click", () => generateRepoKey(k.repo, !!k.present));
    actions.appendChild(btnGen);
    if (k.publicKey) {
      const btnCopy = document.createElement("button");
      btnCopy.type = "button";
      btnCopy.className = "ghost";
      btnCopy.textContent = "Copier";
      btnCopy.addEventListener("click", async () => {
        try {
          await navigator.clipboard.writeText(k.publicKey);
          setStatus("Clé publique de " + k.repo + " copiée.", "ok");
        } catch {
          setStatus("Copie impossible — sélectionne le champ et Ctrl+C.", "err");
        }
      });
      actions.appendChild(btnCopy);
      const btnCheck = document.createElement("button");
      btnCheck.type = "button";
      btnCheck.className = "ghost";
      btnCheck.textContent = gitVerified(k.repo) ? "Accès confirmé" : "Vérifier";
      btnCheck.addEventListener("click", async () => {
        try {
          const v = await verifyRepo(k.repo);
          setStatus(k.repo + " : " + v.message, v.ok ? "ok" : "err");
          btnCheck.textContent = v.ok ? "Accès confirmé" : "Vérifier";
        } catch (e) {
          setStatus(e.message, "err");
        }
        renderJourney();
      });
      actions.appendChild(btnCheck);
    }
    row.appendChild(actions);
    box.appendChild(row);
  });
}

async function generateRepoKey(repo, rotate) {
  if (rotate && !window.confirm("Remplacer la clé de " + repo + " ? L’ancienne Deploy key collée sur le dépôt ne marchera plus.")) {
    return;
  }
  try {
    const r = await fetch("/api/v1/git-deploy-key/repos", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ repo, confirm: true, rotate }),
    });
    const j = await r.json();
    if (!r.ok) throw new Error(j.error || r.statusText);
    renderRepoKeys(j);
    setStatus("Clé prête pour " + repo + ". Copie-la en Deploy key (lecture seule) sur ce dépôt.", "ok");
  } catch (e) {
    setStatus(e.message, "err");
  }
}

async function loadPlatforms() {
  loadRepoKeys();
  const box = $("platList");
  if (!box) return;
  try {
    const r = await fetch("/api/v1/platforms");
    const j = await r.json();
    if (!r.ok) throw new Error(j.error || r.statusText);
    renderPlatList(j.platforms || []);
    if ($("platBase") && !$("platBase").value.trim() && $("publicBaseDomain")) {
      $("platBase").value = $("publicBaseDomain").value.trim();
    }
  } catch {
    box.innerHTML = "<p class=\"hint\">Liste de plateformes injoignable.</p>";
  }
}

function fillRestorePlatforms(platforms) {
  const sel = $("restorePlat");
  if (!sel) return;
  const prev = sel.value;
  sel.innerHTML = "";
  platforms.forEach((p) => {
    const o = document.createElement("option");
    o.value = p.id;
    o.textContent = p.id;
    sel.appendChild(o);
  });
  if (prev && platforms.some((p) => p.id === prev)) sel.value = prev;
  if (!$("restoreSubdir").value && sel.value === "arc") $("restoreSubdir").value = "nwanyo";
}

async function restorePlatform() {
  const t = target();
  const st = $("restoreStatus");
  const say = (msg, kind) => {
    st.hidden = !msg;
    st.textContent = msg || "";
    st.className = "app-form-status" + (kind ? " " + kind : "");
  };
  const id = $("restorePlat") ? $("restorePlat").value : "";
  const sql = $("restoreSql").files[0];
  const up = $("restoreUploads").files[0];
  if (!t.host || !t.user) {
    say("Cause : IP ou utilisateur SSH vide.\nOù : en haut de la console.\nÀ faire : remplis IP + utilisateur, Analyser, puis Restaurer.", "err");
    return;
  }
  if (!id) {
    say("Cause : aucune plateforme enregistrée.\nOù : liste Plateformes.\nÀ faire : Enregistrer la plateforme d’abord.", "err");
    return;
  }
  if (!sql && !up) {
    say("Cause : aucun fichier choisi.\nÀ faire : choisis un dump SQL et/ou une archive des fichiers.", "err");
    return;
  }
  const what = [sql ? "la base (" + sql.name + ")" : "", up ? "les fichiers (" + up.name + ")" : ""].filter(Boolean).join(" et ");
  if (!window.confirm("CHANGE sur " + t.user + "@" + t.host + " :\nremplacer " + what + " de « " + id + " ».\n\n"
    + "L’existant est sauvegardé sur le VPS (dossier backups de la plateforme) avant remplacement. Continuer ?")) {
    return;
  }
  const fd = new FormData();
  fd.append("id", id);
  fd.append("host", t.host);
  fd.append("port", String(t.port));
  fd.append("user", t.user);
  fd.append("confirm", "true");
  fd.append("uploadsSubdir", $("restoreSubdir").value.trim());
  if (sql) fd.append("sql", sql);
  if (up) fd.append("uploads", up);
  say("");
  showBusy({
    risk: "CHANGE",
    title: "Restauration de « " + id + " »",
    detail: "Envoi SFTP puis import sur le VPS. Ne ferme pas la page.",
    steps: ["Envoi des fichiers", "Sauvegarde de l’existant", "Import", "Redémarrage des services"],
    currentStep: 0,
  });
  try {
    const r = await fetch("/api/v1/platforms/restore", { method: "POST", body: fd });
    const j = await r.json();
    if (!r.ok) throw new Error(j.error || r.statusText);
    hideBusy();
    const report = (j.report || []).join("\n");
    say("Restauré.\n" + report, "ok");
    localStorage.setItem("vpsap.data." + t.host + "." + id, "done");
    journey.viewing = "";
    renderJourney();
    showResult({
      ok: true,
      kicker: "CHANGE · réussi",
      title: "Données de " + id + " restaurées",
      detail: report,
      next: "Si la plateforme n’est pas encore déployée : Déployer. Sinon, recharge le site.",
    });
  } catch (e) {
    hideBusy();
    say(e.message, "err");
    showResult({ ok: false, kicker: "CHANGE · échec", title: "Restauration interrompue", detail: e.message,
      next: "Lis Cause / Où / À faire. La base précédente reste sauvegardée sur le VPS." });
  }
}

function renderPlatList(platforms, deployReady) {
  fillRestorePlatforms(platforms);
  journey.platforms = platforms;
  renderJourney();
  const box = $("platList");
  if (!box) return;
  if (!platforms.length) {
    box.innerHTML = "<p class=\"hint\">Aucune plateforme enregistrée. Ajoute des services puis Enregistrer.</p>";
    return;
  }
  box.innerHTML = "";
  platforms.forEach((p) => {
    const row = document.createElement("div");
    row.className = "action-row READY";
    const svcTxt = (p.services || []).map((s) => s.id + " → " + platPublicUrl(p, s)).join(" · ");
    const info = document.createElement("div");
    const routeLbl = p.routing === "path" ? "chemins" : "sous-domaines";
    info.innerHTML = "<span class=\"tag\">déclarée</span><strong>" + esc(p.id) + "</strong><p>"
      + (p.services || []).length + " service(s) · " + routeLbl + " · " + esc(svcTxt) + "</p>";
    row.appendChild(info);
    const actions = document.createElement("div");
    actions.className = "row-actions";
    const btnEdit = document.createElement("button");
    btnEdit.type = "button";
    btnEdit.className = "ghost";
    btnEdit.textContent = "Modifier";
    btnEdit.addEventListener("click", () => fillPlatForm(p));
    const btnDel = document.createElement("button");
    btnDel.type = "button";
    btnDel.className = "ghost ghost-danger";
    btnDel.textContent = "Supprimer";
    btnDel.addEventListener("click", () => deletePlatform(p));
    const btnDeploy = document.createElement("button");
    btnDeploy.type = "button";
    btnDeploy.className = "change";
    btnDeploy.textContent = "Déployer";
    btnDeploy.addEventListener("click", () => deployPlatform(p));
    actions.appendChild(btnEdit);
    actions.appendChild(btnDel);
    actions.appendChild(btnDeploy);
    row.appendChild(actions);
    box.appendChild(row);
  });
}

async function deployPlatform(p) {
  const t = target();
  if (!t.host || !t.user) {
    setStatus("Indique IP et utilisateur SSH.", "err");
    return;
  }
  let plan;
  try {
    const r = await fetch("/api/v1/platforms/deploy-plan?id=" + encodeURIComponent(p.id));
    plan = await r.json();
    if (!r.ok) throw new Error(plan.error || r.statusText);
  } catch (e) {
    setStatus("Plan plateforme : " + e.message, "err");
    return;
  }
  const accepted = await showConfirmPlan({
    kicker: "CHANGE · confirmation",
    title: "Déployer la plateforme « " + p.id + " »",
    detail: "Cible " + t.user + "@" + t.host + ". N services, aucun port public. Rien ne part sans confirmation.",
    actions: plan.actions || [],
    warnings: plan.warnings || [],
    canProceed: plan.canProceed !== false,
    blocker: plan.blocker || "",
  });
  if (!accepted) {
    setStatus("Déploiement plateforme annulé.", "ok");
    return;
  }
  const steps = (plan.actions || []).slice(0, 5);
  showBusy({
    risk: "CHANGE",
    title: "Plateforme — " + p.id,
    detail: "Clones + builds sur " + t.user + "@" + t.host + ". Jusqu’à 20 minutes. Ne ferme pas cette page.",
    steps: steps.length ? steps : ["Cloner", "Build", "Traefik"],
    currentStep: 0,
  });
  setStatus("Déploiement de la plateforme " + p.id + "…");
  try {
    const r = await fetch("/api/v1/platforms/deploy", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        id: p.id,
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
    const firstUrl = (p.services && p.services[0]) ? platPublicUrl(p, p.services[0]) : "";
    setStatus(p.id + " : plateforme déployée.", "ok");
    showResult({
      ok: true,
      kicker: "CHANGE · réussi",
      title: "Plateforme " + p.id + " déployée",
      detail: "Plan exécuté en " + elapsed + ". HTTPS "
        + (p.routing === "path" ? "par chemins (un DNS)." : "par sous-domaines."),
      link: firstUrl,
      next: "DNS wildcard vers le VPS si ce n’est pas du sslip. Redis/MariaDB doivent déjà être SKIP si tu les as cochés.",
    });
  } catch (e) {
    hideBusy();
    setStatus("Plateforme « " + p.id + " » échouée — vois la fenêtre.", "err");
    showResult({
      ok: false,
      kicker: "CHANGE · échec",
      title: p.id + " n’a pas abouti",
      detail: e.message,
      next: "Lis Cause / Où / À faire. Chaque service doit avoir un Dockerfile (racine ou sous-dossier).",
    });
  }
}

async function savePlatform() {
  setPlatFormStatus("");
  const services = collectPlatServices();
  if (!services.length) {
    setPlatFormStatus("Cause : aucun service.\nOù : bande Plateformes.\nÀ faire : Ajouter un service.", "err");
    return;
  }
  try {
    const cur = await fetch("/api/v1/platforms").then((r) => r.json());
    const list = (cur.platforms || []).slice();
    const spec = {
      id: $("platId").value.trim(),
      baseDomain: $("platBase").value.trim(),
      routing: platRoutingMode(),
      services,
    };
    const idx = list.findIndex((p) => p.id === (platEditingId || spec.id));
    if (idx >= 0) list[idx] = spec;
    else list.push(spec);
    const ip = $("sshHost") ? $("sshHost").value.trim() : "";
    const r = await fetch("/api/v1/platforms", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ platforms: list, vpsIp: ip }),
    });
    const j = await r.json();
    if (!r.ok) throw new Error(j.error || r.statusText);
    platEditingId = spec.id;
    localStorage.setItem("vpsap.plat", spec.id);
    journey.viewing = "";
    renderPlatList(j.platforms || []);
    loadRepoKeys();
    setPlatFormStatus(j.note || "Enregistré (local, pas de SSH).", "ok");
    setStatus("Plateforme enregistrée. Tu peux Déployer (CHANGE confirmé).", "ok");
  } catch (e) {
    setPlatFormStatus(e.message, "err");
    setStatus(e.message, "err");
  }
}

async function deletePlatform(p) {
  if (!window.confirm("Retirer « " + p.id + " » de la liste locale ? Rien ne sera arrêté sur un VPS.")) {
    return;
  }
  try {
    const r = await fetch("/api/v1/platforms?id=" + encodeURIComponent(p.id), { method: "DELETE" });
    const j = await r.json();
    if (!r.ok) throw new Error(j.error || r.statusText);
    renderPlatList(j.platforms || []);
    if (platEditingId === p.id) clearPlatForm();
    setStatus("Plateforme « " + p.id + " » retirée de la liste locale.", "ok");
  } catch (e) {
    setStatus(e.message, "err");
  }
}

/* ---------- Parcours guidé : une étape à la fois (le mode expert garde le tableau complet) ---------- */

const PREP_IDS = ["security", "docker", "traefik", "mariadb", "deploy"];
const PREP_LABELS = {
  security: "Sécurité : pare-feu, anti-intrusion, connexion SSH par clé uniquement",
  docker: "Docker : le moteur qui fait tourner tes applications",
  traefik: "Traefik : HTTPS automatique et routage vers tes applications",
  mariadb: "MariaDB : la base de données (utilisée si ton appli en a besoin)",
  deploy: "Compte deploy : un utilisateur sans droits admin qui fait tourner les applis",
};
const JOURNEY_STEPS = [
  { id: "analyse", label: "Serveur analysé" },
  { id: "prepare", label: "Serveur préparé" },
  { id: "platform", label: "Plateforme décrite" },
  { id: "git", label: "Accès aux dépôts" },
  { id: "data", label: "Données" },
  { id: "online", label: "En ligne" },
];

const journey = {
  snapshot: null,
  plan: null,
  platforms: [],
  repos: [],
  status: null,
  statusFor: "",
  statusPending: false,
  viewing: "",
};

function guided() {
  return document.body.classList.contains("guided");
}

function setGuided(on) {
  document.body.classList.toggle("guided", on);
  localStorage.setItem("vpsap.expert", on ? "0" : "1");
  if ($("btnExpert")) $("btnExpert").textContent = on ? "Mode expert" : "Parcours guidé";
  renderJourney();
}

function journeyPlatform() {
  const saved = localStorage.getItem("vpsap.plat");
  return journey.platforms.find((p) => p.id === saved) || journey.platforms[0] || null;
}

function journeyRepos(p) {
  if (!p) return [];
  return journey.repos.filter((r) => (r.usedBy || []).some((u) => u.startsWith(p.id + " / ")));
}

function gitVerified(repo) {
  return localStorage.getItem("vpsap.git." + repo) === "1";
}

function dataKey(p) {
  return "vpsap.data." + target().host + "." + (p ? p.id : "");
}

function platformHasData(p) {
  return (p.services || []).some((s) => (s.needs || []).some((n) => n === "mariadb" || n.startsWith("volume:")));
}

function prepMissing() {
  const actions = (journey.plan && journey.plan.actions) || [];
  return PREP_IDS.filter((id) => {
    const a = actions.find((x) => x.id === id);
    return !a || a.status !== "SKIP";
  });
}

function journeyDone() {
  const p = journeyPlatform();
  const repos = journeyRepos(p);
  return {
    analyse: !!journey.snapshot,
    prepare: !!journey.plan && prepMissing().length === 0,
    platform: !!p,
    git: !!p && repos.every((r) => gitVerified(r.repo)),
    data: !!p && (!platformHasData(p) || !!localStorage.getItem(dataKey(p))),
    online: !!p && !!journey.status && journey.statusFor === p.id && journey.status.online,
  };
}

function journeyCurrent(done) {
  const s = JOURNEY_STEPS.find((x) => !done[x.id]);
  return s ? s.id : "done";
}

function renderJourney() {
  if (!$("journey") || !journey.snapshot) return;
  const done = journeyDone();
  const current = journeyCurrent(done);
  const showing = journey.viewing || current;
  const plat = journeyPlatform();
  if (current === "online" && plat && journey.statusFor !== plat.id && !journey.statusPending) {
    journey.statusPending = true;
    refreshJourneyStatus(false).finally(() => { journey.statusPending = false; });
  }
  const snap = journey.snapshot;
  $("journeyServer").textContent = [
    snap.profileName, snap.host, snap.osPretty,
    snap.cpu && snap.cpu.vcpus ? snap.cpu.vcpus + " vCPU" : "",
    snap.memory && snap.memory.totalBytes ? humanBytes(snap.memory.totalBytes) + " RAM" : "",
  ].filter(Boolean).join(" · ");

  const ol = $("journeySteps");
  ol.innerHTML = "";
  JOURNEY_STEPS.forEach((s) => {
    const li = document.createElement("li");
    li.textContent = s.label;
    if (done[s.id]) li.classList.add("done");
    if (s.id === current) li.classList.add("current");
    if (s.id === showing && journey.viewing) li.classList.add("viewing");
    li.addEventListener("click", () => {
      journey.viewing = s.id === current ? "" : s.id;
      renderJourney();
    });
    ol.appendChild(li);
  });

  const workloads = document.querySelector(".band-workloads");
  const sectionFor = { platform: "platform", git: "git", data: "data" }[showing] || "";
  if (workloads) workloads.classList.toggle("journey-show", guided() && !!sectionFor);
  if ($("platformsCard")) {
    document.querySelectorAll("#platformsCard [data-jsec]").forEach((el) => {
      el.classList.toggle("journey-off", el.dataset.jsec !== sectionFor);
    });
  }
  renderJourneyCard(showing, current, done);
}

function journeyButton(label, onClick, kind) {
  const b = document.createElement("button");
  b.type = "button";
  if (kind) b.className = kind;
  b.textContent = label;
  b.addEventListener("click", onClick);
  $("journeyActions").appendChild(b);
  return b;
}

function journeyListItems(items) {
  const ul = $("journeyList");
  ul.innerHTML = "";
  ul.hidden = !items.length;
  items.forEach((it) => {
    const li = document.createElement("li");
    if (it.cls) li.className = it.cls;
    li.innerHTML = it.html;
    ul.appendChild(li);
  });
}

function renderJourneyCard(step, current, done) {
  const p = journeyPlatform();
  const idx = JOURNEY_STEPS.findIndex((s) => s.id === step);
  $("journeyKicker").textContent = step === "done"
    ? "Terminé"
    : "Étape " + (idx + 1) + " sur " + JOURNEY_STEPS.length + (step !== current ? " · déjà faite, tu peux la revoir" : "");
  $("journeyActions").innerHTML = "";
  $("journeyHint").hidden = true;
  journeyListItems([]);
  const hint = (text) => { $("journeyHint").hidden = false; $("journeyHint").innerHTML = text; };

  switch (step) {
    case "analyse":
      $("journeyTitle").textContent = "Analyser le serveur";
      $("journeyText").textContent = "Renseigne l’IP et l’utilisateur à gauche, puis clique Analyser le VPS. Rien n’est modifié.";
      break;
    case "prepare": {
      const missing = prepMissing();
      $("journeyTitle").textContent = missing.length ? "Préparer le serveur" : "Serveur prêt";
      $("journeyText").textContent = missing.length
        ? "L’agent installe en une fois ce qu’il faut pour héberger des applications. Tu confirmes une seule fois, puis tu laisses la page ouverte (5 à 15 minutes)."
        : "Tout est installé et sécurisé sur ce serveur.";
      journeyListItems(PREP_IDS.map((id) => ({
        html: esc(PREP_LABELS[id]),
        cls: missing.includes(id) ? "" : "ok",
      })));
      if (missing.length) journeyButton("Préparer le serveur", prepareServer, "change");
      break;
    }
    case "platform":
      $("journeyTitle").textContent = p ? "Plateforme « " + p.id + " »" : "Décrire ta plateforme";
      $("journeyText").textContent = p
        ? "Tu peux la modifier ci-dessous, puis Enregistrer. Rien n’est encore installé sur le serveur."
        : "Une plateforme = tes applications (site, back-office, API…), chacune avec son dépôt Git. Pars d’un modèle ou d’une fiche vide, puis Enregistrer.";
      if (!p) {
        journeyButton("Partir du modèle ARC", fillArcPreset, "ghost");
        journeyButton("Fiche vide", clearPlatForm, "ghost");
      }
      hint("Pas encore de nom de domaine ? Laisse les noms d’hôte vides : l’agent utilise une adresse automatique <code>…sslip.io</code>. Tu mettras ton domaine plus tard, puis Enregistrer et Déployer.");
      break;
    case "git": {
      const repos = journeyRepos(p);
      $("journeyTitle").textContent = repos.length ? "Donner accès à tes dépôts privés" : "Aucun dépôt privé";
      $("journeyText").textContent = repos.length
        ? "Pour chaque dépôt ci-dessous : Générer, Copier, ouvrir le lien, coller la clé sur GitHub (case « Allow write access » décochée). Puis Vérifier."
        : "Tes dépôts sont publics (HTTPS) : rien à faire ici.";
      journeyListItems(repos.map((r) => ({
        html: esc(r.repo) + (gitVerified(r.repo) ? " — accès confirmé" : r.present ? " — clé générée, à coller puis vérifier" : " — pas encore de clé"),
        cls: gitVerified(r.repo) ? "ok" : "",
      })));
      if (repos.length) journeyButton("Tout vérifier", verifyAllRepos, "");
      break;
    }
    case "data":
      $("journeyTitle").textContent = "Restaurer les données (si besoin)";
      $("journeyText").textContent = "Si ton application a besoin d’une base existante (c’est le cas d’ARC), envoie le dump SQL et l’archive des fichiers ci-dessous. Sinon, passe cette étape.";
      journeyButton("Pas de données à restaurer", () => {
        localStorage.setItem(dataKey(p), "skip");
        journey.viewing = "";
        renderJourney();
      }, "ghost");
      break;
    case "online":
      $("journeyTitle").textContent = "Mettre en ligne « " + (p ? p.id : "") + " »";
      $("journeyText").textContent = "L’agent récupère le code, construit les images et ouvre l’accès HTTPS. Le premier déploiement peut prendre jusqu’à 20 minutes.";
      renderStatusList();
      if (p) {
        journeyButton("Déployer", async () => {
          await deployPlatform(p);
          await refreshJourneyStatus(true);
        }, "change");
        journeyButton("Vérifier l’accès", () => refreshJourneyStatus(true), "ghost");
      }
      break;
    case "done":
      $("journeyTitle").textContent = "« " + p.id + " » est en ligne";
      $("journeyText").textContent = "Tes applications répondent en HTTPS. Pour mettre à jour après un changement de code ou de domaine : modifie la fiche si besoin, puis Déployer.";
      renderStatusList();
      journeyButton("Vérifier à nouveau", () => refreshJourneyStatus(true), "ghost");
      journeyButton("Redéployer", async () => {
        await deployPlatform(p);
        await refreshJourneyStatus(true);
      }, "ghost");
      break;
    default:
      throw new Error("Étape inconnue : " + step);
  }
  if (step !== current) {
    const back = document.createElement("button");
    back.type = "button";
    back.className = "linkish";
    back.textContent = "Revenir à l’étape en cours";
    back.addEventListener("click", () => { journey.viewing = ""; renderJourney(); });
    $("journeyActions").appendChild(back);
  }
}

function renderStatusList() {
  const st = journey.status;
  if (!st || !st.services) return;
  journeyListItems(st.services.map((s) => ({
    html: esc(s.service) + " — <a href=\"" + esc(s.url) + "\" target=\"_blank\" rel=\"noopener\">" + esc(s.url) + "</a>"
      + (s.online ? " (répond, HTTP " + s.httpCode + ")" : s.httpCode > 0 ? " (erreur HTTP " + s.httpCode + ")" : " (pas encore joignable)"),
    cls: s.online ? "ok" : "err",
  })));
}

async function refreshJourneyStatus(force) {
  const p = journeyPlatform();
  if (!p) return;
  if (!force && journey.statusFor === p.id) return;
  try {
    const r = await fetch("/api/v1/platforms/status?id=" + encodeURIComponent(p.id));
    const j = await r.json();
    if (!r.ok) throw new Error(j.error || r.statusText);
    journey.status = j;
    journey.statusFor = p.id;
  } catch (e) {
    journey.status = null;
    journey.statusFor = p.id;
    setStatus("Vérification de l’accès : " + e.message, "err");
  }
  renderJourney();
}

async function verifyRepo(repo) {
  const r = await fetch("/api/v1/git-deploy-key/repos/verify", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ repo }),
  });
  const j = await r.json();
  if (!r.ok) throw new Error(j.error || r.statusText);
  if (j.ok) localStorage.setItem("vpsap.git." + repo, "1");
  else localStorage.removeItem("vpsap.git." + repo);
  return j;
}

async function verifyAllRepos() {
  const repos = journeyRepos(journeyPlatform());
  const failures = [];
  for (const k of repos) {
    setStatus("Vérification de " + k.repo + "…");
    try {
      const v = await verifyRepo(k.repo);
      if (!v.ok) failures.push(k.repo + " : " + v.message);
    } catch (e) {
      failures.push(k.repo + " : " + e.message);
    }
  }
  setStatus(failures.length ? failures.join(" · ") : "Tous les dépôts acceptent leur clé.", failures.length ? "err" : "ok");
  renderJourney();
}

async function prepareServer() {
  const t = target();
  const missing = prepMissing();
  const accepted = await showConfirmPlan({
    kicker: "CHANGE · confirmation",
    title: "Préparer le serveur " + t.host,
    detail: "L’agent installe dans cet ordre, puis réanalyse le serveur. Chaque brique est un script versionné qui peut être relancé sans risque.",
    actions: missing.map((id) => PREP_LABELS[id]),
    warnings: missing.includes("security")
      ? ["Après la sécurité, la connexion SSH par mot de passe et la connexion root seront fermées. Garde ta clé SSH personnelle : ton compte admin reste autorisé."]
      : [],
    canProceed: true,
  });
  if (!accepted) return;
  const order = missing.slice();
  showBusy({
    risk: "CHANGE",
    title: "Préparation du serveur",
    detail: "Cible " + t.user + "@" + t.host + ". Ne ferme pas cette page.",
    steps: order.map((id) => PREP_LABELS[id].split(" :")[0]),
    currentStep: 0,
  });
  const startedAt = Date.now();
  try {
    for (let guard = 0; guard < 8; guard += 1) {
      const pr = await fetch("/api/v1/plan");
      journey.plan = await pr.json();
      const next = PREP_IDS.map((id) => journey.plan.actions.find((a) => a.id === id))
        .find((a) => a && a.status === "READY");
      if (!next) break;
      const i = order.indexOf(next.id);
      updateBusy({ currentStep: i < 0 ? 0 : i, steps: order.map((id) => PREP_LABELS[id].split(" :")[0]),
        detail: "En cours : " + PREP_LABELS[next.id].split(" :")[0] + ". Les images Docker peuvent prendre quelques minutes." });
      const r = await fetch("/api/v1/install", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ component: next.id, host: t.host, port: t.port, user: t.user, confirm: true }),
      });
      const j = await r.json();
      if (!r.ok) throw new Error(PREP_LABELS[next.id].split(" :")[0] + " : " + (j.error || r.statusText));
      paint(j.snapshot, j.report, "ssh");
    }
    const pr = await fetch("/api/v1/plan");
    journey.plan = await pr.json();
    renderPlan(journey.plan);
    hideBusy();
    const left = prepMissing();
    showResult({
      ok: left.length === 0,
      kicker: left.length ? "CHANGE · incomplet" : "CHANGE · réussi",
      title: left.length ? "Préparation incomplète" : "Serveur prêt",
      detail: left.length
        ? "Encore à faire : " + left.map((id) => PREP_LABELS[id].split(" :")[0]).join(", ") + ". Ouvre le mode expert pour voir pourquoi."
        : "Tout est installé en " + formatElapsed(Date.now() - startedAt) + ".",
      next: left.length ? "" : "Étape suivante : décrire ta plateforme.",
    });
  } catch (e) {
    hideBusy();
    showResult({ ok: false, kicker: "CHANGE · échec", title: "Préparation interrompue", detail: e.message,
      next: "Ce qui est déjà installé reste en place. Corrige, puis relance « Préparer le serveur »." });
  }
  renderJourney();
}

if ($("btnExpert")) $("btnExpert").addEventListener("click", () => setGuided(!guided()));
if ($("btnRailSetup")) {
  $("btnRailSetup").addEventListener("click", () => {
    const on = document.body.classList.toggle("show-setup");
    $("btnRailSetup").textContent = on ? "Masquer les options de connexion" : "Options de connexion";
  });
}
document.body.classList.toggle("guided", localStorage.getItem("vpsap.expert") !== "1");
if ($("btnExpert")) $("btnExpert").textContent = guided() ? "Mode expert" : "Parcours guidé";

showView(currentView());
refreshPwsh();
loadPublicDomain();
loadGitDeployKey();
loadPlatforms();
if ($("platServices") && !$("platServices").children.length) {
  addPlatServiceRow({ id: "api", port: 8080, needs: ["mariadb"] });
  addPlatServiceRow({ id: "www", port: 3000, needs: [] });
  syncPlatRoutingHint();
}
loadRuntime().then(loadStack).catch(() => {
  setStatus("API injoignable.", "err");
});
