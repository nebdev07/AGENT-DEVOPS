/**
 * Test unitaire (Node, sans navigateur) de journeyStepFlags.
 * Duplique la règle métier : current = étape affichée.
 * Exit 0 = OK.
 */
function journeyStepFlags(stepId, { showing, current, done, viewing }) {
  const isShowing = stepId === showing;
  return {
    done: !!done[stepId],
    current: isShowing,
    next: !isShowing && stepId === current && !!viewing,
  };
}

const STEPS = ["analyse", "prepare", "platform", "git", "data", "online"];
let failed = 0;

function assert(cond, msg) {
  if (!cond) {
    console.error("FAIL:", msg);
    failed++;
  } else {
    console.log("OK:", msg);
  }
}

// Cas image 1 : contenu Données, progression encore bloquée sur Git
{
  const done = { analyse: true, prepare: true, platform: true, git: false, data: true, online: false };
  const current = "git";
  const showing = "data";
  const viewing = "data";
  const flags = Object.fromEntries(
    STEPS.map((id) => [id, journeyStepFlags(id, { showing, current, done, viewing })])
  );
  assert(flags.data.current === true, "Données affichée → .current sur data");
  assert(flags.git.current === false, "Git non affiché → pas .current");
  assert(flags.git.next === true, "Git = prochain trou → .next discret");
  assert(flags.data.done === true, "Données déjà faite → .done");
}

// Cas image 2 : contenu En ligne
{
  const done = { analyse: true, prepare: true, platform: true, git: false, data: true, online: true };
  const current = "git";
  const showing = "online";
  const viewing = "online";
  const flags = Object.fromEntries(
    STEPS.map((id) => [id, journeyStepFlags(id, { showing, current, done, viewing })])
  );
  assert(flags.online.current === true, "En ligne affichée → .current sur online");
  assert(flags.git.current === false, "Git non affiché → pas .current (bug historique)");
  assert(flags.git.next === true, "Git reste .next");
}

// Parcours normal sans viewing : current === showing === git
{
  const done = { analyse: true, prepare: true, platform: true, git: false, data: false, online: false };
  const current = "git";
  const showing = "git";
  const viewing = "";
  const flags = journeyStepFlags("git", { showing, current, done, viewing });
  assert(flags.current === true && flags.next === false, "Sans viewing, git progress = current");
}

if (failed) {
  console.error(`\n${failed} assertion(s) en échec`);
  process.exit(1);
}
console.log("\nPASS test-journey-active");
