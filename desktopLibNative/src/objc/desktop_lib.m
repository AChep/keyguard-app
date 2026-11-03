#import <ApplicationServices/ApplicationServices.h>
#import <AppKit/AppKit.h>
#import <Carbon/Carbon.h>
#import <Foundation/Foundation.h>
#import <LocalAuthentication/LocalAuthentication.h>
#import <Security/Security.h>
#import <UserNotifications/UserNotifications.h>
#import <objc/objc.h>
#import <dispatch/dispatch.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>

#include "main_thread.h"

typedef void (*kg_biometrics_callback_t)(int32_t status, const char *error);
typedef void (*kg_hotkey_callback_t)(int32_t hotkey_id);
typedef void (*kg_power_callback_t)(int32_t event);

// Mirrors `REGISTER_STATUS_INTERNAL_ERROR` in `ffi.rs`.
static const int32_t KGNativeInternalError = -5;

@interface KGPowerObserver : NSObject
@property(nonatomic, assign) kg_power_callback_t callback;
@property(nonatomic, strong) NSMutableArray<id> *tokens;
@end

@implementation KGPowerObserver
@end

static NSMutableDictionary<NSNumber *, KGPowerObserver *> *kg_power_registry(void) {
    static NSMutableDictionary<NSNumber *, KGPowerObserver *> *registry;
    static dispatch_once_t once_token;
    dispatch_once(&once_token, ^{
        registry = [[NSMutableDictionary alloc] init];
    });
    return registry;
}

static int32_t kg_power_next_id = 1;

int32_t kg_register_native_power_events(kg_power_callback_t callback) {
    if (callback == NULL) {
        return KGNativeInternalError;
    }
    __block int32_t result = KGNativeInternalError;
    kg_run_on_main(^{
        KGPowerObserver *entry = [[KGPowerObserver alloc] init];
        entry.callback = callback;
        entry.tokens = [[NSMutableArray alloc] init];
        NSNotificationCenter *center = NSWorkspace.sharedWorkspace.notificationCenter;
        // Matches DesktopPowerEvent in the JVM bridge. Use synchronous
        // delivery: the will-sleep notification must wait for the lock.
        NSArray<NSNotificationName> *names = @[
            NSWorkspaceScreensDidSleepNotification,
            NSWorkspaceScreensDidWakeNotification,
            NSWorkspaceWillSleepNotification,
            NSWorkspaceDidWakeNotification,
        ];
        for (NSUInteger index = 0; index < names.count; index++) {
            const int32_t event = (int32_t)index + 1;
            id token = [center addObserverForName:names[index]
                                          object:nil
                                           queue:nil
                                      usingBlock:^(NSNotification *_note) {
                (void)_note;
                kg_run_on_main(^{
                    if (entry.callback != NULL) {
                        entry.callback(event);
                    }
                });
            }];
            [entry.tokens addObject:token];
        }
        const int32_t registration_id = kg_power_next_id++;
        kg_power_registry()[@(registration_id)] = entry;
        result = registration_id;
    });
    return result;
}

bool kg_unregister_native_power_events(int32_t registration_id) {
    __block bool result = false;
    kg_run_on_main(^{
        NSNumber *key = @(registration_id);
        KGPowerObserver *entry = kg_power_registry()[key];
        if (entry == nil) {
            return;
        }
        entry.callback = NULL;
        NSNotificationCenter *center = NSWorkspace.sharedWorkspace.notificationCenter;
        for (id token in entry.tokens) {
            [center removeObserver:token];
        }
        [entry.tokens removeAllObjects];
        [kg_power_registry() removeObjectForKey:key];
        result = true;
    });
    return result;
}

static NSString *const KGAccountName = @"com.artemchep.keyguard";

// Mirrors `ChallengeStatus` in `biometrics.rs`.
static const int32_t KGBiometricsStatusSuccess = 0;
static const int32_t KGBiometricsStatusUserCanceled = 1;
static const int32_t KGBiometricsStatusSecurityDeviceLocked = 3;
static const int32_t KGBiometricsStatusUnavailable = 4;
static const int32_t KGBiometricsStatusUserPrefersPassword = 5;
static const int32_t KGBiometricsStatusUnknown = 6;
static const OSType KGHotKeySignature = 'KGHK';
static const int32_t KGHotKeyUnavailable = -4;

@interface KGHotKeyEntry : NSObject
@property(nonatomic, assign) EventHotKeyRef ref;
@property(nonatomic, assign) kg_hotkey_callback_t callback;
@property(nonatomic, assign, getter=isPressed) BOOL pressed;
@property(nonatomic, assign) uint32_t keyCode;
@property(nonatomic, assign) uint32_t modifiers;
// Zero for keys registered by a fixed key code.
@property(nonatomic, assign) unichar character;
@property(nonatomic, assign) uint32_t fallbackKeyCode;
@end

@implementation KGHotKeyEntry
@end

static void kg_log_keychain_error(NSString *prefix, OSStatus status) {
    NSString *message = CFBridgingRelease(SecCopyErrorMessageString(status, NULL));
    if (message == nil) {
        message = [NSString stringWithFormat:@"Keychain error: %d", (int)status];
    }

    NSLog(@"%@: %@", prefix, message);
}

static NSString *kg_string_from_utf8(const char *value) {
    if (value == NULL) {
        return nil;
    }

    return [NSString stringWithUTF8String:value];
}

static uint8_t kg_color_component_to_byte(CGFloat component) {
    if (!isfinite(component) || component <= 0.0) {
        return 0;
    }
    if (component >= 1.0) {
        return 255;
    }

    return (uint8_t)(component * 255.0 + 0.5);
}

int32_t kg_get_system_accent_color(void) {
    __block int32_t result = 0;
    void (^block)(void) = ^{
        if (![NSColor respondsToSelector:@selector(controlAccentColor)]) {
            result = 0;
            return;
        }

        NSColor *accentColor = [NSColor controlAccentColor];
        NSColor *rgbColor = [accentColor colorUsingColorSpace:[NSColorSpace sRGBColorSpace]];
        if (rgbColor == nil) {
            result = 0;
            return;
        }

        CGFloat red = 0.0;
        CGFloat green = 0.0;
        CGFloat blue = 0.0;
        CGFloat alpha = 0.0;
        [rgbColor getRed:&red green:&green blue:&blue alpha:&alpha];

        uint32_t argb = 0xFF000000u;
        argb |= ((uint32_t)kg_color_component_to_byte(red)) << 16;
        argb |= ((uint32_t)kg_color_component_to_byte(green)) << 8;
        argb |= (uint32_t)kg_color_component_to_byte(blue);
        result = (int32_t)argb;
    };

    kg_run_on_main(block);

    return result;
}

static NSMutableDictionary<NSNumber *, KGHotKeyEntry *> *kg_hotkey_registry(void) {
    static NSMutableDictionary<NSNumber *, KGHotKeyEntry *> *registry = nil;
    static dispatch_once_t once_token;
    dispatch_once(&once_token, ^{
        registry = [[NSMutableDictionary alloc] init];
    });
    return registry;
}

static EventHandlerRef kg_hotkey_event_handler = NULL;
static int32_t kg_hotkey_next_id = 1;

static OSStatus kg_on_hotkey_event(
        EventHandlerCallRef _next_handler,
        EventRef event,
        void *_user_data
) {
    (void) _next_handler;
    (void) _user_data;

    if (GetEventClass(event) != kEventClassKeyboard) {
        return noErr;
    }

    const UInt32 event_kind = GetEventKind(event);
    if (event_kind != kEventHotKeyPressed && event_kind != kEventHotKeyReleased) {
        return noErr;
    }

    EventHotKeyID hotkey_id;
    const OSStatus status = GetEventParameter(
            event,
            kEventParamDirectObject,
            typeEventHotKeyID,
            NULL,
            sizeof(hotkey_id),
            NULL,
            &hotkey_id
    );
    if (status != noErr) {
        return status;
    }

    if (hotkey_id.signature != KGHotKeySignature) {
        return noErr;
    }

    KGHotKeyEntry *entry = kg_hotkey_registry()[@((int32_t) hotkey_id.id)];
    if (entry == nil) {
        return noErr;
    }

    if (event_kind == kEventHotKeyReleased) {
        entry.pressed = NO;
        return noErr;
    }

    if (!entry.isPressed && entry.callback != NULL) {
        entry.pressed = YES;
        entry.callback((int32_t) hotkey_id.id);
    }

    return noErr;
}

static bool kg_install_hotkey_handler(void) {
    if (kg_hotkey_event_handler != NULL) {
        return true;
    }

    const EventTypeSpec specs[] = {
            {
            .eventClass = kEventClassKeyboard,
            .eventKind = kEventHotKeyPressed,
            },
            {
                    .eventClass = kEventClassKeyboard,
                    .eventKind = kEventHotKeyReleased,
            },
    };
    const OSStatus status = InstallApplicationEventHandler(
            &kg_on_hotkey_event,
            2,
            specs,
            NULL,
            &kg_hotkey_event_handler
    );
    if (status != noErr) {
        NSLog(@"HotKey Register: Failed to install event handler: %d", (int) status);
        return false;
    }

    return true;
}

static unichar kg_ascii_lowercase(unichar character) {
    return character >= 'A' && character <= 'Z' ? (unichar) (character + ('a' - 'A')) : character;
}

// Letters and digits arrive as (character << 16) | fallback key code. Carbon
// matches physical key codes, so find the key that types the character in the
// current Latin layout; the recorder captured that key label. Like Chromium's
// layout-aware global shortcuts. Only the main key block is searched, so a
// digit never resolves to the keypad. The layout is NULL when unavailable.
static uint32_t kg_hotkey_key_code_in_layout(
        const UCKeyboardLayout *layout,
        unichar character,
        uint32_t fallback
) {
    if (layout == NULL) {
        return fallback;
    }

    const unichar target = kg_ascii_lowercase(character);
    for (UInt16 key_code = 0; key_code < kVK_F17; key_code++) {
        UInt32 dead_key_state = 0;
        UniChar chars[4];
        UniCharCount length = 0;
        const OSStatus status = UCKeyTranslate(
                layout,
                key_code,
                kUCKeyActionDown,
                0,
                LMGetKbdType(),
                kUCKeyTranslateNoDeadKeysMask,
                &dead_key_state,
                sizeof(chars) / sizeof(chars[0]),
                &length,
                chars
        );
        if (status == noErr && length == 1 && kg_ascii_lowercase(chars[0]) == target) {
            return key_code;
        }
    }
    return fallback;
}

// The returned layout is owned by the source and lives until it is released.
static const UCKeyboardLayout *kg_hotkey_layout_of(TISInputSourceRef source) {
    if (source == NULL) {
        return NULL;
    }

    CFDataRef data = TISGetInputSourceProperty(source, kTISPropertyUnicodeKeyLayoutData);
    return data == NULL ? NULL : (const UCKeyboardLayout *) CFDataGetBytePtr(data);
}

static uint32_t kg_hotkey_key_code_for_character(unichar character, uint32_t fallback) {
    TISInputSourceRef source = TISCopyCurrentASCIICapableKeyboardLayoutInputSource();
    const uint32_t result =
            kg_hotkey_key_code_in_layout(kg_hotkey_layout_of(source), character, fallback);
    if (source != NULL) {
        CFRelease(source);
    }
    return result;
}

static OSStatus kg_register_event_hotkey(
        int32_t hotkey_id,
        uint32_t key_code,
        uint32_t modifiers,
        EventHotKeyRef *hotkey_ref
) {
    const EventHotKeyID event_hotkey_id = {
            .signature = KGHotKeySignature,
            .id = (UInt32) hotkey_id,
    };
    return RegisterEventHotKey(
            key_code,
            modifiers,
            event_hotkey_id,
            GetApplicationEventTarget(),
            0,
            hotkey_ref
    );
}

// Runs on the main thread. Moves character shortcuts to the keys that type
// their characters in the newly selected layout.
static void kg_hotkey_refresh_layout(void) {
    TISInputSourceRef source = TISCopyCurrentASCIICapableKeyboardLayoutInputSource();
    const UCKeyboardLayout *layout = kg_hotkey_layout_of(source);
    [kg_hotkey_registry() enumerateKeysAndObjectsUsingBlock:^(
            NSNumber *key,
            KGHotKeyEntry *entry,
            BOOL *stop
    ) {
        (void) stop;
        if (entry.character == 0) {
            return;
        }
        const uint32_t key_code =
                kg_hotkey_key_code_in_layout(layout, entry.character, entry.fallbackKeyCode);
        if (key_code == entry.keyCode) {
            return;
        }

        // Register the new key first, so a failure keeps the old one working.
        EventHotKeyRef hotkey_ref = NULL;
        const OSStatus status =
                kg_register_event_hotkey(key.intValue, key_code, entry.modifiers, &hotkey_ref);
        if (status != noErr || hotkey_ref == NULL) {
            NSLog(@"HotKey Layout: Failed to move hotkey: %d", (int) status);
            return;
        }
        UnregisterEventHotKey(entry.ref);
        entry.ref = hotkey_ref;
        entry.keyCode = key_code;
        entry.pressed = NO;
    }];
    if (source != NULL) {
        CFRelease(source);
    }
}

static void kg_on_input_source_changed(
        CFNotificationCenterRef _center,
        void *_observer,
        CFNotificationName _name,
        const void *_object,
        CFDictionaryRef _user_info
) {
    (void) _center;
    (void) _observer;
    (void) _name;
    (void) _object;
    (void) _user_info;
    kg_enqueue_on_main(^{
        kg_hotkey_refresh_layout();
    });
}

static void kg_observe_input_source_changes(void) {
    static bool observing = false;
    if (observing) {
        return;
    }

    observing = true;
    CFNotificationCenterAddObserver(
            CFNotificationCenterGetDistributedCenter(),
            NULL,
            &kg_on_input_source_changed,
            kTISNotifySelectedKeyboardInputSourceChanged,
            NULL,
            CFNotificationSuspensionBehaviorDeliverImmediately
    );
}

static int32_t kg_register_global_hotkey_inner(
        uint32_t native_key_code,
        uint32_t modifiers,
        kg_hotkey_callback_t callback
) {
    if (callback == NULL) {
        return KGNativeInternalError;
    }

    if (!kg_install_hotkey_handler()) {
        return KGNativeInternalError;
    }

    const unichar character = (unichar) (native_key_code >> 16);
    const uint32_t fallback_key_code = native_key_code & 0xFFFF;
    const uint32_t key_code = character == 0
            ? fallback_key_code
            : kg_hotkey_key_code_for_character(character, fallback_key_code);

    const int32_t hotkey_id = kg_hotkey_next_id++;
    EventHotKeyRef hotkey_ref = NULL;
    const OSStatus status = kg_register_event_hotkey(hotkey_id, key_code, modifiers, &hotkey_ref);
    if (status != noErr || hotkey_ref == NULL) {
        NSLog(@"HotKey Register: Failed to register hotkey: %d", (int) status);
        return KGHotKeyUnavailable;
    }

    KGHotKeyEntry *entry = [[KGHotKeyEntry alloc] init];
    entry.ref = hotkey_ref;
    entry.callback = callback;
    entry.pressed = NO;
    entry.keyCode = key_code;
    entry.modifiers = modifiers;
    entry.character = character;
    entry.fallbackKeyCode = fallback_key_code;
    kg_hotkey_registry()[@(hotkey_id)] = entry;
    if (character != 0) {
        kg_observe_input_source_changes();
    }
    return hotkey_id;
}

static bool kg_unregister_global_hotkey_inner(int32_t hotkey_id) {
    NSNumber *key = @(hotkey_id);
    KGHotKeyEntry *entry = kg_hotkey_registry()[key];
    if (entry == nil) {
        return false;
    }

    const OSStatus status = UnregisterEventHotKey(entry.ref);
    if (status != noErr) {
        NSLog(@"HotKey Unregister: Failed to unregister hotkey: %d", (int) status);
        return false;
    }

    [kg_hotkey_registry() removeObjectForKey:key];
    return true;
}

int32_t kg_register_native_global_hotkey(
        uint32_t native_key_code,
        uint32_t native_modifiers,
        kg_hotkey_callback_t callback
) {
    __block int32_t result = KGNativeInternalError;
    void (^block)(void) = ^{
        result = kg_register_global_hotkey_inner(
                native_key_code,
                native_modifiers,
                callback
        );
    };

    kg_run_on_main(block);

    return result;
}

bool kg_unregister_native_global_hotkey(int32_t hotkey_id) {
    __block bool result = false;
    void (^block)(void) = ^{
        result = kg_unregister_global_hotkey_inner(hotkey_id);
    };

    kg_run_on_main(block);

    return result;
}

bool kg_biometrics_is_supported(void) {
    LAContext *context = [[LAContext alloc] init];
    NSError *error = nil;
    return [context canEvaluatePolicy:LAPolicyDeviceOwnerAuthenticationWithBiometrics error:&error];
}

static int32_t kg_biometrics_status_from_error(NSError *error) {
    if (error == nil || ![error.domain isEqualToString:LAErrorDomain]) {
        return KGBiometricsStatusUnknown;
    }

    switch (error.code) {
        case LAErrorUserCancel:
        case LAErrorSystemCancel:
        case LAErrorAppCancel:
            return KGBiometricsStatusUserCanceled;
        case LAErrorUserFallback:
            return KGBiometricsStatusUserPrefersPassword;
        case LAErrorBiometryLockout:
            return KGBiometricsStatusSecurityDeviceLocked;
        case LAErrorBiometryNotAvailable:
        case LAErrorBiometryNotEnrolled:
        case LAErrorPasscodeNotSet:
            return KGBiometricsStatusUnavailable;
        default:
            return KGBiometricsStatusUnknown;
    }
}

void kg_biometrics_verify(const char *title, kg_biometrics_callback_t callback) {
    if (callback == NULL) {
        return;
    }

    LAContext *context = [[LAContext alloc] init];
    NSError *error = nil;
    BOOL is_supported = [context canEvaluatePolicy:LAPolicyDeviceOwnerAuthenticationWithBiometrics error:&error];
    if (!is_supported) {
        NSString *message = error.localizedDescription ?: @"Biometrics are unavailable";
        callback(kg_biometrics_status_from_error(error), message.UTF8String);
        return;
    }

    NSString *localized_reason = kg_string_from_utf8(title);
    if (localized_reason == nil) {
        callback(KGBiometricsStatusUnknown, "Authentication failed");
        return;
    }

    [context evaluatePolicy:LAPolicyDeviceOwnerAuthenticationWithBiometrics
            localizedReason:localized_reason
                      reply:^(BOOL success, NSError *reply_error) {
        if (success) {
            callback(KGBiometricsStatusSuccess, NULL);
            return;
        }

        NSString *message = reply_error.localizedDescription ?: @"Authentication failed";
        callback(kg_biometrics_status_from_error(reply_error), message.UTF8String);
    }];
}

bool kg_keychain_add_password(const char *id, const char *password) {
    NSString *service = kg_string_from_utf8(id);
    NSString *password_value = kg_string_from_utf8(password);
    if (service == nil || password_value == nil) {
        return false;
    }

    NSData *data = [password_value dataUsingEncoding:NSUTF8StringEncoding];
    NSDictionary *query = @{
        (__bridge NSString *)kSecClass: (__bridge NSString *)kSecClassGenericPassword,
        (__bridge NSString *)kSecAttrAccount: KGAccountName,
        (__bridge NSString *)kSecAttrService: service,
    };
    NSDictionary *update = @{
        (__bridge NSString *)kSecValueData: data,
    };

    OSStatus status = SecItemUpdate((__bridge CFDictionaryRef)query, (__bridge CFDictionaryRef)update);
    if (status == errSecItemNotFound) {
        NSDictionary *add_query = @{
            (__bridge NSString *)kSecClass: (__bridge NSString *)kSecClassGenericPassword,
            (__bridge NSString *)kSecAttrAccount: KGAccountName,
            (__bridge NSString *)kSecAttrService: service,
            (__bridge NSString *)kSecAttrIsPermanent: @YES,
            (__bridge NSString *)kSecValueData: data,
        };
        status = SecItemAdd((__bridge CFDictionaryRef)add_query, NULL);
    }

    BOOL success = status == errSecSuccess;
    if (!success) {
        kg_log_keychain_error(@"Keychain Add: Failed to add an item to the keychain", status);
    }

    return success;
}

char *kg_keychain_get_password(const char *id) {
    NSString *service = kg_string_from_utf8(id);
    if (service == nil) {
        return NULL;
    }

    NSDictionary *query = @{
        (__bridge NSString *)kSecClass: (__bridge NSString *)kSecClassGenericPassword,
        (__bridge NSString *)kSecAttrAccount: KGAccountName,
        (__bridge NSString *)kSecAttrService: service,
        (__bridge NSString *)kSecReturnData: @YES,
    };

    CFTypeRef result = NULL;
    OSStatus status = SecItemCopyMatching((__bridge CFDictionaryRef)query, &result);
    if (status != errSecSuccess) {
        if (status == errSecItemNotFound) {
            return NULL;
        }

        kg_log_keychain_error(@"Keychain Get: Failed to get an item from the keychain", status);
        return NULL;
    }

    NSData *data = CFBridgingRelease(result);
    NSString *value = [[NSString alloc] initWithData:data encoding:NSUTF8StringEncoding];
    if (value == nil) {
        return NULL;
    }

    return strdup(value.UTF8String);
}

bool kg_keychain_delete_password(const char *id) {
    NSString *service = kg_string_from_utf8(id);
    if (service == nil) {
        return false;
    }

    NSDictionary *query = @{
        (__bridge NSString *)kSecClass: (__bridge NSString *)kSecClassGenericPassword,
        (__bridge NSString *)kSecAttrAccount: KGAccountName,
        (__bridge NSString *)kSecAttrService: service,
    };

    OSStatus status = SecItemDelete((__bridge CFDictionaryRef)query);
    BOOL success = status == errSecSuccess;
    if (!success) {
        kg_log_keychain_error(@"Keychain Delete: Failed to delete an item from the keychain", status);
    }

    return success;
}

bool kg_keychain_contains_password(const char *id) {
    NSString *service = kg_string_from_utf8(id);
    if (service == nil) {
        return false;
    }

    NSDictionary *query = @{
        (__bridge NSString *)kSecClass: (__bridge NSString *)kSecClassGenericPassword,
        (__bridge NSString *)kSecAttrAccount: KGAccountName,
        (__bridge NSString *)kSecAttrService: service,
        (__bridge NSString *)kSecReturnData: @NO,
    };

    OSStatus status = SecItemCopyMatching((__bridge CFDictionaryRef)query, NULL);
    if (status == errSecSuccess) {
        return true;
    }

    if (status == errSecItemNotFound) {
        return false;
    }

    kg_log_keychain_error(@"Keychain Contains: Failed to check if item exists in the keychain", status);
    return false;
}

static NSString *kg_notification_skip_reason(void) {
    NSBundle *mainBundle = [NSBundle mainBundle];
    NSURL *bundleURL = mainBundle.bundleURL;
    NSString *bundlePath = bundleURL.path;
    if (bundlePath.length == 0) {
        return @"main bundle URL is missing";
    }

    NSString *bundleExtension = bundleURL.pathExtension.lowercaseString;
    if (![bundleExtension isEqualToString:@"app"]) {
        return [NSString stringWithFormat:@"main bundle is not an .app bundle (%@)", bundlePath];
    }

    NSString *bundleIdentifier = mainBundle.bundleIdentifier;
    if (bundleIdentifier.length == 0) {
        return [NSString stringWithFormat:@"bundle identifier is missing for %@", bundlePath];
    }

    return nil;
}

static void kg_log_notification_exception(NSString *stage, NSException *exception) {
    NSLog(@"Notification: Failed to post local notification during %@ due to %@: %@",
          stage,
          exception.name,
          exception.reason ?: @"Unknown reason");
}

int32_t kg_post_notification(int32_t identifier, const char *title, const char *text) {
    NSString *notification_title = kg_string_from_utf8(title);
    NSString *notification_text = kg_string_from_utf8(text);
    if (notification_title == nil || notification_text == nil) {
        return 0;
    }

    dispatch_async(dispatch_get_main_queue(), ^{
        @try {
            // Local IDE/JBR runs are intentionally unsupported here because
            // UserNotifications expects the current process to resolve to a real app bundle.
            NSString *skipReason = kg_notification_skip_reason();
            if (skipReason != nil) {
                NSLog(@"Notification: Skipping local notification because %@.", skipReason);
                return;
            }

            UNUserNotificationCenter *center = [UNUserNotificationCenter currentNotificationCenter];
            [center requestAuthorizationWithOptions:(UNAuthorizationOptionAlert | UNAuthorizationOptionBadge | UNAuthorizationOptionSound)
                                  completionHandler:^(BOOL granted, NSError *error) {
                @try {
                    (void)error;
                    if (!granted) {
                        return;
                    }

                    UNMutableNotificationContent *content = [[UNMutableNotificationContent alloc] init];
                    content.title = notification_title;
                    content.body = notification_text;
                    content.sound = [UNNotificationSound defaultSound];
                    content.interruptionLevel = UNNotificationInterruptionLevelActive;

                    NSString *request_identifier = [[NSUUID UUID] UUIDString];
                    UNNotificationRequest *request = [UNNotificationRequest requestWithIdentifier:request_identifier
                                                                                          content:content
                                                                                          trigger:nil];
                    [center addNotificationRequest:request withCompletionHandler:nil];
                } @catch (NSException *exception) {
                    kg_log_notification_exception(@"authorization callback", exception);
                }
            }];
        } @catch (NSException *exception) {
            kg_log_notification_exception(@"initialization", exception);
        }
    });

    (void)identifier;
    return 0;
}
