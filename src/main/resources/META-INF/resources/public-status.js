(() => {
  const page = document.querySelector("main[data-json-url]");
  const groups = document.getElementById("status-groups");
  const refreshStatus = document.getElementById("refresh-status");
  const updatedAt = document.getElementById("updated-at");
  const resourceCount = document.getElementById("resource-count");

  if (!page || !groups || !refreshStatus || !updatedAt || !resourceCount) return;

  const escapeHtml = (value) => String(value ?? "").replace(/[&<>"']/g, (character) => ({
    "&": "&amp;",
    "<": "&lt;",
    ">": "&gt;",
    '"': "&quot;",
    "'": "&#39;",
  })[character]);

  const stateClass = (state) => /^[A-Z_]+$/.test(String(state ?? "")) ? state : "UNKNOWN";

  const renderResource = (resource, data) => {
    const state = stateClass(resource.state);
    const maintenance = (resource.maintenanceWindows ?? []).map((window) => `
      <div class="maintenance-note"><div>
        <strong>${escapeHtml(window.name)} · ${window.status === "ACTIVE" ? "维护中" : "计划维护"}</strong>
        <span>${escapeHtml(window.period)}</span>
      </div></div>`).join("");
    const history = (resource.history ?? []).map((slot) => {
      const slotState = stateClass(slot.state);
      const label = `${slot.period}：${slot.status}`;
      return `<span class="history-slot ${slotState}" title="${escapeHtml(slot.period)} · ${escapeHtml(slot.status)}" aria-label="${escapeHtml(label)}"></span>`;
    }).join("");
    const methods = (resource.methods ?? []).map((method) =>
      `<li><span>${escapeHtml(method.name)}</span><span class="status ${stateClass(method.state)}">${escapeHtml(method.status)}</span></li>`
    ).join("");

    return `<article class="card"><div class="row"><h3>${escapeHtml(resource.name)}</h3><span class="status ${state}">${escapeHtml(resource.status)}</span></div>
      ${maintenance}
      <div class="history"><div class="history-heading">过去 30 天 · 每格 2 小时</div><div class="history-track-wrap"><div class="history-track" role="img" aria-label="${escapeHtml(resource.name)} 过去 30 天的状态历史，每格 2 小时">${history}</div></div>
        <div class="history-scale"><span>${escapeHtml(data.historyStart ?? "")}</span><span>现在 · ${escapeHtml(data.historyEnd ?? "")}</span></div></div>
      <ul class="methods">${methods || "<li>尚未配置监控方式</li>"}</ul></article>`;
  };

  const render = (data) => {
    if (!Array.isArray(data.groups)) throw new Error("Invalid status data");
    groups.innerHTML = data.groups.map((group) =>
      `<section><h2>${escapeHtml(group.name)}</h2>${(group.resources ?? []).map((resource) => renderResource(resource, data)).join("")}</section>`
    ).join("");
    resourceCount.textContent = String(data.count ?? 0);
    updatedAt.textContent = `更新于 ${data.updatedAt ?? ""}`;
  };

  let refreshing = false;
  const refresh = async () => {
    if (refreshing || document.visibilityState === "hidden") return;
    refreshing = true;
    try {
      const response = await fetch(page.dataset.jsonUrl, { cache: "no-store", headers: { Accept: "application/json" } });
      if (response.status === 404) {
        groups.innerHTML = "<p class=\"muted\">页面不存在或尚未公开</p>";
        resourceCount.textContent = "0";
        updatedAt.textContent = "";
        refreshStatus.textContent = "页面未公开，正在重试";
        return;
      }
      if (!response.ok) throw new Error(`Status request failed: ${response.status}`);
      render(await response.json());
      refreshStatus.textContent = "数据已更新";
    } catch (_) {
      refreshStatus.textContent = "刷新失败，将稍后重试";
    } finally {
      refreshing = false;
    }
  };

  window.setInterval(refresh, 60_000);
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "visible") refresh();
  });
})();
