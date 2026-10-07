const HTML_ESCAPES = { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" };

const STATUS_SYMBOLS = {
  SUCCESS: "status-blue",
  FAILURE: "status-red",
  UNSTABLE: "status-yellow",
  ABORTED: "status-aborted",
  NOT_BUILT: "status-nobuilt",
};

const STATUS_LABELS = {
  SUCCESS: "Success",
  FAILURE: "Failed",
  UNSTABLE: "Unstable",
  ABORTED: "Aborted",
  NOT_BUILT: "Not built",
};

const COLOR_SYMBOLS = {
  blue: "status-blue",
  red: "status-red",
  yellow: "status-yellow",
  aborted: "status-aborted",
  notbuilt: "status-nobuilt",
  disabled: "status-disabled",
  grey: "status-nobuilt",
};

const BUILD_TIME_FORMAT = {
  month: "short",
  day: "numeric",
  year: "numeric",
  hour: "numeric",
  minute: "2-digit",
  second: "2-digit",
};

export function escapeHtml(value) {
  return String(value ?? "").replace(/[&<>"']/g, (character) => HTML_ESCAPES[character]);
}

export function readLook(doc) {
  const templateMarkup = (id) => doc.getElementById(id)?.innerHTML ?? "";
  return {
    version: doc.querySelector('meta[name="jenkins:version"]')?.getAttribute("content") ?? "",
    logo: templateMarkup("jenkins-logo"),
    symbol: (name) => templateMarkup(`jenkins-symbol-${name}`),
  };
}

export function statusOf(build) {
  if (build.building) {
    return { symbol: "status-nobuilt-anime", label: "In progress" };
  }
  return {
    symbol: STATUS_SYMBOLS[build.result] ?? "status-nobuilt",
    label: STATUS_LABELS[build.result] ?? String(build.result ?? "Unknown"),
  };
}

export function statusOfColor(color) {
  const base = String(color ?? "notbuilt").replace(/_anime$/, "");
  const symbol = COLOR_SYMBOLS[base] ?? "status-nobuilt";
  return String(color ?? "").endsWith("_anime") ? `${symbol}-anime` : symbol;
}

export function formatBuildTime(timestamp, locale = "en-US") {
  return new Date(timestamp).toLocaleString(locale, BUILD_TIME_FORMAT);
}

export function actionAttributes(action, data = {}) {
  const dataAttributes = Object.entries(data)
    .filter(([, value]) => value != null)
    .map(([name, value]) => ` data-${name}="${escapeHtml(value)}"`)
    .join("");
  return ` role="button" tabindex="0" data-action="${escapeHtml(action)}"${dataAttributes}`;
}

export function pageHeader(look, crumbs) {
  const items = crumbs
    .map((crumb, index) =>
      index === crumbs.length - 1
        ? `<li aria-current="page" class="jenkins-breadcrumbs__list-item"><span>${escapeHtml(crumb.label)}</span></li>`
        : `<li class="jenkins-breadcrumbs__list-item"><a${actionAttributes(crumb.action, crumb.data)}>` +
          `${escapeHtml(crumb.label)}</a></li>`,
    )
    .join("");
  return (
    `<header id="page-header" class="jenkins-header"><div class="jenkins-header__main">` +
    `<div class="jenkins-header__navigation"><a class="app-jenkins-logo"${actionAttributes("open-jobs")}>${look.logo}` +
    `<span class="jenkins-mobile-hide">Jenkins</span></a>` +
    `<div id="breadcrumbBar" class="jenkins-breadcrumbs" aria-label="breadcrumb">` +
    `<ol class="jenkins-breadcrumbs__list" id="breadcrumbs">${items}</ol></div>` +
    `</div></div></header>`
  );
}

export function jobsTable(look, jobs, locale) {
  const rows = jobs
    .map((job) => {
      const isFolder = job.color == null;
      const link = isFolder
        ? actionAttributes("open-jobs", { parent: job.fullName })
        : actionAttributes("open-job", { job: job.fullName });
      const lastBuild = job.lastBuild
        ? `#${escapeHtml(job.lastBuild.number)} · ${escapeHtml(formatBuildTime(job.lastBuild.timestamp, locale))}`
        : isFolder
          ? "Folder"
          : "No builds yet";
      return (
        `<tr class="job-status-${escapeHtml(job.color ?? "folder")}">` +
        statusCell(look, isFolder ? "status-nobuilt" : statusOfColor(job.color)) +
        `<td><a class="jenkins-table__link"${link}>${escapeHtml(job.name)}</a></td>` +
        `<td>${lastBuild}</td></tr>`
      );
    })
    .join("");
  return table(["S", "Name", "Last build"], rows);
}

export function buildsTable(look, jobFullName, builds, locale) {
  const rows = builds
    .map((build) => {
      const status = statusOf(build);
      return (
        `<tr>${statusCell(look, status.symbol)}` +
        `<td><a class="jenkins-table__link"${actionAttributes("open-build", { job: jobFullName, build: build.number })}>` +
        `#${escapeHtml(build.number)}</a></td>` +
        `<td>${escapeHtml(status.label)}</td>` +
        `<td>${escapeHtml(formatBuildTime(build.timestamp, locale))}</td></tr>`
      );
    })
    .join("");
  return table(["S", "Build", "Result", "Started"], rows);
}

export function statusCell(look, symbol) {
  return (
    `<td class="jenkins-table__cell--tight jenkins-table__icon">` +
    `<div class="jenkins-table__cell__button-wrapper">${look.symbol(symbol)}</div></td>`
  );
}

export function table(headings, rows) {
  const head = headings
    .map((heading, index) => (index === 0 ? `<th class="jenkins-table__cell--tight">${heading}</th>` : `<th>${heading}</th>`))
    .join("");
  return `<table class="jenkins-table jenkins-table--medium"><thead><tr>${head}</tr></thead><tbody>${rows}</tbody></table>`;
}

export function link(label, action, data) {
  return `<a class="jenkins-table__link"${actionAttributes(action, data)}>${escapeHtml(label)}</a>`;
}

export function section(title, content) {
  return `<section class="jenkins-section"><h2 class="jenkins-section__title">${escapeHtml(title)}</h2>${content}</section>`;
}

export function propertiesTable(properties) {
  const rows = properties
    .filter(([, value]) => value != null && value !== "")
    .map(([name, value]) => `<tr><td>${escapeHtml(name)}</td><td>${escapeHtml(value)}</td></tr>`)
    .join("");
  return table(["Name", "Value"], rows);
}

export function descriptionBlock(text) {
  return text ? `<div class="jenkins-description">${escapeHtml(text)}</div>` : "";
}

export function formatDuration(milliseconds) {
  const seconds = Math.round((milliseconds ?? 0) / 1000);
  if (seconds < 60) {
    return `${seconds} sec`;
  }
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) {
    return `${minutes} min ${seconds % 60} sec`;
  }
  return `${Math.floor(minutes / 60)} hr ${minutes % 60} min`;
}

export function pageBody(content) {
  return `<div id="page-body" class="app-page-body app-page-body--one-column clear"><div id="main-panel">${content}</div></div>`;
}

export function appBar(content, controls = []) {
  return `<div class="jenkins-app-bar">${content}<div class="jenkins-app-bar__controls">${controls.join("")}</div></div>`;
}

export function appBarTitle(title) {
  return `<div class="jenkins-app-bar__content"><h1>${escapeHtml(title)}</h1></div>`;
}

export function buildCaption(look, build, locale) {
  const status = statusOf(build);
  return (
    `<div class="jenkins-app-bar__content jenkins-build-caption">` +
    `<span class="jenkins-visually-hidden">${escapeHtml(status.label)}</span>${look.symbol(status.symbol)}` +
    `<h1>#${escapeHtml(build.number)} (${escapeHtml(formatBuildTime(build.timestamp, locale))})</h1></div>`
  );
}

export function button({ label, action, symbol = "", primary = false, disabled = false }) {
  const classes = primary ? "jenkins-button jenkins-button--primary" : "jenkins-button";
  return (
    `<button type="button" class="${classes}" data-action="${escapeHtml(action)}"${disabled ? " disabled" : ""}>` +
    `${symbol}${escapeHtml(label)}</button>`
  );
}

export function consoleOutput(lines) {
  return `<pre class="console-output">${lines.map(escapeHtml).join("\n")}</pre>`;
}

export function notice(title, description) {
  return (
    `<div class="jenkins-notice"><div>${escapeHtml(title)}</div>` +
    `<div class="jenkins-notice__description">${escapeHtml(description)}</div></div>`
  );
}

export function spinner(text) {
  return `<p class="jenkins-spinner">${escapeHtml(text)}</p>`;
}

export function pageFooter(version) {
  return (
    `<footer class="page-footer"><div class="page-footer__flex-row"><div class="page-footer__links">` +
    `<span class="jenkins-button jenkins-button--tertiary jenkins_ver">Jenkins ${escapeHtml(version)}</span>` +
    `</div></div></footer>`
  );
}
