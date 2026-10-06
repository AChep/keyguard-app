#import <AppKit/AppKit.h>
#import <ApplicationServices/ApplicationServices.h>
#include <stdbool.h>
#include <stdint.h>

#include "main_thread.h"

// Only capture and activation need the AppKit thread, and both are bounded: a
// stalled thread fails the call instead of blocking the login indefinitely.
// NSRunningApplication and the Accessibility API are thread-safe, so the
// probes that Rust polls while typing run on the calling thread.
static const int64_t kg_autotype_main_timeout = NSEC_PER_SEC;

@interface KGAutotypeTarget : NSObject
@property(nonatomic, strong) NSRunningApplication *app;
// The AX application element the focus probe queries, retained for the lifetime of the target.
@property(nonatomic, assign) AXUIElementRef element;
@property(nonatomic, assign) AXUIElementRef window;
@end
@implementation KGAutotypeTarget
- (void)dealloc {
    if (_element) CFRelease(_element);
    if (_window) CFRelease(_window);
}
@end

// Copies the window that has keyboard focus inside the application, or NULL.
static AXUIElementRef kg_autotype_copy_focused_window(AXUIElementRef application) {
    CFTypeRef window = NULL;
    AXError error = AXUIElementCopyAttributeValue(application, kAXFocusedWindowAttribute, &window);
    if (error != kAXErrorSuccess || !window) return NULL;
    if (CFGetTypeID(window) != AXUIElementGetTypeID()) {
        CFRelease(window);
        return NULL;
    }
    return (AXUIElementRef)window;
}

// Prompts for Accessibility access when it has not been granted. The system
// shows the prompt, so this is safe on any thread.
bool kg_autotype_permission(void) {
    @autoreleasepool {
        NSDictionary *options = @{(__bridge NSString *)kAXTrustedCheckOptionPrompt: @YES};
        return AXIsProcessTrustedWithOptions((__bridge CFDictionaryRef)options);
    }
}

void *kg_autotype_capture(void) {
    __block void *result = NULL;
    kg_run_on_main_within(kg_autotype_main_timeout, ^{
        if (!AXIsProcessTrusted()) return;
        NSRunningApplication *app = NSWorkspace.sharedWorkspace.frontmostApplication;
        if (!app || app.processIdentifier == NSProcessInfo.processInfo.processIdentifier) return;
        AXUIElementRef element = AXUIElementCreateApplication(app.processIdentifier);
        AXUIElementSetMessagingTimeout(element, 0.2);
        AXUIElementRef window = kg_autotype_copy_focused_window(element);
        if (!window) {
            CFRelease(element);
            return;
        }
        AXUIElementSetMessagingTimeout(window, 0.2);
        KGAutotypeTarget *target = [KGAutotypeTarget new];
        target.app = app;
        target.element = element;
        target.window = window;
        result = (__bridge_retained void *)target;
    });
    return result;
}

void kg_autotype_release(void *handle) {
    id target = (__bridge_transfer id)handle;
    (void)target;
}

static bool kg_autotype_available(KGAutotypeTarget *target) {
    return AXIsProcessTrusted() && !target.app.terminated;
}

bool kg_autotype_activate(void *handle) {
    __block bool result = false;
    kg_run_on_main_within(kg_autotype_main_timeout, ^{
        KGAutotypeTarget *target = (__bridge KGAutotypeTarget *)handle;
        if (!kg_autotype_available(target)) return;
        // Either request may be refused, for example when the target is already
        // active. They are hints; the caller confirms focus with kg_autotype_focused.
        (void)AXUIElementPerformAction(target.window, kAXRaiseAction);
        if (@available(macOS 14.0, *)) {
            [NSApp yieldActivationToApplication:target.app];
            (void)[target.app activateFromApplication:NSRunningApplication.currentApplication options:0];
        } else {
            (void)[target.app activateWithOptions:0];
        }
        result = true;
    });
    return result;
}

// Typing may rename the destination (for example, an edited document), so
// input is bound to the retained AX window and its owner, never to a title.
bool kg_autotype_focused(void *handle) {
    @autoreleasepool {
        KGAutotypeTarget *target = (__bridge KGAutotypeTarget *)handle;
        if (!kg_autotype_available(target) || !target.app.active) return false;
        AXUIElementRef window = kg_autotype_copy_focused_window(target.element);
        bool result = window && CFEqual(window, target.window);
        if (window) CFRelease(window);
        return result;
    }
}

bool kg_autotype_keys_released(void) {
    for (CGKeyCode key = 0; key < 128; key++) {
        // Caps Lock is a toggle; Unicode input does not depend on it.
        if (key != 0x39 && CGEventSourceKeyState(kCGEventSourceStateHIDSystemState, key)) return false;
    }
    for (CGMouseButton button = 0; button < 32; button++) {
        if (CGEventSourceButtonState(kCGEventSourceStateHIDSystemState, button)) return false;
    }
    return true;
}

// Rust confirms a passing focus probe, which includes the Accessibility check,
// at most one poll interval before every character.
bool kg_autotype_character(const uint16_t *units, size_t count, bool tab) {
    if (!units || count == 0 || count > 2) return false;
    CGEventRef down = CGEventCreateKeyboardEvent(NULL, tab ? 0x30 : 0, true);
    CGEventRef up = CGEventCreateKeyboardEvent(NULL, tab ? 0x30 : 0, false);
    if (!down || !up) {
        if (down) CFRelease(down);
        if (up) CFRelease(up);
        return false;
    }
    CGEventSetFlags(down, 0);
    CGEventSetFlags(up, 0);
    if (!tab) {
        CGEventKeyboardSetUnicodeString(down, count, units);
        CGEventKeyboardSetUnicodeString(up, count, units);
    }
    CGEventPost(kCGSessionEventTap, down);
    CGEventPost(kCGSessionEventTap, up);
    CFRelease(down);
    CFRelease(up);
    return true;
}
