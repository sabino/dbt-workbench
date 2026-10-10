import {expect} from "@playwright/test";

export async function replaceEditorContent(page, editor, content, modifier) {
  // Keyboard selection updates CodeMirror's model before inserting replacement text.
  await editor.press(`${modifier}+a`);
  await page.keyboard.insertText(content);
  await expect(editor).toHaveText(content, {useInnerText: true});
}
