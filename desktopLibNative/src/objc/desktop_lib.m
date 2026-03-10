#import <ApplicationServices/ApplicationServices.h>
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

typedef void (*kg_biometrics_callback_t)(bool success, const char *error);

static NSString *const KGAccountName = @"com.artemchep.keyguard";

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

bool kg_post_keyboard_event(uint16_t key_code, bool key_down, uint64_t flags) {
    CGEventRef event = CGEventCreateKeyboardEvent(NULL, (CGKeyCode)key_code, key_down);
    if (event == NULL) {
        return false;
    }

    CGEventSetFlags(event, (CGEventFlags)flags);
    CGEventPost(kCGHIDEventTap, event);
    CFRelease(event);
    return true;
}

bool kg_biometrics_is_supported(void) {
    LAContext *context = [[LAContext alloc] init];
    NSError *error = nil;
    return [context canEvaluatePolicy:LAPolicyDeviceOwnerAuthenticationWithBiometrics error:&error];
}

void kg_biometrics_verify(const char *title, kg_biometrics_callback_t callback) {
    if (callback == NULL) {
        return;
    }

    LAContext *context = [[LAContext alloc] init];
    NSError *error = nil;
    BOOL is_supported = [context canEvaluatePolicy:LAPolicyDeviceOwnerAuthenticationWithBiometrics error:&error];
    if (!is_supported) {
        callback(false, NULL);
        return;
    }

    NSString *localized_reason = kg_string_from_utf8(title);
    if (localized_reason == nil) {
        callback(false, "Authentication failed");
        return;
    }

    [context evaluatePolicy:LAPolicyDeviceOwnerAuthenticationWithBiometrics
            localizedReason:localized_reason
                      reply:^(BOOL success, NSError *reply_error) {
        if (success) {
            callback(true, NULL);
            return;
        }

        NSString *message = reply_error.localizedDescription ?: @"Authentication failed";
        callback(false, message.UTF8String);
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
