//! Inspect Rust paths, preserving production items after inline test modules.

use syn::visit::Visit;

pub(super) fn production_dependencies(source: &str) -> Vec<String> {
    let file = syn::parse_file(source).expect("parse Rust source for layer boundaries");
    let mut visitor = Dependencies::default();
    visitor.visit_file(&file);
    visitor.paths
}

#[derive(Default)]
struct Dependencies {
    paths: Vec<String>,
}

impl Dependencies {
    fn use_tree(&mut self, prefix: &str, tree: &syn::UseTree) {
        match tree {
            syn::UseTree::Path(path) => {
                self.use_tree(&format!("{prefix}{}::", path.ident), &path.tree);
            }
            syn::UseTree::Name(name) => self.paths.push(format!("{prefix}{}", name.ident)),
            syn::UseTree::Rename(rename) => self.paths.push(format!("{prefix}{}", rename.ident)),
            syn::UseTree::Glob(_) => self.paths.push(format!("{prefix}*")),
            syn::UseTree::Group(group) => {
                for tree in &group.items {
                    self.use_tree(prefix, tree);
                }
            }
        }
    }
}

impl<'ast> Visit<'ast> for Dependencies {
    fn visit_item_mod(&mut self, module: &'ast syn::ItemMod) {
        if module.attrs.iter().any(|attribute| {
            attribute.path().is_ident("cfg")
                && attribute
                    .parse_args::<syn::Path>()
                    .is_ok_and(|path| path.is_ident("test"))
        }) {
            return;
        }
        syn::visit::visit_item_mod(self, module);
    }

    fn visit_item_use(&mut self, item: &'ast syn::ItemUse) {
        self.use_tree("", &item.tree);
    }

    fn visit_path(&mut self, path: &'ast syn::Path) {
        self.paths.push(
            path.segments
                .iter()
                .map(|segment| segment.ident.to_string())
                .collect::<Vec<_>>()
                .join("::"),
        );
        syn::visit::visit_path(self, path);
    }

    // Documentation and other attribute payloads are not dependencies.
    fn visit_attribute(&mut self, _: &'ast syn::Attribute) {}
}

#[test]
fn sees_dependencies_after_strings_and_inline_tests() {
    let dependencies = production_dependencies(
        r#"
        fn before() { let url = "https://example.test"; crate::openpgp::mutation::run(); }
        #[cfg(test)]
        mod tests { use crate::protocol::AllowedInTests; }
        use crate::{openpgp::{agent::Signer as Alias, mutation::*}, protocol::Request};
        fn after() { crate::openpgp::message::read(); }
    "#,
    );
    for path in [
        "crate::openpgp::mutation::run",
        "crate::openpgp::agent::Signer",
        "crate::openpgp::mutation::*",
        "crate::protocol::Request",
        "crate::openpgp::message::read",
    ] {
        assert!(
            dependencies.iter().any(|dependency| dependency == path),
            "missing {path}"
        );
    }
    assert!(
        !dependencies
            .iter()
            .any(|path| path.contains("AllowedInTests"))
    );
}

#[test]
fn ignores_dependency_names_in_comments_and_literals() {
    let dependencies = production_dependencies(
        r##"
        // use crate::protocol::Request;
        /* crate::openpgp::mutation::run(); */
        const TEXT: &str = r#"crate::openpgp::agent::Signer"#;
        const OTHER: &str = "prost::Message";
    "##,
    );
    assert!(!dependencies.iter().any(|path| path.contains("protocol")
        || path.contains("openpgp")
        || path.contains("prost")));
}
