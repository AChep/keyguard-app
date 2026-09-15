// Posts notifications only to this process's NSWorkspace notification center.
// This test does not put the computer or its displays to sleep.
#import <AppKit/AppKit.h>
#import <dispatch/dispatch.h>
#include <assert.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>

extern int32_t kg_register_native_power_events(void (*callback)(int32_t));
extern bool kg_unregister_native_power_events(int32_t registration_id);

static int counts[5];

static void on_event(int32_t event) {
    assert(NSThread.isMainThread);
    assert(event >= 1 && event <= 4);
    counts[event]++;
}

static void run_on_worker(void (^block)(void)) {
    dispatch_semaphore_t done = dispatch_semaphore_create(0);
    dispatch_async(dispatch_get_global_queue(QOS_CLASS_DEFAULT, 0), ^{
        @autoreleasepool {
            block();
        }
        dispatch_semaphore_signal(done);
    });
    NSDate *deadline = [NSDate dateWithTimeIntervalSinceNow:5];
    while (dispatch_semaphore_wait(done, DISPATCH_TIME_NOW) != 0) {
        assert(deadline.timeIntervalSinceNow > 0);
        [NSRunLoop.currentRunLoop runUntilDate:[NSDate dateWithTimeIntervalSinceNow:0.01]];
    }
}

int main(void) {
    @autoreleasepool {
        assert(kg_register_native_power_events(NULL) == -5);
        assert(!kg_unregister_native_power_events(-1));
        int32_t first = kg_register_native_power_events(on_event);
        assert(first > 0);
        __block int32_t second = 0;
        run_on_worker(^{ second = kg_register_native_power_events(on_event); });
        assert(second > first);

        NSArray<NSNotificationName> *names = @[
            NSWorkspaceScreensDidSleepNotification,
            NSWorkspaceScreensDidWakeNotification,
            NSWorkspaceWillSleepNotification,
            NSWorkspaceDidWakeNotification,
        ];
        NSNotificationCenter *center = NSWorkspace.sharedWorkspace.notificationCenter;
        for (NSUInteger index = 0; index < names.count; index++) {
            [center postNotificationName:names[index] object:NSWorkspace.sharedWorkspace];
            assert(counts[index + 1] == 2);
        }
        assert(kg_unregister_native_power_events(first));
        assert(!kg_unregister_native_power_events(first));
        run_on_worker(^{
            [center postNotificationName:NSWorkspaceWillSleepNotification object:nil];
            // Delivery must have finished when posting returns, even off-main.
            assert(counts[3] == 3);
            assert(kg_unregister_native_power_events(second));
        });
        for (NSUInteger index = 0; index < names.count; index++) {
            [center postNotificationName:names[index] object:nil];
            assert(counts[index + 1] == (index == 2 ? 3 : 2));
        }
        puts("macOS power observer tests passed");
    }
    return 0;
}
