import assert from "node:assert/strict";
import { test } from "node:test";
import {
  appBar,
  buildCaption,
  buildsTable,
  button,
  consoleOutput,
  descriptionBlock,
  escapeHtml,
  formatDuration,
  jobsTable,
  link,
  notice,
  pageHeader,
  propertiesTable,
  section,
  statusOf,
  statusOfColor,
} from "../kit/jenkins-kit.js";

const look = {
  version: "2.541.3",
  logo: '<img id="jenkins-head-icon">',
  symbol: (name) => `<svg data-symbol="${name}"></svg>`,
};

test("console lines are escaped so a log can never inject markup", () => {
  const html = consoleOutput(["<script>alert(1)</script>", "Finished: FAILURE"]);
  assert.equal(
    html,
    '<pre class="console-output">&lt;script&gt;alert(1)&lt;/script&gt;\nFinished: FAILURE</pre>',
  );
});

test("a build result maps to the Jenkins status symbol and label", () => {
  assert.deepEqual(statusOf({ result: "FAILURE", building: false }), { symbol: "status-red", label: "Failed" });
  assert.deepEqual(statusOf({ result: "SUCCESS", building: false }), { symbol: "status-blue", label: "Success" });
  assert.deepEqual(statusOf({ result: null, building: true }), {
    symbol: "status-nobuilt-anime",
    label: "In progress",
  });
});

test("the build caption uses the jenkins-build-caption markup with the status symbol", () => {
  const html = buildCaption(look, { number: 7, result: "UNSTABLE", building: false, timestamp: 0 }, "en-US");
  assert.match(html, /^<div class="jenkins-app-bar__content jenkins-build-caption">/);
  assert.match(html, /<span class="jenkins-visually-hidden">Unstable<\/span><svg data-symbol="status-yellow">/);
  assert.match(html, /<h1>#7 \(.+\)<\/h1>/);
});

test("the header links the logo to the job list and makes every earlier breadcrumb clickable", () => {
  const html = pageHeader(look, [
    { label: "folder", action: "open-jobs", data: { parent: "folder" } },
    { label: "<job>", action: "open-job", data: { job: 'folder/"<job>"' } },
    { label: "#3" },
  ]);
  assert.match(
    html,
    /<a class="app-jenkins-logo" role="button" tabindex="0" data-action="open-jobs"><img id="jenkins-head-icon">/,
  );
  assert.match(html, /<a role="button" tabindex="0" data-action="open-jobs" data-parent="folder">folder<\/a>/);
  assert.match(
    html,
    /<a role="button" tabindex="0" data-action="open-job" data-job="folder\/&quot;&lt;job&gt;&quot;">&lt;job&gt;<\/a>/,
  );
  assert.doesNotMatch(html, /href=/);
  assert.match(html, /<li aria-current="page" class="jenkins-breadcrumbs__list-item"><span>#3<\/span><\/li>/);
});

test("a Jenkins ball color maps to its status symbol, animated while building", () => {
  assert.equal(statusOfColor("red"), "status-red");
  assert.equal(statusOfColor("blue_anime"), "status-blue-anime");
  assert.equal(statusOfColor("notbuilt"), "status-nobuilt");
  assert.equal(statusOfColor(undefined), "status-nobuilt");
});

test("the job list is a jenkins-table whose rows open the job or the folder", () => {
  const html = jobsTable(
    look,
    [
      { name: "demo-app", fullName: "demo-app", color: "red", lastBuild: { number: 1, timestamp: 0 } },
      { name: "team", fullName: "team" },
    ],
    "en-US",
  );
  assert.match(html, /^<table class="jenkins-table jenkins-table--medium">/);
  assert.match(html, /<tr class="job-status-red"><td class="jenkins-table__cell--tight jenkins-table__icon">/);
  assert.match(html, /<svg data-symbol="status-red">/);
  assert.match(html, /data-action="open-job" data-job="demo-app">demo-app<\/a>/);
  assert.match(html, /data-action="open-jobs" data-parent="team">team<\/a><\/td><td>Folder<\/td>/);
});

test("the build history lists each build with its status and opens it", () => {
  const html = buildsTable(
    look,
    "demo-app",
    [
      { number: 2, result: null, building: true, timestamp: 0 },
      { number: 1, result: "FAILURE", building: false, timestamp: 0 },
    ],
    "en-US",
  );
  assert.match(html, /<svg data-symbol="status-nobuilt-anime">/);
  assert.match(html, /data-action="open-build" data-job="demo-app" data-build="1">#1<\/a><\/td><td>Failed<\/td>/);
});

test("buttons use the Jenkins button classes and carry their action", () => {
  assert.equal(
    button({ label: "Open in Jenkins", action: "open", primary: true }),
    '<button type="button" class="jenkins-button jenkins-button--primary" data-action="open">Open in Jenkins</button>',
  );
  assert.match(button({ label: "Load", action: "load-more", disabled: true }), / disabled>/);
});

test("the app bar wraps its content and controls like l:app-bar", () => {
  assert.equal(
    appBar("<div>c</div>", ["<button></button>"]),
    '<div class="jenkins-app-bar"><div>c</div><div class="jenkins-app-bar__controls"><button></button></div></div>',
  );
});

test("a notice follows the l:notice structure and escapes its text", () => {
  assert.equal(
    notice("Jenkins could not answer", "<b>no job</b>"),
    '<div class="jenkins-notice"><div>Jenkins could not answer</div><div class="jenkins-notice__description">&lt;b&gt;no job&lt;/b&gt;</div></div>',
  );
});

test("escapeHtml turns null into an empty string", () => {
  assert.equal(escapeHtml(null), "");
});

test("a section uses the jenkins-section markup and escapes its title", () => {
  assert.equal(
    section("<Changes>", "<p>body</p>"),
    '<section class="jenkins-section"><h2 class="jenkins-section__title">&lt;Changes&gt;</h2><p>body</p></section>',
  );
});

test("a properties table escapes every value and skips empty ones", () => {
  const html = propertiesTable([
    ["Why", "<b>waiting</b>"],
    ["Blocked", false],
    ["Build", null],
  ]);
  assert.match(html, /<td>Why<\/td><td>&lt;b&gt;waiting&lt;\/b&gt;<\/td>/);
  assert.match(html, /<td>Blocked<\/td><td>false<\/td>/);
  assert.doesNotMatch(html, /Build/);
});

test("a link is a clickable table link with escaped data", () => {
  assert.equal(
    link("<x>", "open-job", { job: 'a"b' }),
    '<a class="jenkins-table__link" role="button" tabindex="0" data-action="open-job" data-job="a&quot;b">&lt;x&gt;</a>',
  );
});

test("a description is escaped and empty descriptions render nothing", () => {
  assert.equal(descriptionBlock("<i>x</i>"), '<div class="jenkins-description">&lt;i&gt;x&lt;/i&gt;</div>');
  assert.equal(descriptionBlock(null), "");
});

test("durations read like Jenkins", () => {
  assert.equal(formatDuration(4200), "4 sec");
  assert.equal(formatDuration(184000), "3 min 4 sec");
  assert.equal(formatDuration(3720000), "1 hr 2 min");
});
