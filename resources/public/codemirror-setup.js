// CodeMirror 6 Setup for Global Access
import { EditorView, keymap } from "@codemirror/view";
import { EditorState } from "@codemirror/state";
import { basicSetup } from "@codemirror/basic-setup";
import { markdown } from "@codemirror/lang-markdown";
import * as commands from "@codemirror/commands";

// Make CodeMirror available globally
window.CodeMirror = {
  view: { EditorView, keymap },
  state: { EditorState },
  basicSetup,
  langMarkdown: markdown,
  commands
};

console.log("CodeMirror 6 is now available globally:", !!window.CodeMirror);