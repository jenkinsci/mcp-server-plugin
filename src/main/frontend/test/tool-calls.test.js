import assert from "node:assert/strict";
import { test } from "node:test";
import { readCallableTools, textOf, toolCaller, valueOf } from "../kit/tool-calls.js";

const page = (content) => ({
  querySelector: () => (content == null ? null : { getAttribute: () => content }),
});

const textResult = (value) => ({ content: [{ type: "text", text: JSON.stringify(value) }] });

test("the callable tools are read from the page", () => {
  assert.deepEqual([...readCallableTools(page("getBuildLog getBuild"))], ["getBuildLog", "getBuild"]);
  assert.equal(readCallableTools(page(null)).size, 0);
});

test("the value of a tool result is the result Jenkins returned", () => {
  const result = textResult({ status: "COMPLETED", message: "Data retrieved successfully.", result: { number: 3 } });

  assert.equal(textOf(result), JSON.stringify({ status: "COMPLETED", message: "Data retrieved successfully.", result: { number: 3 } }));
  assert.deepEqual(valueOf(result), { number: 3 });
  assert.deepEqual(valueOf({ structuredContent: { lines: [] } }), { lines: [] });
});

test("a tool error is thrown with the message Jenkins wrote", () => {
  assert.throws(() => valueOf({ isError: true, content: [{ type: "text", text: "Item/Read is missing" }] }), /Item\/Read is missing/);
});

test("a view can only call the tools its page lists", async () => {
  const calls = [];
  const app = {
    callServerTool: async (params) => {
      calls.push(params);
      return textResult({ result: { number: 1 } });
    },
  };
  const callJenkins = toolCaller(app, new Set(["getBuild"]));

  await assert.rejects(() => callJenkins("triggerBuild", { jobFullName: "a" }), /triggerBuild is not one of the tools/);
  assert.deepEqual(await callJenkins("getBuild", { jobFullName: "a" }), { number: 1 });
  assert.deepEqual(calls, [{ name: "getBuild", arguments: { jobFullName: "a" } }]);
});
