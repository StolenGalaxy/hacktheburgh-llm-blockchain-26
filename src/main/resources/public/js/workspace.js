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
const money = (amount) => Number(amount).toFixed(2);
const shortAmount = (amount) => String(Number(amount));

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
}

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
      el("p", { class: "small" }, el("strong", { text: "Your task: " }), invite.task),
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
  if (project.deadlinePassed) {
    status.className = "badge";
    status.textContent = "Deadline passed";
  } else {
    status.className = allStaked ? "badge green" : "badge amber";
    status.textContent = allStaked ? "Fully staked" : "Awaiting stakes";
  }

  const notice = $("#stake-notice");
  notice.hidden = project.youStaked || project.deadlinePassed;
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
        task.details ? el("p", { class: "muted small", text: task.details }) : null,
        el("div", { class: "row small" },
          el("span", { class: "row" }, avatar(task.assigneeInitials), task.assigneeName))))
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
  stakeButton.hidden = project.youStaked || project.deadlinePassed;
  $("#stake-title").textContent = `Stake ${shortAmount(project.stakeAmount)}?`;

  // Team
  $("#member-list").replaceChildren(...project.members.map((member) =>
    el("li", { class: "row" },
      avatar(member.initials),
      member.name,
      member.staked
        ? el("span", { class: "badge green push", text: `✓ ${shortAmount(project.stakeAmount)}` })
        : el("span", { class: "badge amber push", text: "Pending" }))));

  // Add-task dialog: only members without a task can be picked
  $("#member-select").replaceChildren(...project.members.map((member) => {
    const option = el("option", { value: member.userId, text: member.hasTask ? `${member.name} (has a task)` : member.name });
    option.disabled = member.hasTask;
    return option;
  }));
  const firstFree = project.members.find((member) => !member.hasTask);
  if (firstFree) $("#member-select").value = firstFree.userId;

  document.querySelectorAll('input[name="projectId"]').forEach((input) => { input.value = project.id; });
  $("#project-view").hidden = false;
}

async function load() {
  showError(params.get("error"));
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
  renderProject(await getJson(`/api/projects/${projectId}`), me);
}

load().catch((error) => showError(error.message));
