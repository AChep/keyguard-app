#import <Carbon/Carbon.h>
#import <Foundation/Foundation.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#include "main_thread.h"

// Text Input Sources must be read on the AppKit thread. The caller polls, so a
// busy AppKit thread skips this poll instead of stalling it.
static const int64_t kg_keyboard_main_timeout = 250 * NSEC_PER_MSEC;

// Writes the primary language of the selected keyboard input source, such as
// "ru" or "zh-Hans", into the caller's buffer. Returns false, leaving the
// buffer unspecified, when the language is unknown, does not fit, or the
// AppKit thread does not respond in time.
bool kg_keyboard_input_language(char *buffer, size_t capacity) {
    if (buffer == NULL || capacity == 0) {
        return false;
    }

    __block bool result = false;
    const bool ran = kg_run_on_main_within(kg_keyboard_main_timeout, ^{
        TISInputSourceRef source = TISCopyCurrentKeyboardInputSource();
        if (source == NULL) {
            return;
        }

        CFArrayRef languages = TISGetInputSourceProperty(source, kTISPropertyInputSourceLanguages);
        if (languages != NULL
                && CFGetTypeID(languages) == CFArrayGetTypeID()
                && CFArrayGetCount(languages) > 0) {
            CFTypeRef language = CFArrayGetValueAtIndex(languages, 0);
            if (language != NULL && CFGetTypeID(language) == CFStringGetTypeID()) {
                result = CFStringGetCString(
                        (CFStringRef) language,
                        buffer,
                        (CFIndex) capacity,
                        kCFStringEncodingUTF8
                );
            }
        }
        CFRelease(source);
    });
    return ran && result;
}
