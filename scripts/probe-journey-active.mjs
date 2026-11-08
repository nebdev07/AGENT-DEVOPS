/**
 * Probe DOM authentifié : stepper .current = étape affichée.
 * node scripts/probe-journey-active.mjs
 */
import { chromium } from "playwright";

const BASE = process.env.VPSAP_BASE || "http://127.0.0.1:8088";
const EMAIL = process.env.PLATFORM_FOUNDER_EMAIL || "nebdev07@gmail.com";
const PASS = process.env.PLATFORM_DEFAULT_PASSWORD || "VpsAutopilot!ChangeMe";
const NEW_PASS = process.env.PLATFORM_PROBE_PASSWORD || "ProbeTest!ChangeMe42";

async function waitReady(page) {
  for (let i = 0; i < 60; i++) {
    try {
      const r = await page.request.get(BASE + "/api/v1/health");
      if (r.ok()) return;
    } catch (_) {}
    await page.waitForTimeout(1000);
  }
  throw new Error("Agent non prêt sur " + BASE);
}

async function ensureSession(page) {
  let login = await page.request.post(BASE + "/api/v1/platform/auth/login", {
    data: { email: EMAIL, password: PASS },
  });
  let body = await login.json().catch(() => ({}));
  if (!login.ok()) {
    // Mot de passe déjà changé : tente le mot de passe probe
    login = await page.request.post(BASE + "/api/v1/platform/auth/login", {
      data: { email: EMAIL, password: NEW_PASS },
    });
    body = await login.json().catch(() => ({}));
    if (!login.ok()) {
      throw new Error("Login impossible: " + (body.error || login.status()));
    }
  }
  if (body.mustChangePassword) {
    const ch = await page.request.post(BASE + "/api/v1/platform/auth/password", {
      data: { currentPassword: PASS, newPassword: NEW_PASS },
    });
    if (!ch.ok()) {
      const j = await ch.json().catch(() => ({}));
      throw new Error("Changement MDP: " + (j.error || ch.status()));
    }
  }
}

async function main() {
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  try {
    await waitReady(page);
    await ensureSession(page);
    await page.goto(BASE + "/index.html#cockpit", { waitUntil: "domcontentloaded", timeout: 30000 });
    // JOURNEY_STEPS est en `const` (pas sur window) — ne tester que renderJourney.
    await page.waitForFunction(() => typeof window.renderJourney === "function", { timeout: 20000 });

    const result = await page.evaluate(() => {
      journey.snapshot = {
        host: "213.156.134.92",
        osPretty: "Ubuntu 24.04",
        profileName: "lab",
        cpu: { vcpus: 4 },
        memory: { totalBytes: 8 * 1024 ** 3 },
      };
      journey.plan = {
        actions: ["security", "docker", "traefik", "mariadb", "deploy"].map((id) => ({
          id,
          status: "SKIP",
        })),
      };
      journey.platforms = [
        {
          id: "arc",
          routing: "path",
          services: [
            { id: "www", needs: [] },
            { id: "api", needs: ["mariadb"] },
          ],
        },
      ];
      journey.repos = [{ repo: "git@github.com:nebdev07/arc-client.git", usedBy: ["arc / www"] }];
      localStorage.setItem("vpsap.plat", "arc");
      localStorage.setItem("vpsap.data.213.156.134.92.arc", "1");
      localStorage.removeItem("vpsap.git.git@github.com:nebdev07/arc-client.git");
      journey.status = {
        online: true,
        services: [{ service: "www", online: true, url: "https://x/", httpCode: 200 }],
      };
      journey.statusFor = "arc";
      document.body.classList.add("guided", "connected");
      document.getElementById("board")?.classList.remove("hidden");
      document.getElementById("empty")?.classList.add("hidden");
      document.getElementById("journey")?.classList.remove("hidden");

      function snap(viewing) {
        journey.viewing = viewing;
        renderJourney();
        const items = [...document.querySelectorAll("#journeySteps li")].map((li) => ({
          id: li.dataset.step,
          label: li.textContent.trim(),
          current: li.classList.contains("current"),
          done: li.classList.contains("done"),
          next: li.classList.contains("next"),
          aria: li.getAttribute("aria-current"),
        }));
        return {
          items,
          kicker: document.getElementById("journeyKicker")?.textContent || "",
          title: document.getElementById("journeyTitle")?.textContent || "",
        };
      }

      const dataView = snap("data");
      const onlineView = snap("online");
      const progressView = snap("");

      const assertData =
        dataView.items.find((x) => x.id === "data")?.current === true &&
        dataView.items.find((x) => x.id === "git")?.current === false &&
        dataView.items.find((x) => x.id === "data")?.aria === "step" &&
        /ÉTAPE 5/i.test(dataView.kicker);

      const assertOnline =
        onlineView.items.find((x) => x.id === "online")?.current === true &&
        onlineView.items.find((x) => x.id === "git")?.current === false &&
        /ÉTAPE 6/i.test(onlineView.kicker);

      const assertGit = progressView.items.find((x) => x.id === "git")?.current === true;

      return {
        ok: !!(assertData && assertOnline && assertGit),
        assertData,
        assertOnline,
        assertGit,
        dataView,
        onlineView,
        progressView,
        hasFlagsFn: typeof journeyStepFlags === "function",
      };
    });

    await page.evaluate(() => {
      document.getElementById("tuto")?.classList.add("hidden");
      document.getElementById("busy")?.classList.add("hidden");
      document.getElementById("confirm")?.classList.add("hidden");
      journey.viewing = "data";
      renderJourney();
    });
    await page.screenshot({ path: "target/probe-journey-data.png" });
    await page.evaluate(() => {
      journey.viewing = "online";
      renderJourney();
    });
    await page.screenshot({ path: "target/probe-journey-online.png" });

    console.log(JSON.stringify(result, null, 2));
    if (!result.ok) {
      console.error("FAIL probe journey active");
      process.exit(1);
    }
    console.log("PASS probe-journey-active");
  } finally {
    await browser.close();
  }
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
