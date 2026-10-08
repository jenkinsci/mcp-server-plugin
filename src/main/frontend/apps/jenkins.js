import { App } from "@modelcontextprotocol/ext-apps/app-with-deps";
import {
  appBar,
  appBarTitle,
  buildCaption,
  buildsTable,
  button,
  consoleOutput,
  descriptionBlock,
  escapeHtml,
  formatBuildTime,
  formatDuration,
  jobsTable,
  link,
  notice,
  pageBody,
  pageFooter,
  pageHeader,
  propertiesTable,
  readLook,
  section,
  spinner,
  statusCell,
  statusOf,
  table,
} from "../kit/jenkins-kit.js";
import { readCallableTools, toolCaller, valueOf } from "../kit/tool-calls.js";

const LOAD_MORE_STEP = 200;

const BUILD_HISTORY_SIZE = 20;

const CAPTION_FIELDS = "number,result,building,timestamp,duration,displayName";

const BUILD_FIELDS = `${CAPTION_FIELDS},description,actions[failCount,skipCount,totalCount]`;

const JOB_FIELDS = `fullName,displayName,description,color,builds[number,result,building,timestamp,duration]{0,${BUILD_HISTORY_SIZE}}`;

const JOB_LIST_FIELDS = "name,fullName,color,lastBuild[number,result,timestamp,building]";

const RESULT_COLORS = { SUCCESS: "blue", FAILURE: "red", UNSTABLE: "yellow", ABORTED: "aborted" };

const SCM_PROPERTY_LIMIT = 40;

const STACK_TRACE_LINES = 20;

const look = readLook(document);
const root = document.getElementById("app");
const app = new App({ name: "jenkins", version: "0.3.0" }, {}, { autoResize: true });
const callJenkins = toolCaller(app, readCallableTools(document));
const state = {
  view: document.querySelector('meta[name="jenkins:view"]')?.getAttribute("content") ?? "jobs",
  args: {},
  data: {},
  error: null,
  loadingMore: false,
};

document.body.classList.add(`jenkins-${look.version}`);

function buildArguments(args) {
  return args.buildNumber == null
    ? { jobFullName: args.jobFullName }
    : { jobFullName: args.jobFullName, buildNumber: Number(args.buildNumber) };
}

function hasAbsoluteUrl(item) {
  return typeof item?.url === "string" && /^https?:\/\//.test(item.url);
}

function hasEarlierLines(log) {
  return typeof log?.startLine === "number" && log.startLine > 1;
}

let rootUrlIsMissing = false;

async function callWithUrlUnlessRootUrlIsMissing(name, toolArguments, fields) {
  if (!rootUrlIsMissing) {
    try {
      return await callJenkins(name, { ...toolArguments, tree: `${fields},url` });
    } catch (error) {
      rootUrlIsMissing = /Root URL/i.test(error.message);
      if (!rootUrlIsMissing) {
        throw error;
      }
      logToHost("warning", "Jenkins has no root URL configured, so Open in Jenkins is hidden");
    }
  }
  return await callJenkins(name, { ...toolArguments, tree: fields });
}

function optional(name, toolArguments) {
  return callJenkins(name, toolArguments).catch(() => null);
}

function logToHost(level, data) {
  app.sendLog({ level, logger: "jenkins", data }).catch(() => {});
}

async function run(load) {
  state.error = null;
  render();
  try {
    await load();
  } catch (error) {
    state.error = error.message;
    logToHost("error", `${state.view} view failed: ${error.message}`);
  }
  render();
  tellTheModelWhatIsOnScreen();
}

function open(view, args, seed = null) {
  Object.assign(state, { view, args, data: {} });
  return run(() => loaders[view](args, seed));
}

async function loadConsole(args, seed) {
  const isSearch = Array.isArray(seed?.matches);
  const [build, log] = await Promise.all([
    callWithUrlUnlessRootUrlIsMissing("getBuild", buildArguments(args), CAPTION_FIELDS),
    isSearch || Array.isArray(seed?.lines) ? seed : callJenkins("getBuildLog", buildArguments(args)),
  ]);
  state.data.build = build;
  state.data[isSearch ? "search" : "log"] = log;
}

async function loadBuild(args) {
  const buildArgs = buildArguments(args);
  const [build, changeSets, scms, scripts] = await Promise.all([
    callWithUrlUnlessRootUrlIsMissing("getBuild", buildArgs, BUILD_FIELDS),
    optional("getBuildChangeSets", buildArgs),
    optional("getBuildScm", buildArgs),
    optional("getReplayScripts", buildArgs),
  ]);
  Object.assign(state.data, { build, changeSets, scms, scripts });
}

async function loadJob(args) {
  const [job, scms] = await Promise.all([
    callWithUrlUnlessRootUrlIsMissing("getJob", { jobFullName: args.jobFullName }, JOB_FIELDS),
    optional("getJobScm", { jobFullName: args.jobFullName }),
  ]);
  Object.assign(state.data, { job, scms });
}

async function loadJobs(args, seed) {
  if (Array.isArray(seed) && seed.some((job) => "lastResult" in job)) {
    state.data.jobs = seed.map((job) => ({
      name: job.displayName ?? job.name,
      fullName: job.fullName,
      color: RESULT_COLORS[job.lastResult] ?? "notbuilt",
    }));
    return;
  }
  const listArguments = args.parentFullName
    ? { parentFullName: args.parentFullName, tree: JOB_LIST_FIELDS }
    : { tree: JOB_LIST_FIELDS };
  state.data.jobs = await callJenkins("getJobs", listArguments);
}

async function loadTests(args, seed) {
  const buildArgs = buildArguments(args);
  const [build, report, flaky] = await Promise.all([
    callWithUrlUnlessRootUrlIsMissing("getBuild", buildArgs, CAPTION_FIELDS),
    seed?.failingTests || seed?.TestResult ? seed : callJenkins("getTestResults", { ...buildArgs, onlyFailingTests: true }),
    seed?.TestResultWithFlakyFailures ? seed : optional("getFlakyFailures", buildArgs),
  ]);
  Object.assign(state.data, { build, report, flaky: flaky?.TestResultWithFlakyFailures ?? [] });
}

async function loadQueueItem(args, seed) {
  state.data.item = seed?.id != null ? seed : await callJenkins("getQueueItem", { id: Number(args.id) });
}

async function loadSystemLog(args, seed) {
  if (Array.isArray(seed)) {
    state.data.recorders = seed;
    return;
  }
  state.data.entries = (seed?.entries ? seed : await callJenkins("getSystemLog", args)).entries ?? [];
}

async function loadStatus() {
  const [status, user] = await Promise.all([optional("getStatus", {}), optional("whoAmI", {})]);
  Object.assign(state.data, { status, user });
}

const loaders = {
  "build-log": loadConsole,
  build: loadBuild,
  job: loadJob,
  jobs: loadJobs,
  "test-results": loadTests,
  "queue-item": loadQueueItem,
  "system-log": loadSystemLog,
  status: loadStatus,
};

async function loadMoreLines() {
  state.loadingMore = true;
  render();
  try {
    const shown = state.data.log?.lines?.length ?? 0;
    state.data.log = await callJenkins("getBuildLog", {
      ...buildArguments(state.args),
      limit: -(shown + LOAD_MORE_STEP),
    });
  } catch (error) {
    state.error = error.message;
  }
  state.loadingMore = false;
  render();
}

function folderCrumbs(fullName, includeLast) {
  const segments = fullName ? fullName.split("/") : [];
  const folders = includeLast ? segments : segments.slice(0, -1);
  return folders.map((segment, index) => ({
    label: segment,
    action: "open-jobs",
    data: { parent: segments.slice(0, index + 1).join("/") },
  }));
}

function buildCrumbs(page) {
  const jobFullName = state.args.jobFullName ?? "";
  const number = state.data.build?.number ?? state.args.buildNumber;
  const jobCrumb = { label: jobFullName.split("/").pop(), action: "open-job", data: { job: jobFullName } };
  const buildCrumb = number == null ? { label: "…" } : { label: `#${number}` };
  if (!page) {
    return [...folderCrumbs(jobFullName, false), jobCrumb, buildCrumb];
  }
  return [
    ...folderCrumbs(jobFullName, false),
    jobCrumb,
    { ...buildCrumb, action: "open-build", data: { job: jobFullName, build: number } },
    { label: page },
  ];
}

function crumbs() {
  switch (state.view) {
    case "jobs":
      return state.args.parentFullName ? folderCrumbs(state.args.parentFullName, true) : [{ label: "All" }];
    case "job":
      return [...folderCrumbs(state.args.jobFullName, false), { label: state.args.jobFullName?.split("/").pop() ?? "" }];
    case "build":
      return buildCrumbs(null);
    case "build-log":
      return buildCrumbs("Console Output");
    case "test-results":
      return buildCrumbs("Test Result");
    case "queue-item":
      return [{ label: "Build Queue" }];
    case "system-log":
      return [{ label: "System Log" }];
    default:
      return [{ label: "Status" }];
  }
}

function openInJenkinsButton(item) {
  return hasAbsoluteUrl(item)
    ? button({ label: "Open in Jenkins", action: "open", symbol: look.symbol("external"), primary: true })
    : "";
}

function urlOfCurrentView() {
  return state.view === "job" ? state.data.job?.url : state.data.build?.url;
}

function captionOrTitle(title) {
  return state.data.build ? buildCaption(look, state.data.build) : appBarTitle(title);
}

function testCounts(build) {
  return (build?.actions ?? []).find((action) => action && "totalCount" in action) ?? null;
}

function scmSection(scms) {
  if (!Array.isArray(scms) || !scms.length) {
    return "";
  }
  const properties = scms.flatMap((scm, index) => flatten(scm, scms.length > 1 ? `scm${index + 1}` : ""));
  return section("SCM", propertiesTable(properties.slice(0, SCM_PROPERTY_LIMIT)));
}

function flatten(value, prefix) {
  if (value == null || typeof value !== "object") {
    return prefix ? [[prefix, String(value)]] : [];
  }
  return Object.entries(value)
    .filter(([key]) => key !== "_class")
    .flatMap(([key, child]) => flatten(child, prefix ? `${prefix}.${key}` : key));
}

function consoleView() {
  const { build, log, search } = state.data;
  const controls = [
    hasEarlierLines(log)
      ? button({
          label: state.loadingMore ? "Loading…" : `Load ${LOAD_MORE_STEP} more lines`,
          action: "load-more",
          symbol: look.symbol("terminal"),
          disabled: state.loadingMore,
        })
      : "",
    search ? button({ label: "Show the console", action: "open-console", symbol: look.symbol("terminal") }) : "",
    openInJenkinsButton(build),
  ];
  const body = search
    ? section(`${search.matchCount ?? search.matches.length} matches for "${search.pattern}"`, consoleOutput(searchLines(search)))
    : log
      ? consoleOutput(log.lines ?? [])
      : spinner("Loading the console output");
  return appBar(captionOrTitle("Console Output"), controls) + body;
}

function searchLines(search) {
  return search.matches.flatMap((match, index) => [
    ...(index ? ["…"] : []),
    ...(match.contextLines ?? [match.matchedLine]).map(
      (line, offset) => `${String((match.contextStartLine ?? match.matchedLineNumber) + offset).padStart(6)}  ${line}`,
    ),
  ]);
}

function buildView() {
  const { build, changeSets, scms, scripts } = state.data;
  if (!build) {
    return appBar(appBarTitle("Build")) + spinner("Loading the build");
  }
  const counts = testCounts(build);
  const controls = [
    button({ label: "Console Output", action: "open-console", symbol: look.symbol("terminal") }),
    counts ? button({ label: "Test Result", action: "open-tests" }) : "",
    openInJenkinsButton(build),
  ];
  const details = propertiesTable([
    ["Status", statusOf(build).label],
    ["Started", formatBuildTime(build.timestamp)],
    ["Duration", build.building ? "In progress" : formatDuration(build.duration)],
    ["Tests", counts ? `${counts.failCount} failures, ${counts.skipCount} skipped, ${counts.totalCount} tests` : null],
  ]);
  return (
    appBar(buildCaption(look, build), controls) +
    descriptionBlock(build.description) +
    section("Details", details) +
    changesSection(changeSets) +
    scmSection(scms) +
    (scripts?.mainScript ? section("Pipeline script", consoleOutput(scripts.mainScript.split("\n"))) : "")
  );
}

function changesSection(changeSets) {
  if (!Array.isArray(changeSets)) {
    return "";
  }
  const changes = changeSets.flatMap((changeSet) => changeSet?.items ?? []);
  if (!changes.length) {
    return section("Changes", descriptionBlock("No changes."));
  }
  const rows = changes
    .map(
      (change) =>
        `<tr><td><code>${escapeHtml(String(change.commitId ?? "").slice(0, 8))}</code></td>` +
        `<td>${escapeHtml(change.author?.fullName ?? "")}</td>` +
        `<td>${escapeHtml(String(change.msg ?? change.comment ?? "").split("\n")[0])}</td></tr>`,
    )
    .join("");
  return section("Changes", table(["Commit", "Author", "Message"], rows));
}

function jobView() {
  const { job, scms } = state.data;
  const title = job?.displayName ?? state.args.jobFullName;
  const history = job ? buildsTable(look, state.args.jobFullName, job.builds ?? []) : spinner("Loading builds");
  return (
    appBar(appBarTitle(title), [openInJenkinsButton(job)]) +
    descriptionBlock(job?.description) +
    section("Builds", history) +
    scmSection(scms)
  );
}

function jobsView() {
  const title = state.args.scmUrl
    ? `Jobs using ${state.args.scmUrl}`
    : state.args.parentFullName
      ? state.args.parentFullName.split("/").pop()
      : "All jobs";
  const list = state.data.jobs ? jobsTable(look, state.data.jobs) : spinner("Loading jobs");
  return appBar(appBarTitle(title)) + list;
}

function testsView() {
  const { build, report, flaky } = state.data;
  const controls = [
    button({ label: "Console Output", action: "open-console", symbol: look.symbol("terminal") }),
    openInJenkinsButton(build),
  ];
  if (!report) {
    return appBar(captionOrTitle("Test Result"), controls) + spinner("Loading the test result");
  }
  const counts = report.TestResultAction;
  if (!counts) {
    return appBar(captionOrTitle("Test Result"), controls) + descriptionBlock("This build has no test result.");
  }
  const summary = descriptionBlock(`${counts.failCount} failures, ${counts.skipCount} skipped, ${counts.totalCount} tests`);
  return (
    appBar(captionOrTitle("Test Result"), controls) +
    section("Test Result", summary + testsTable(failingTests(report), "status-red")) +
    (flaky?.length ? section("Flaky tests", testsTable(flaky, "status-yellow")) : "")
  );
}

function failingTests(report) {
  if (Array.isArray(report.failingTests)) {
    return report.failingTests;
  }
  return (report.TestResult?.suites ?? [])
    .flatMap((suite) => suite.cases ?? [])
    .filter((test) => test.status === "FAILED" || test.status === "REGRESSION");
}

function testsTable(tests, symbol) {
  if (!tests.length) {
    return descriptionBlock("All tests passed.");
  }
  const rows = tests
    .map((test) => {
      const details = [test.errorDetails, ...String(test.errorStackTrace ?? "").split("\n").slice(0, STACK_TRACE_LINES)]
        .filter(Boolean)
        .map((line) => String(line));
      return (
        `<tr>${statusCell(look, symbol)}<td>${escapeHtml(`${test.className}.${test.name}`)}</td>` +
        `<td>${escapeHtml(test.age ?? "")}</td><td>${escapeHtml(formatDuration((test.duration ?? 0) * 1000))}</td></tr>` +
        (details.length ? `<tr><td></td><td colspan="3">${consoleOutput(details)}</td></tr>` : "")
      );
    })
    .join("");
  return table(["S", "Test", "Age", "Duration"], rows);
}

function queueItemView() {
  const item = state.data.item;
  if (!item) {
    return appBar(appBarTitle("Queue item")) + spinner("Loading the queue item");
  }
  const build = item.executable;
  const task = item.task ?? {};
  const controls =
    build?.number != null && task.fullName
      ? [link(`Build #${build.number}`, "open-build", { job: task.fullName, build: build.number })]
      : [];
  return (
    appBar(appBarTitle(`Queue item #${item.id}`), controls) +
    propertiesTable([
      ["Task", task.fullName ?? task.name],
      ["Why", item.why],
      ["In the queue since", item.inQueueSince ? formatBuildTime(item.inQueueSince) : null],
      ["Blocked", item.blocked],
      ["Buildable", item.buildable],
      ["Stuck", item.stuck],
      ["Cancelled", item.cancelled],
      ["Build", build?.number != null ? `#${build.number}` : null],
    ])
  );
}

function systemLogView() {
  const { entries, recorders } = state.data;
  if (recorders) {
    const rows = recorders
      .map((name) => `<tr><td>${link(name, "open-recorder", { recorder: name })}</td></tr>`)
      .join("");
    return appBar(appBarTitle("Log Recorders")) + table(["Name"], rows);
  }
  const title = state.args.recorder ? `Log recorder ${state.args.recorder}` : "System Log";
  if (!entries) {
    return appBar(appBarTitle(title)) + spinner("Loading the log");
  }
  const rows = entries
    .map(
      (entry) =>
        `<tr><td class="jenkins-table__cell--no-wrap">${escapeHtml(entry.timestamp)}</td><td>${escapeHtml(entry.level)}</td>` +
        `<td>${escapeHtml(entry.logger)}</td><td>${escapeHtml(entry.message)}</td></tr>` +
        (entry.exception ? `<tr><td></td><td colspan="3">${consoleOutput(String(entry.exception).split("\n"))}</td></tr>` : ""),
    )
    .join("");
  return appBar(appBarTitle(title)) + table(["Time", "Level", "Logger", "Message"], rows);
}

function statusView() {
  const { status, user } = state.data;
  if (!status && !user) {
    return appBar(appBarTitle("Status")) + spinner("Loading the status");
  }
  const statusProperties = Object.entries(status ?? {}).map(([name, value]) => [
    name,
    value != null && typeof value === "object" ? JSON.stringify(value) : value,
  ]);
  return (
    appBar(appBarTitle("Status")) +
    (user ? section("User", propertiesTable([["Signed in as", user.fullName]])) : "") +
    (status ? section("Jenkins", propertiesTable(statusProperties)) : "")
  );
}

const views = {
  "build-log": consoleView,
  build: buildView,
  job: jobView,
  jobs: jobsView,
  "test-results": testsView,
  "queue-item": queueItemView,
  "system-log": systemLogView,
  status: statusView,
};

function mainContent() {
  const errorNotice = state.error ? notice("Jenkins could not answer", state.error) : "";
  return errorNotice + views[state.view]();
}

function render() {
  root.innerHTML = pageHeader(look, crumbs()) + pageBody(mainContent()) + pageFooter(look.version);
  const log = root.querySelector("pre.console-output");
  if (log && state.view === "build-log" && state.data.log) {
    log.scrollTop = log.scrollHeight;
  }
}

function screenDescription() {
  const build = state.data.build;
  const buildName = `${state.args.jobFullName} #${build?.number ?? state.args.buildNumber ?? "?"}`;
  switch (state.view) {
    case "jobs":
      return `the Jenkins job list${state.args.parentFullName ? ` in ${state.args.parentFullName}` : ""}`;
    case "job":
      return `the build history of ${state.args.jobFullName}`;
    case "build":
      return `the build page of ${buildName} (${build ? statusOf(build).label : "loading"})`;
    case "build-log":
      return `the console output of ${buildName} (${build ? statusOf(build).label : "loading"})`;
    case "test-results":
      return `the test result of ${buildName}`;
    case "queue-item":
      return `the build queue item #${state.data.item?.id ?? state.args.id}`;
    case "system-log":
      return "the Jenkins system log";
    default:
      return "the Jenkins status";
  }
}

function tellTheModelWhatIsOnScreen() {
  if (!app.getHostCapabilities()?.updateModelContext) {
    return;
  }
  app
    .updateModelContext({
      content: [{ type: "text", text: `In the Jenkins app the user is now looking at ${screenDescription()}.` }],
    })
    .catch(() => {});
}

function currentBuild() {
  return { jobFullName: state.args.jobFullName, buildNumber: state.data.build?.number ?? state.args.buildNumber };
}

const actions = {
  "open-jobs": (data) => open("jobs", data.parent ? { parentFullName: data.parent } : {}),
  "open-job": (data) => open("job", { jobFullName: data.job }),
  "open-build": (data) => open("build", { jobFullName: data.job, buildNumber: Number(data.build) }),
  "open-console": () => open("build-log", currentBuild()),
  "open-tests": () => open("test-results", currentBuild()),
  "open-recorder": (data) => open("system-log", { recorder: data.recorder }),
  "load-more": () => loadMoreLines(),
  open: () => app.openLink({ url: urlOfCurrentView() }),
};

function runActionOf(target) {
  if (!target || target.disabled) {
    return;
  }
  actions[target.dataset.action]?.(target.dataset);
}

root.addEventListener("click", (event) => {
  const target = event.target.closest("[data-action]");
  if (target) {
    event.preventDefault();
    runActionOf(target);
  }
});

root.addEventListener("keydown", (event) => {
  const target = event.target.closest('[role="button"][data-action]');
  if (target && (event.key === "Enter" || event.key === " ")) {
    event.preventDefault();
    runActionOf(target);
  }
});

app.addEventListener("toolinput", (params) => {
  state.args = params.arguments ?? {};
  render();
});

app.addEventListener("toolresult", (result) => {
  let seed = null;
  try {
    seed = valueOf(result);
  } catch (error) {
    state.error = error.message;
  }
  open(state.view, state.args, seed);
});

render();
await app.connect();
