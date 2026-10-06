import SwiftUI
import KeyguardShared
#if os(iOS)
import UIKit
#endif

struct GeneratorWorkspaceView: View {
    @Environment(FilePickerModel.self) private var filePickerModel
    let generator: GeneratorSnapshot
    let actions: GeneratorActions
    @State private var editing = GeneratorEditingState()
    @State private var layout = GeneratorWorkspaceLayout.compact
    @State private var rootVisible = false
    #if os(iOS)
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @ScaledMetric(relativeTo: .body) private var breakpoint = GeneratorWorkspaceLayout.breakpoint
    #endif

    private var isPad: Bool {
        #if os(iOS)
        UIDevice.current.userInterfaceIdiom == .pad
        #else
        false
        #endif
    }

    #if os(macOS)
    private var optionsToolbar: GeneratorOptionsToolbarState { GeneratorOptionsToolbarState(snapshot: generator) }
    #endif

    var body: some View {
        #if os(iOS)
        let useAdaptiveLayout = isPad
        let isRegular = horizontalSizeClass == .regular
        let isAccessibilitySize = dynamicTypeSize.isAccessibilitySize
        let textScale = breakpoint / GeneratorWorkspaceLayout.breakpoint
        #endif
        // The generator tools are pushed through the shared Kotlin nav stack, so the
        // section hosts a `NavStackContainer` instead of a plain `NavigationStack`.
        NavStackContainer(scope: "generator") {
            Group {
                // `loaded` describes each value-generation operation, including
                // those triggered by typing. Keep the form mounted during these
                // operations so its text fields retain their buffers and focus.
                if generator.types.isEmpty {
                    LoadingIndicator()
                } else {
                    GeneratorContentView(
                        generator: generator,
                        actions: actions,
                        editing: isPad ? editing : nil,
                        layout: layout
                    )
                }
            }
            #if os(iOS)
            .onGeometryChange(for: GeneratorWorkspaceLayout.self) { geometry in
                GeneratorWorkspaceLayout(
                    width: geometry.size.width,
                    isPad: useAdaptiveLayout,
                    isRegular: isRegular,
                    isAccessibilitySize: isAccessibilitySize,
                    textScale: textScale
                )
            } action: { newLayout in
                if newLayout.isWide != layout.isWide { editing.prepareForRemount() }
                layout = newLayout
            }
            .background {
                if isPad, rootVisible {
                    Button(L10n.generatorRegenerateButton) {
                        actions.invoke("value:refresh")
                    }
                    .keyboardShortcut("r", modifiers: .command)
                    .disabled(generator.value?.canRefresh != true)
                    .hidden()
                    .accessibilityHidden(true)
                }
            }
            #endif
            .navigationTitle(L10n.generatorHeaderTitle)
            #if os(iOS)
            // Keep the title out of the independently scrolling columns. Restore
            // the large title explicitly when resizing back to a single form.
            .toolbarTitleDisplayMode(isPad ? (layout.isWide ? .inline : .large) : .automatic)
            #endif
            .toolbar { generatorToolbar }
            .onAppear { rootVisible = true }
            .onDisappear {
                rootVisible = false
                editing.endFocus()
            }
            .onChange(of: generator.types.first(where: \.selected)?.id) { _, _ in
                editing.reset()
            }
            .onChange(of: generator.filters.map(\.key)) { _, keys in
                editing.retain(keys: Set(keys))
            }
        }
        .sheet(
            item: Binding(
                get: { filePickerModel.pendingDatePicker.flatMap { $0.request.presentsInAddForm ? nil : $0 } },
                set: { if $0 == nil { filePickerModel.cancelDatePicker() } }
            )
        ) { pending in
            DatePickerSheet(request: pending.request)
        }
        // The "create login / SSH key" AddRoute is presented at the root
        // (`RootContainer`) so it works from any screen, not just here.
    }

    // MARK: - Toolbar

    #if os(macOS)
    @ToolbarContentBuilder
    private var generatorToolbar: some CustomizableToolbarContent {
        let toolbar = optionsToolbar
        ToolbarItem(id: "generator.refresh") {
            GeneratorRefreshToolbarButton(canRefresh: generator.value?.canRefresh == true, invoke: actions.invoke)
        }
        if toolbar.canOpenHistory
            || !toolbar.options.isEmpty
        {
            ToolbarItem(id: "generator.more") {
                GeneratorOptionsToolbarButton(
                    options: toolbar.options,
                    canOpenHistory: toolbar.canOpenHistory, invoke: actions.invoke
                )
            }
        }
    }
    #else
    @ToolbarContentBuilder
    private var generatorToolbar: some ToolbarContent {
        if layout.isWide {
            if generator.canOpenHistory {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(L10n.generatorhistoryHeaderTitle, systemImage: "clock.arrow.circlepath") {
                        actions.invoke("history")
                    }
                    .help(L10n.generatorhistoryHeaderTitle)
                }
            }
        } else {
            ToolbarItem(placement: .topBarTrailing) {
                GeneratorRefreshToolbarButton(canRefresh: generator.value?.canRefresh == true, invoke: actions.invoke)
            }
        }
        ToolbarItem(placement: .topBarTrailing) {
            if (!layout.isWide && generator.canOpenHistory) || !generator.options.isEmpty {
                GeneratorOptionsToolbarButton(
                    options: generator.options,
                    canOpenHistory: !layout.isWide && generator.canOpenHistory, invoke: actions.invoke
                )
            }
        }
    }
    #endif

}
