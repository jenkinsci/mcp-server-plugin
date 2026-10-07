export function readCallableTools(doc) {
  const names = doc.querySelector('meta[name="jenkins:callable-tools"]')?.getAttribute("content") ?? "";
  return new Set(names.split(" ").filter(Boolean));
}

export function textOf(result) {
  return result?.content?.find((content) => content.type === "text")?.text ?? "";
}

export function valueOf(result) {
  if (result?.isError) {
    throw new Error(textOf(result) || "The Jenkins tool call failed");
  }
  if (result?.structuredContent) {
    return result.structuredContent;
  }
  const parsed = JSON.parse(textOf(result));
  return parsed && typeof parsed === "object" && "result" in parsed ? parsed.result : parsed;
}

export function toolCaller(app, callableTools) {
  return async (name, toolArguments) => {
    if (!callableTools.has(name)) {
      throw new Error(`${name} is not one of the tools this view may call: ${[...callableTools].join(", ") || "none"}`);
    }
    return valueOf(await app.callServerTool({ name, arguments: toolArguments }));
  };
}
