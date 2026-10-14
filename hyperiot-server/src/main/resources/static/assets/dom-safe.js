// Element.replaceChildren() turns null / undefined / false arguments into the text "null" / "undefined" / "false".
// The page builders pass conditional children (`cond && node`), so drop empty values the same way h() does.
const replaceChildren = Element.prototype.replaceChildren;
Element.prototype.replaceChildren = function safeReplaceChildren(...nodes) {
  return replaceChildren.apply(this, nodes.filter((n) => n != null && n !== false));
};
