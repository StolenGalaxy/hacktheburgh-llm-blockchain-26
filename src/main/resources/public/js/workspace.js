// Fills workspace.html from /api/me and /api/projects/{id}.
// Forms on the page POST to the server, which redirects back here (with ?error=... if something was wrong).
// Everything user-supplied is inserted with textContent, never innerHTML.

const params = new URLSearchParams(location.search);
const $ = (selector) => document.querySelector(selector);

function el(tag, props = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(props)) {
    if (key === "class") node.className = value;
    else if (key === "text") node.textContent = value;
    else node.setAttribute(key, value);
  }
  node.append(...children.filter((child) => child != null));
  return node;
}

const avatar = (initials) => el("span", { class: "avatar", text: initials });
// Every amount shown on the page goes through one of these, so they all get the "$"
const money = (amount) => `$${Number(amount).toFixed(2)}`;
const shortAmount = (amount) => `$${Number(amount)}`;
// USDC has 6 decimals: show at least 2 and up to all 6, e.g. $24.691356 or $15.00
const usdc = (amount) => `$${Number(amount).toLocaleString("en-GB", { minimumFractionDigits: 2, maximumFractionDigits: 6 })}`;

async function getJson(url) {
  const response = await fetch(url, { headers: { Accept: "application/json" } });
  if (response.status === 401) {
    location.href = "/login.html";
    throw new Error("Not logged in");
  }
  if (!response.ok) throw new Error((await response.text()) || response.statusText);
  return response.json();
}

function showError(message) {
  $("#page-error").textContent = message || "";
}

function renderAccount(me) {
  $("#account-name").textContent = me.name;
  $("#account-avatar").textContent = me.initials;
  $("#topbar-avatar").textContent = me.initials;
  renderWallet(me.wallet);
}

// Money that isn't committed to a stake yet (stakes stay in the wallet until their contract is created)
const freeFunds = (wallet) => Math.max(0, Number(wallet.balance) - Number(wallet.committed));

function renderWallet(wallet) {
  const chip = $("#wallet-chip-balance");
  const error = $("#wallet-error");
  const addButton = $("#add-funds-button");
  const maxTopUp = Number(wallet.maxTopUp);
  $("#add-funds-amount").max = maxTopUp;
  $("#add-funds-hint").textContent = `Temporary, until real payments are set up: this adds test USDC to your wallet. Up to ${money(maxTopUp)} at a time.`;

  if (!wallet.available) {
    chip.textContent = "Unavailable";
    $("#wallet-balance").textContent = "—";
    $("#wallet-free").textContent = "";
    error.hidden = false;
    error.textContent = wallet.error;
    $("#wallet-details").replaceChildren();
    addButton.disabled = true;
    return;
  }
  error.hidden = true;
  addButton.disabled = false;
  chip.textContent = money(wallet.balance);
  $("#wallet-balance").textContent = usdc(wallet.balance);
  const committed = Number(wallet.committed);
  $("#wallet-free").textContent = committed > 0
    ? `${usdc(freeFunds(wallet))} free · ${usdc(committed)} committed to stakes in projects without a contract yet`
    : "USDC, all free to stake";
  const row = (label, value, props = {}) => [el("dt", { text: label }), el("dd", { ...props, text: value })];
  $("#wallet-details").replaceChildren(...row("Address", wallet.address, { class: "mono" }));
}

// From the stake dialog, when there isn't enough in the wallet
$("#stake-add-funds").addEventListener("click", () => {
  $("#stake").close();
  $("#wallet").showModal();
});

function renderProjectList(projects, activeId) {
  $("#project-list").replaceChildren(...projects.map((project) =>
    el("li", {}, el("a", {
      href: `/workspace.html?project=${project.id}`,
      class: project.id === activeId ? "active" : "",
      text: project.name,
    }))));
}

function renderInbox(invites) {
  const count = $("#inbox-count");
  count.textContent = invites.length;
  count.hidden = invites.length === 0;

  if (invites.length === 0) {
    $("#inbox-list").replaceChildren(el("p", { class: "muted", text: "No invitations." }));
    return;
  }
  $("#inbox-list").replaceChildren(...invites.map((invite) =>
    el("article", { class: "card stack" },
      el("h3", { text: invite.projectName }),
      el("p", { class: "muted small", text: `From ${invite.from} · Due ${invite.deadline} · Stake ${shortAmount(invite.stake)}` }),
      el("p", { class: "small" }, el("strong", { text: "Your task: " }), `${invite.task} (${invite.weightPercentage}%)`),
      el("form", { class: "dialog-actions", method: "post", action: "/invites/respond" },
        el("input", { type: "hidden", name: "inviteId", value: invite.id }),
        el("button", { class: "btn", name: "response", value: "declined", text: "Decline" }),
        el("button", { class: "btn primary", name: "response", value: "accepted", text: "Accept" })))));
}

function renderProject(project, me) {
  document.title = `CommitD — ${project.name}`;
  $("#crumb-project").textContent = project.name;
  $("#project-name").textContent = project.name;
  $("#project-description").textContent = project.description;
  $("#project-deadline").textContent = `Due ${project.deadline}`;
  $("#project-member-count").textContent = `${project.members.length} member${project.members.length === 1 ? "" : "s"}`;
  $("#project-repo").href = project.repoUrl;
  $("#project-repo").textContent = `${project.repoUrl.replace(/^https:\/\//, "")} ↗`;

  const stakedCount = project.members.filter((member) => member.staked).length;
  const allStaked = stakedCount === project.members.length;
  const status = $("#project-status");
  if (project.evaluation.status === "PAID") {
    status.className = "badge green";
    status.textContent = "Paid out";
  } else if (project.evaluation.status === "RUNNING") {
    status.className = "badge amber";
    status.textContent = "Evaluating";
  } else if (project.contract.status === "CREATED") {
    status.className = "badge green";
    status.textContent = project.deadlinePassed ? "Awaiting settlement" : "Contract live";
  } else if (project.deadlinePassed) {
    status.className = "badge";
    status.textContent = "Deadline passed";
  } else {
    status.className = allStaked ? "badge green" : "badge amber";
    status.textContent = allStaked ? "Fully staked" : "Awaiting stakes";
  }

  const notice = $("#stake-notice");
  notice.hidden = project.youStaked || project.deadlinePassed || project.locked;
  notice.textContent = `${stakedCount} of ${project.members.length} members have staked. Add yours to get started.`;

  // Tasks, with the All / Mine filter
  const mineOnly = params.get("filter") === "mine";
  $("#tab-all").href = `?project=${project.id}&filter=all`;
  $("#tab-mine").href = `?project=${project.id}&filter=mine`;
  $("#tab-all").className = mineOnly ? "" : "active";
  $("#tab-mine").className = mineOnly ? "active" : "";

  const tasks = project.tasks
    .map((task, index) => ({ ...task, label: `CD-${String(index + 1).padStart(2, "0")}` }))
    .filter((task) => !mineOnly || task.assigneeId === me.userId);
  $("#task-list").replaceChildren(...(tasks.length
    ? tasks.map((task) => el("li", { class: "card task" },
        el("span", { class: "task-id", text: task.label }),
        el("h3", { text: task.title }),
        el("div", { class: "row between small" },
          el("span", { class: "row" }, avatar(task.assigneeInitials), task.assigneeName),
          el("span", { class: "badge", text: `${task.weightPercentage}%` }))))
    : [el("li", { class: "muted", text: mineOnly ? "You don't have a task in this project yet." : "No tasks yet." })]));

  // Pool
  const target = Number(project.poolTarget);
  $("#pool-value").textContent = money(project.poolTotal);
  $("#pool-target").textContent = `of ${money(project.poolTarget)} total commitment`;
  $("#pool-progress").style.width = `${target > 0 ? (Number(project.poolTotal) / target) * 100 : 0}%`;
  $("#pool-staked").textContent = `${stakedCount} of ${project.members.length} staked`;
  $("#pool-each").textContent = `${shortAmount(project.stakeAmount)} each`;

  const yourStake = $("#your-stake");
  yourStake.className = project.youStaked ? "badge green" : "badge amber";
  yourStake.textContent = project.youStaked ? `✓ ${shortAmount(project.stakeAmount)}` : "Pending";
  const stakeButton = $("#stake-button");
  stakeButton.textContent = `Stake ${shortAmount(project.stakeAmount)}`;
  stakeButton.hidden = project.youStaked || project.deadlinePassed || project.locked;
  $("#stake-title").textContent = `Stake ${shortAmount(project.stakeAmount)}?`;
  renderStakeWallet(project, me.wallet);

  // Team
  $("#member-list").replaceChildren(...project.members.map((member) =>
    el("li", { class: "row", title: member.walletAddress ? `Wallet ${member.walletAddress}` : "" },
      avatar(member.initials),
      member.name,
      member.staked
        ? el("span", { class: "badge green push", text: `✓ ${shortAmount(project.stakeAmount)}` })
        : el("span", { class: "badge amber push", text: "Pending" }))));

  // Pending invitations, cancellable by whoever sent them or the creator
  $("#pending-invites-section").hidden = project.pendingInvites.length === 0;
  $("#pending-invites").replaceChildren(...project.pendingInvites.map((invite) =>
    el("li", { class: "stack", style: "gap: 4px" },
      el("div", { class: "row" },
        el("span", { style: "overflow-wrap: anywhere", text: invite.email }),
        invite.canCancel
          ? el("form", { class: "push", method: "post", action: "/invites/cancel" },
              el("input", { type: "hidden", name: "projectId", value: project.id }),
              el("input", { type: "hidden", name: "inviteId", value: invite.id }),
              el("button", { class: "link-btn", type: "submit", text: "Cancel" }))
          : el("span", { class: "badge amber push", text: "Pending" })),
      el("span", { class: "muted small", text: `${invite.task} · ${invite.weightPercentage}% · sent by ${invite.invitedBy}` }))));

  // Add-task dialog: only members without a task can be picked
  $("#member-select").replaceChildren(...project.members.map((member) => {
    const option = el("option", { value: member.userId, text: member.hasTask ? `${member.name} (has a task)` : member.name });
    option.disabled = member.hasTask;
    return option;
  }));
  const firstFree = project.members.find((member) => !member.hasTask);
  if (firstFree) $("#member-select").value = firstFree.userId;

  renderWeights(project);
  renderContract(project);
  renderEvaluation(project);

  // Until the contract is created, members can leave and the creator can delete the project.
  // The creator can't leave, since only they can create the contract.
  $("#leave-section").hidden = project.locked;
  $("#leave-button").hidden = project.youCreated;
  $("#delete-button").hidden = !project.youCreated;
  $("#leave-title").textContent = `Leave ${project.name}?`;
  $("#delete-title").textContent = `Delete ${project.name}?`;

  // Once the contract exists, tasks, weights, members and stakes are fixed
  for (const id of ["#add-task-button", "#invite-button"]) $(id).hidden = project.locked;
  if (project.locked) $("#edit-weights-button").hidden = true;

  document.querySelectorAll('input[name="projectId"]').forEach((input) => { input.value = project.id; });
  $("#project-view").hidden = false;
}

// The stake dialog says what's in the wallet, and blocks staking more than is free (the server checks this too)
function renderStakeWallet(project, wallet) {
  const line = $("#stake-wallet");
  const short = wallet.available && freeFunds(wallet) < Number(project.stakeAmount);
  $("#stake-confirm").disabled = short;
  $("#stake-add-funds").hidden = !short;
  if (!wallet.available) {
    line.className = "small warning";
    line.textContent = `Your wallet can't be checked right now: ${wallet.error}`;
  } else if (short) {
    line.className = "small warning";
    line.textContent = `You have ${usdc(freeFunds(wallet))} free in your wallet, which isn't enough. Add funds first.`;
  } else {
    line.className = "small muted";
    line.textContent = `You have ${usdc(freeFunds(wallet))} free in your wallet.`;
  }
}

// ===== Evaluation =====
// Before the deadline, the card counts down to it. After it, any member can request evaluation, which runs in the
// background on the server (Gemini scores everyone, then the pool is paid out on-chain). While it runs the page
// re-fetches every few seconds, and once it's done the results section shows each member's score, feedback and payout.
let countdownTimer;
let pollTimer;

function renderEvaluation(project) {
  clearInterval(countdownTimer);
  clearTimeout(pollTimer);
  const evaluation = project.evaluation;
  const clock = $("#evaluation-countdown");
  const label = $("#evaluation-countdown-label");
  const form = $("#evaluation-form");
  const button = $("#request-evaluation-button");
  const pad = (n) => String(n).padStart(2, "0");
  const secondsLeft = () => Math.max(0, Math.floor((project.deadlineEpochMillis - Date.now()) / 1000));

  renderResults(project);

  function beforeDeadline(left) {
    form.hidden = false;
    button.disabled = true;
    button.textContent = "Request evaluation";
    button.title = "Available once the project deadline has passed";
    const days = Math.floor(left / 86400);
    const hours = Math.floor((left % 86400) / 3600);
    const minutes = Math.floor((left % 3600) / 60);
    clock.className = "countdown";
    clock.textContent = `${days > 0 ? `${days}d ` : ""}${pad(hours)}h ${pad(minutes)}m ${pad(left % 60)}s`;
    label.className = "muted small";
    label.textContent = "until evaluation can be requested (the project deadline)";
  }

  function afterDeadline() {
    form.hidden = evaluation.status === "PAID";
    button.disabled = !evaluation.requestable;
    button.title = evaluation.requestable ? "" : (evaluation.blocker || "");
    label.className = "muted small";

    if (evaluation.status === "RUNNING") {
      clock.className = "countdown";
      clock.textContent = "Evaluating…";
      label.textContent = evaluation.scoresSaved
        ? "Scores are in. Paying out the pool on the blockchain…"
        : `Requested by ${evaluation.requestedBy}. Gemini is reviewing each member's commits, then the pool is paid out. This can take a minute or two.`;
      button.textContent = "Evaluating…";
      pollTimer = setTimeout(refresh, 3000);
    } else if (evaluation.status === "PAID") {
      clock.className = "countdown done";
      clock.textContent = "✓ Paid out";
      label.textContent = `Evaluated ${evaluation.finishedAt}. Each member's share has been sent to their wallet.`;
    } else if (evaluation.status === "FAILED") {
      clock.className = "countdown";
      clock.textContent = "Didn't finish";
      label.className = "small warning";
      label.textContent = evaluation.requestable ? evaluation.error : `${evaluation.error} ${evaluation.blocker}`;
      button.textContent = evaluation.scoresSaved ? "Finish paying out" : "Try again";
    } else if (evaluation.requestable) {
      clock.className = "countdown done";
      clock.textContent = "✓ Ready for evaluation";
      label.textContent = "The deadline has passed. Any member can now request evaluation.";
      button.textContent = "Request evaluation";
    } else {
      clock.className = "countdown";
      clock.textContent = "Can't be evaluated";
      label.className = "small warning";
      label.textContent = evaluation.blocker;
      button.textContent = "Request evaluation";
    }
  }

  function tick() {
    const left = secondsLeft();
    if (left > 0) {
      beforeDeadline(left);
      return;
    }
    clearInterval(countdownTimer);
    afterDeadline();
  }
  tick();
  if (secondsLeft() > 0) countdownTimer = setInterval(tick, 1000);
}

// One card per member: score, payout (and the gain or loss against their stake), Gemini's feedback and evidence
function renderResults(project) {
  const evaluation = project.evaluation;
  const section = $("#results-section");
  section.hidden = evaluation.results.length === 0;
  if (section.hidden) return;

  const paid = evaluation.status === "PAID";
  const badge = $("#results-badge");
  badge.className = paid ? "badge green" : "badge amber";
  badge.textContent = paid ? "✓ Paid out" : "Payout pending";
  $("#results-summary").textContent = paid
    ? `Scored by Gemini and paid out ${evaluation.finishedAt}. Everyone staked ${usdc(project.stakeAmount)}; payouts depend on scores and task weights.`
    : evaluation.status === "RUNNING"
      ? "Scored by Gemini. Sending the payouts now…"
      : "Scored by Gemini, but the payouts haven't been sent yet. Request evaluation again to finish (the scores are kept).";

  $("#results-list").replaceChildren(...evaluation.results.map((result) => {
    const change = Number(result.change);
    const changeText = change === 0 ? "Even" : `${change > 0 ? "+" : "−"}${usdc(Math.abs(change))}`;
    return el("li", { class: "card task" },
      el("div", { class: "result-head" },
        avatar(result.initials),
        el("div", {},
          el("h3", { text: result.you ? `${result.name} (you)` : result.name }),
          el("span", { class: "muted small", text: `${result.weightPercentage}% of the work` })),
        el("span", { class: "result-score", title: "Gemini's score", text: `${result.score}/10` }),
        el("div", { class: "result-money" },
          el("strong", { text: usdc(result.payout) }),
          el("div", { class: `small ${change > 0 ? "gain" : change < 0 ? "loss" : "muted"}`,
            text: `${changeText} vs ${usdc(result.staked)} staked` }))),
      el("p", { class: "result-feedback small", text: result.feedback || "No feedback." }),
      result.evidence.length
        ? el("details", { class: "evidence small" },
            el("summary", { text: `Evidence (${result.evidence.length})` }),
            el("ul", {}, ...result.evidence.map((item) => el("li", { text: item }))))
        : null);
  }));
}

// Starting it only takes a moment (the work happens in the background), but show that it's been pressed
$("#evaluation-form").addEventListener("submit", () => {
  const button = $("#request-evaluation-button");
  setTimeout(() => { button.disabled = true; button.textContent = "Starting…"; });
});

// Re-fetches the project (and the wallet, whose balance changes when the pool pays out) while an evaluation runs
let currentProjectId;
async function refresh() {
  try {
    const [me, project] = await Promise.all([getJson("/api/me"), getJson(`/api/projects/${currentProjectId}`)]);
    renderAccount(me);
    renderInbox(me.invites);
    renderProject(project, me);
  } catch (error) {
    showError(`Couldn't check on the evaluation: ${error.message}`);
    pollTimer = setTimeout(refresh, 10000);
  }
}

// Task weights must total 100%. Pending invites hold their weight until they're answered.
function renderWeights(project) {
  const total = project.assignedWeight;
  const free = 100 - total - project.reservedWeight;
  const badge = $("#weight-total");
  badge.className = total === 100 ? "badge green" : "badge amber";
  badge.textContent = `${total}% of 100% assigned`;
  badge.hidden = project.tasks.length === 0;
  $("#edit-weights-button").hidden = project.tasks.length === 0;

  // New task / invite weight inputs: default to (and cap at) what's left
  const reservedNote = project.reservedWeight > 0 ? ` (${project.reservedWeight}% is held by pending invitations)` : "";
  for (const input of [$("#task-weight"), $("#invite-weight")]) {
    input.max = Math.max(free, 1);
    input.value = free > 0 ? free : "";
    input.closest("label").querySelector("[data-weight-hint]").textContent = free > 0
      ? `${free}% of the project's weight is left${reservedNote}.`
      : `No weight is left${reservedNote}. Lower other tasks with Edit weights first.`;
  }

  // Edit weights dialog: one input per task, with a live total
  const inputs = project.tasks.map((task) => {
    const input = el("input", { type: "number", name: `weight-${task.id}`, min: 1, max: 100, step: 1, required: "", value: task.weightPercentage });
    input.addEventListener("input", updateDialogTotal);
    return input;
  });
  $("#weights-list").replaceChildren(...project.tasks.map((task, i) =>
    el("label", { class: "weight-row small" }, el("span", { text: `${task.title} · ${task.assigneeName}` }), inputs[i])));

  function updateDialogTotal() {
    const sum = inputs.reduce((acc, input) => acc + (Number(input.value) || 0), 0) + project.reservedWeight;
    const line = $("#weights-dialog-total");
    line.textContent = `Total: ${sum}%${reservedNote}${sum > 100 ? " — that's more than 100%" : sum < 100 ? ` — ${100 - sum}% unassigned` : " ✓"}`;
    line.className = sum > 100 ? "small error" : "small";
  }
  updateDialogTotal();
}

// The creator can create the contract once every member has a task, has staked, and weights total 100%.
// Creating it puts a pool on the chain and deposits every member's stake.
function renderContract(project) {
  const contract = project.contract;
  const statusLine = $("#contract-status");
  const button = $("#contract-button");
  const details = $("#contract-details");
  statusLine.className = "muted small";

  if (contract.status === "CREATED") {
    $("#contract-blockers").replaceChildren();
    $("#contract-form").hidden = true;
    statusLine.textContent = `✓ Created ${contract.createdAt}. Every member's stake is held in the pool.`;
    details.hidden = false;
    details.replaceChildren(...contractDetails(project));
    return;
  }

  // NONE or CREATING (an earlier attempt didn't finish; pressing again picks up where it left off)
  const blockers = project.contractBlockers;
  const ready = blockers.length === 0;
  const retry = contract.status === "CREATING";
  details.hidden = true;
  $("#contract-form").hidden = !project.youCreated;
  $("#contract-blockers").replaceChildren(...blockers.map((text) => el("li", { text })));
  button.textContent = retry ? "Finish creating smart contract" : "Create smart contract";
  button.disabled = !ready || !project.youCreated || !contract.chainAvailable;

  if (!contract.chainAvailable) {
    statusLine.className = "small warning";
    statusLine.textContent = `The local blockchain isn't running, so the contract can't be created: ${contract.chainError}.`;
  } else if (retry) {
    statusLine.textContent = project.youCreated
      ? "Creating the contract didn't finish. Press the button to complete it. Nothing is deposited twice."
      : `Creating the contract didn't finish. ${project.createdBy} needs to complete it.`;
  } else if (ready) {
    statusLine.textContent = project.youCreated
      ? `Everyone has a task and has staked. Creating it deposits ${money(project.poolTarget)} USDC into the pool.`
      : `Ready. Waiting for ${project.createdBy} to create it.`;
  } else {
    statusLine.textContent = project.youCreated ? "You can create it once these are done:" : `${project.createdBy} can create it once these are done:`;
  }
}

function contractDetails(project) {
  const contract = project.contract;
  const short = (address) => address ? `${address.slice(0, 6)}…${address.slice(-4)}` : "—";
  const row = (label, value, props = {}) => [el("dt", { text: label }), el("dd", { ...props, text: value })];
  const rows = [
    row("Pool ID", contract.poolId, { class: "mono" }),
    row("Contract", short(contract.contractAddress), { class: "mono", title: contract.contractAddress || "" }),
  ];
  const onChain = contract.onChain;
  if (!contract.chainAvailable) {
    rows.push(row("Holding", "Can't check: the blockchain isn't running", { class: "warning" }));
  } else if (!onChain) {
    rows.push(row("Holding", "Couldn't read the pool from the chain", { class: "warning" }));
  } else if (!onChain.exists) {
    rows.push(row("Holding", "This pool isn't on the current chain (its state was probably deleted)", { class: "warning" }));
  } else {
    rows.push(row("Holding", `${usdc(onChain.totalDeposited)} USDC`));
    rows.push(row("Deposits close", onChain.depositsClose));
  }
  return rows.flat();
}

// Creating the contract sends several transactions, so show that it's working
$("#contract-form").addEventListener("submit", () => {
  const button = $("#contract-button");
  // Disable after the browser has captured the form, so the submission still goes through
  setTimeout(() => { button.disabled = true; button.textContent = "Creating… (a few seconds)"; });
});

async function load() {
  showError(params.get("error"));
  const notice = params.get("notice");
  if (notice) {
    $("#page-notice").textContent = notice;
    $("#page-notice").hidden = false;
  }
  const me = await getJson("/api/me");
  renderAccount(me);
  renderInbox(me.invites);

  const requested = Number(params.get("project"));
  const projectId = me.projects.some((project) => project.id === requested) ? requested : me.projects[0]?.id;
  renderProjectList(me.projects, projectId);

  if (projectId === undefined) {
    $("#empty-state").hidden = false;
    return;
  }
  currentProjectId = projectId;
  renderProject(await getJson(`/api/projects/${projectId}`), me);
}

load().catch((error) => showError(error.message));
