#import <Foundation/Foundation.h>
#import <ServiceManagement/ServiceManagement.h>
#include <stdint.h>
#include <stdio.h>
#include <stdatomic.h>
#include <dispatch/dispatch.h>

static _Atomic int loginObserved = 0;

@protocol KGLoginService <NSObject>
@property(nonatomic, readonly) SMAppServiceStatus status;
- (BOOL)registerAndReturnError:(NSError **)error;
- (BOOL)unregisterAndReturnError:(NSError **)error;
@end

static void kg_lal_open_settings(void) {
    if (@available(macOS 13.0, *)) [SMAppService openSystemSettingsLoginItems];
}

// The system has no record of a main app that was never registered: it reports NotFound,
// not NotRegistered. NotRegistered only follows an unregistration. Both read as disabled.
// A failed status lookup also reports NotFound, so it is not proof that nothing is registered.
static BOOL kg_lal_is_unregistered(SMAppServiceStatus status) {
    return status == SMAppServiceStatusNotRegistered || status == SMAppServiceStatusNotFound;
}

// `KGLoginService` lets the test fixture run this logic without ServiceManagement.
static int32_t kg_lal_perform(id<KGLoginService> service, int32_t operation,
    char *diagnostic, size_t capacity, void (*openSettings)(void)) {
    NSError *error = nil;
    BOOL success = YES;
    if (operation == 1 && service.status != SMAppServiceStatusEnabled &&
        service.status != SMAppServiceStatusRequiresApproval) {
        success = [service registerAndReturnError:&error];
    } else if (operation == 2 && service.status != SMAppServiceStatusNotRegistered) {
        success = [service unregisterAndReturnError:&error];
    } else if (operation == 3) {
        openSettings();
    }
    if (!success) {
        snprintf(diagnostic, capacity, "%s", error.localizedDescription.UTF8String ?: "ServiceManagement failed");
        return -3;
    }
    const SMAppServiceStatus status = service.status;
    // Report a registration the system did not record, so the switch does not silently turn off.
    if (operation == 1 && kg_lal_is_unregistered(status)) {
        snprintf(diagnostic, capacity, "ServiceManagement did not record the login item");
        return -3;
    }
    switch (status) {
        case SMAppServiceStatusEnabled: return 0;
        case SMAppServiceStatusNotRegistered:
        case SMAppServiceStatusNotFound: return 1;
        case SMAppServiceStatusRequiresApproval: return 2;
        default: return 6;
    }
}

// Keep Objective-C object lifetime and NSError handling inside an autorelease pool.
__attribute__((visibility("hidden"))) int32_t kg_lal_macos(int32_t operation, char *diagnostic, size_t capacity) {
    @autoreleasepool {
        if (@available(macOS 13.0, *)) {
            return kg_lal_perform((id<KGLoginService>)SMAppService.mainAppService, operation, diagnostic, capacity, kg_lal_open_settings);
        }
        if (operation == 0) return 4;
        snprintf(diagnostic, capacity, "Login registration requires macOS 13 or later");
        return -2;
    }
}

static BOOL kg_lal_is_login_event(NSAppleEventDescriptor *event) {
    return event.eventID == 'oapp' && [event paramDescriptorForKeyword:'prdt'].enumCodeValue == 'lgit';
}

// The caller must sample this while the launch AppleEvent is being dispatched.
// Do not dispatch_sync to the main queue: a UI thread may be awaiting this bridge.
__attribute__((visibility("hidden"))) int32_t kg_lal_macos_launch_event(void) {
    if (atomic_load(&loginObserved)) return 1;
    if (![NSThread isMainThread]) return 0;
    @autoreleasepool {
        NSAppleEventDescriptor *event = NSAppleEventManager.sharedAppleEventManager.currentAppleEvent;
        if (kg_lal_is_login_event(event)) {
            atomic_store(&loginObserved, 1);
        }
        return atomic_load(&loginObserved);
    }
}

__attribute__((visibility("hidden"))) void kg_lal_macos_prepare_launch_detection(void) {
    static dispatch_once_t once;
    static id observer;
    dispatch_once(&once, ^{
        @autoreleasepool {
            observer = [NSNotificationCenter.defaultCenter
                addObserverForName:@"NSApplicationDidFinishLaunchingNotification"
                object:nil queue:nil usingBlock:^(NSNotification *notification) {
                    (void)notification;
                    kg_lal_macos_launch_event();
                }];
        }
    });
    (void)observer;
}
