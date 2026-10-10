import {expect} from "@playwright/test";
import {join} from "node:path";
import {replaceEditorContent} from "./editor-actions.js";

export async function verifyEditorIntelligence({page, base, output, modifier}) {
  const project = `${base}/api/projects/demo-jaffle-shop`;
  const tabs = page.getByRole("navigation", {name: "Open files", exact: true});
  const input = page.locator(".file-editor-pane:not([hidden]) .cm-content");
  const documentation = page.getByRole("region", {name: "Editor documentation", exact: true});
  const settings = page.getByRole("button", {name: "Editor settings", exact: true});
  const mode = page.getByLabel("EDN editing mode", {exact: true});
  const rainbow = page.getByLabel("Rainbow EDN delimiters", {exact: true});
  const sqlPath = "models/editor_assistance.sql";
  const ednPath = "models/editor_assistance.edn";
  const sql = "SELECT o.amount\nFROM {{ ref('orders') }} AS o";
  const edn = "{:select [:o.amount]\n :from [[:orders :o]]}";

  for (const [path, content] of [
    [sqlPath, sql], [ednPath, edn], ["models/editor_parinfer.edn", ""],
    ["macros/editor_money.sql", "{% macro editor_money(amount) %}\n  round({{ amount }}, 2)\n{% endmacro %}"],
    ["models/editor_assistance.yml", "version: 2\nmacros:\n  - name: editor_money\n    description: Round a monetary expression to two decimal places.\n    arguments:\n      - name: amount\n        type: numeric\n        description: The amount expression.\nsources:\n  - name: raw_editor\n    tables:\n      - name: events\n        columns:\n          - name: event_id\n            data_type: BIGINT\n            description: The event identifier.\n"]
  ]) {
    const response = await page.request.post(`${project}/files`, {data: {path, content}});
    expect(response.status(), await response.text()).toBe(201);
  }
  const context = await (await page.request.get(`${project}/editor-context`)).json();
  expect(context.resources.find(resource => resource.name === "editor_money").arguments[0].type).toBe("numeric");
  const refreshed = page.waitForResponse(response => response.url().endsWith("/editor-context") && response.request().method() === "GET");
  await page.getByRole("button", {name: "Refresh project", exact: true}).click();
  await refreshed;

  async function openFile(path) {
    await input.press(`${modifier}+p`);
    const search = page.getByLabel("Search commands", {exact: true});
    await search.fill(path);
    await search.press("Enter");
    await expect(tabs.locator(".file-tab.active")).toContainText(path.split("/").at(-1));
  }
  async function putCursor(marked) {
    const offset = marked.indexOf("|");
    const text = marked.slice(0, offset) + marked.slice(offset + 1);
    await replaceEditorContent(page, input, text, modifier);
    await input.press(`${modifier}+Home`);
    const lines = text.slice(0, offset).split("\n");
    for (let line = 1; line < lines.length; line += 1) await input.press("ArrowDown");
    await input.press("Home");
    for (let column = 0; column < lines.at(-1).length; column += 1) await input.press("ArrowRight");
  }
  async function complete(marked, expected) {
    await putCursor(marked);
    await input.press("Control+Space");
    const option = page.getByRole("option").filter({hasText: expected});
    await expect(option.first()).toBeVisible();
    return option;
  }
  async function checkDocumentationBounds(width) {
    await page.setViewportSize({width, height: 1024});
    await input.press("Control+Shift+Space");
    await expect(documentation).toBeVisible();
    await expect.poll(async () => (await documentation.boundingBox())?.x ?? -1).toBeGreaterThanOrEqual(0);
    await expect.poll(async () => (await documentation.boundingBox())?.y ?? -1).toBeGreaterThanOrEqual(0);
    const box = await documentation.boundingBox();
    expect(box.x).toBeGreaterThanOrEqual(0);
    expect(box.y).toBeGreaterThanOrEqual(0);
    expect(box.x + box.width).toBeLessThanOrEqual(width);
    expect(box.y + box.height).toBeLessThanOrEqual(1024);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
    await input.press("Escape");
  }

  await openFile(sqlPath);
  const amount = await complete("SELECT o.am|\nFROM {{ ref('orders') }} AS o", "amount");
  await amount.first().click();
  await expect(input).toHaveText(sql, {useInnerText: true});
  await input.press("Control+Shift+Space");
  await expect(documentation).toContainText("Total amount (AUD)");
  await expect(documentation).toContainText("orders.amount");
  await input.press("Escape");
  await putCursor("SELECT {{ editor_mo| }}");
  await input.press("Control+Space");
  await expect(page.getByRole("option").filter({hasText: "editor_money"}).first()).toBeVisible();
  await input.press("Escape");
  await putCursor("SELECT {{ editor_mo|ney('amount') }}");
  await input.press("Control+Shift+Space");
  await expect(documentation).toContainText("Round a monetary expression");
  await expect(documentation).toContainText("amount (numeric)");
  await input.press("Escape");
  await input.press("F12");
  await expect(tabs.locator(".file-tab.active")).toContainText("editor_money.sql");
  await expect(input).toContainText("{% macro editor_money(amount) %}");

  await tabs.getByRole("button", {name: "editor_assistance.sql", exact: true}).click();
  await complete("SELECT * FROM {{ ref('ord|') }}", "orders");
  await input.press("Escape");
  await complete("SELECT * FROM {{ source('raw_editor', 'ev|') }}", "events");
  await input.press("Escape");
  await putCursor("SELECT * FROM {{ ref('ord|ers') }}");
  await input.locator(".syntax-template-string").click({modifiers: [modifier]});
  await expect(tabs.locator(".file-tab.active")).toContainText("orders.sql");
  await tabs.getByRole("button", {name: "editor_assistance.sql", exact: true}).click();
  await putCursor("SELECT {{ re|f('orders') }}");
  await input.press("Control+Shift+Space");
  await expect(documentation).toContainText("ref(");
  await expect(documentation.getByRole("link", {name: "Reference documentation"})).toHaveAttribute("href", "https://docs.getdbt.com/reference/dbt-jinja-functions/ref");
  await page.screenshot({path: join(output, "sql-reference-help.png"), fullPage: true});
  await input.press("Escape");
  await checkDocumentationBounds(390);
  await checkDocumentationBounds(1536);

  await openFile(ednPath);
  await expect(input).toHaveText(edn, {useInnerText: true});
  await settings.click();
  await expect(mode).toHaveValue("paredit");
  await expect(rainbow).not.toBeChecked();
  await rainbow.check();
  await settings.click();
  await expect(page.getByRole("dialog", {name: "Editor settings", exact: true})).toBeHidden();
  await expect(input.locator(".edn-bracket-0")).not.toHaveCount(0);
  await expect(input.locator(".edn-bracket-2")).not.toHaveCount(0);
  await complete("{:select [:o.am|]\n :from [[:orders :o]]}", "amount");
  await page.getByRole("option").filter({hasText: "amount"}).first().click();
  await expect(input).toHaveText(edn, {useInnerText: true});
  await input.press("Control+Shift+Space");
  await expect(documentation).toContainText("orders.amount");
  await page.screenshot({path: join(output, "edn-reference-help.png"), fullPage: true});
  await input.press("Escape");
  await page.getByLabel("Theme", {exact: true}).selectOption("light");
  await input.press("Control+Shift+Space");
  await expect(documentation).toBeVisible();
  await page.screenshot({path: join(output, "edn-reference-light.png"), fullPage: true});
  await input.press("Escape");
  await page.getByLabel("Theme", {exact: true}).selectOption("dark");

  await putCursor("[:al|pha] :beta");
  await input.press("Alt+Shift+ArrowUp");
  expect(await page.evaluate(() => window.getSelection().toString())).toBe(":alpha");
  await input.press("Alt+Shift+ArrowDown");
  expect(await page.evaluate(() => window.getSelection().toString())).toBe("");
  await input.press("Control+Alt+ArrowRight");
  await expect(input).toHaveText("[:alpha :beta]", {useInnerText: true});
  await input.press("Control+Alt+ArrowLeft");
  expect((await input.innerText()).replaceAll(/\s+/g, " ").trim()).toBe("[:alpha] :beta");
  await input.press(`${modifier}+z`);
  await expect(input).toHaveText("[:alpha :beta]", {useInnerText: true});

  await settings.click();
  await mode.selectOption("parinfer");
  await settings.click();
  await expect(input).toHaveText("[:alpha :beta]", {useInnerText: true});
  await input.press(`${modifier}+z`);
  expect((await input.innerText()).replaceAll(/\s+/g, " ").trim()).toBe("[:alpha] :beta");
  await openFile("models/editor_parinfer.edn");
  await expect(input).toHaveText("", {useInnerText: true});
  await page.keyboard.type("[");
  await expect(input).toHaveText("[]", {useInnerText: true});
  await input.press(`${modifier}+z`);
  await expect(input).toHaveText("", {useInnerText: true});
  await settings.click();
  await mode.selectOption("plain");
  await settings.click();
  await input.click();
  await page.keyboard.type("[");
  await expect(input).toHaveText("[", {useInnerText: true});
  await settings.click();
  await mode.selectOption("parinfer");
  await settings.click();
  await expect(input).toHaveText("[", {useInnerText: true});
  await replaceEditorContent(page, input, edn, modifier);
  await input.press(`${modifier}+s`);
  await expect(page.getByRole("button", {name: "Save file", exact: true})).toBeDisabled();
  await page.reload();
  await expect(page.getByLabel("Target", {exact: true})).toBeVisible();
  await settings.click();
  await expect(mode).toHaveValue("parinfer");
  await expect(rainbow).toBeChecked();
  await settings.click();
  await openFile(ednPath);
  await expect(input).toHaveText(edn, {useInnerText: true});
  await expect(input.locator(".edn-bracket-0")).not.toHaveCount(0);
}
