import { visit } from 'unist-util-visit';

// Callouts in the Starlight syntax, parsed by remark-directive:
//
//   :::note
//   Text.
//   :::
//
// Collapsed blocks use the same syntax, with the summary as the label:
//
//   :::details[Summary]
//   Text.
//   :::
//
// Only `note`, `caution` and `details` exist (see CONTRIBUTING.md); any other
// container fails the build. remark-directive also parses `:name` and `::name`
// inside ordinary text, such as "Settings:Developer", so those are put back as
// the text they were written as.
const CALLOUTS = {
  note: 'Note',
  caution: 'Caution',
};

export default function remarkAsides() {
  return (tree, file) => {
    visit(tree, (node, index, parent) => {
      if (node.type === 'containerDirective' && node.name === 'details') {
        const label = node.children[0];
        if (!label?.data?.directiveLabel || Object.keys(node.attributes ?? {}).length > 0) {
          file.fail('Details take a summary label and no attributes: ":::details[Summary]".', node);
        }
        node.data = {
          hName: 'details',
          hProperties: { className: ['details'] },
        };
        label.data = { ...label.data, hName: 'summary' };
        return;
      }
      if (node.type === 'containerDirective') {
        const title = CALLOUTS[node.name];
        if (!title) {
          file.fail(`Unknown callout ":::${node.name}". Use :::note, :::caution or :::details.`, node);
        }
        if (node.children[0]?.data?.directiveLabel || Object.keys(node.attributes ?? {}).length > 0) {
          file.fail(`Callouts take no label or attributes: ":::${node.name}".`, node);
        }
        node.data = {
          hName: 'aside',
          hProperties: { className: ['callout', `callout-${node.name}`] },
        };
        node.children.unshift({
          type: 'paragraph',
          data: { hProperties: { className: ['callout-title'] } },
          children: [{ type: 'text', value: title }],
        });
        return;
      }
      if ((node.type === 'textDirective' || node.type === 'leafDirective') && parent && index !== undefined) {
        const source = String(file.value).slice(node.position.start.offset, node.position.end.offset);
        const text = { type: 'text', value: source };
        parent.children[index] = node.type === 'leafDirective' ? { type: 'paragraph', children: [text] } : text;
      }
    });
  };
}
