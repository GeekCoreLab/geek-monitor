(() => {
  const page = document.querySelector("main[data-json-url]");
  const groups = document.getElementById("status-groups");
  const refreshStatus = document.getElementById("refresh-status");
  const updatedAt = document.getElementById("updated-at");
  const resourceCount = document.getElementById("resource-count");
  const rangeButtons = document.querySelectorAll(".range-btn");

  if (!page || !groups || !refreshStatus || !updatedAt || !resourceCount) return;

  const getInitialRange = () => {
    const urlParams = new URLSearchParams(window.location.search);
    const rangeParam = urlParams.get("range");
    if (rangeParam === "30d" || rangeParam === "24h") return rangeParam;
    return page.dataset.range || "24h";
  };

  let currentRange = getInitialRange();

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

    const is30d = (data.range ?? currentRange) === "30d";
    const rangeClass = is30d ? "range-30d" : "range-24h";
    const heading = escapeHtml(data.historyHeading ?? (is30d ? "过去 30 天 · 每格 2 小时" : "过去 24 小时 · 每格 1 小时"));
    const ariaLabel = escapeHtml(`${resource.name} ${data.historyAriaSuffix ?? (is30d ? "过去 30 天的状态历史，每格 2 小时" : "过去 24 小时的状态历史，每格 1 小时")}`);

    return `<article class="card"><div class="row"><h3>${escapeHtml(resource.name)}</h3><span class="status ${state}">${escapeHtml(resource.status)}</span></div>
      ${maintenance}
      <div class="history"><div class="history-heading">${heading}</div><div class="history-track-wrap"><div class="history-track ${rangeClass}" role="img" aria-label="${ariaLabel}">${history}</div></div>
        <div class="history-scale"><span>${escapeHtml(data.historyStart ?? "")}</span><span>现在 · ${escapeHtml(data.historyEnd ?? "")}</span></div></div>
      <ul class="methods">${methods || "<li>尚未配置监控方式</li>"}</ul></article>`;
  };

  const updateRangeButtons = (selectedRange) => {
    rangeButtons.forEach((btn) => {
      const active = btn.dataset.range === selectedRange;
      btn.classList.toggle("active", active);
      btn.setAttribute("aria-selected", active ? "true" : "false");
    });
  };

  const render = (data) => {
    if (!Array.isArray(data.groups)) throw new Error("Invalid status data");
    if (data.range) {
      currentRange = data.range;
      page.dataset.range = currentRange;
      updateRangeButtons(currentRange);
    }
    groups.innerHTML = data.groups.map((group) =>
      `<section><h2>${escapeHtml(group.name)}</h2>${(group.resources ?? []).map((resource) => renderResource(resource, data)).join("")}</section>`
    ).join("");
    resourceCount.textContent = String(data.count ?? 0);
    updatedAt.textContent = `更新于 ${data.updatedAt ?? ""}`;
  };

  let refreshing = false;
  const refresh = async (force = false) => {
    if (refreshing || (!force && document.visibilityState === "hidden")) return;
    refreshing = true;
    try {
      const dataUrl = new URL(page.dataset.jsonUrl, window.location.origin);
      dataUrl.searchParams.set("range", currentRange);
      const response = await fetch(dataUrl.toString(), { cache: "no-store", headers: { Accept: "application/json" } });
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

  const switchRange = (newRange) => {
    if (newRange !== "24h" && newRange !== "30d") return;
    if (newRange === currentRange && groups.querySelector(`.range-${newRange}`)) return;
    currentRange = newRange;
    page.dataset.range = currentRange;
    updateRangeButtons(currentRange);

    const url = new URL(window.location.href);
    if (currentRange === "24h") {
      url.searchParams.delete("range");
    } else {
      url.searchParams.set("range", currentRange);
    }
    window.history.replaceState(null, "", url.toString());

    refreshStatus.textContent = "正在切换范围...";
    refresh(true);
  };

  rangeButtons.forEach((btn) => {
    btn.addEventListener("click", () => switchRange(btn.dataset.range));
  });

  window.setInterval(() => refresh(false), 60_000);
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "visible") refresh(false);
  });
})();
