#import <AppKit/AppKit.h>
#import <ApplicationServices/ApplicationServices.h>
#include <assert.h>
#include <stdio.h>
#include <unistd.h>

static bool trusted = true;
static AXError focusedWindowError = kAXErrorSuccess;
static AXUIElementRef focusedWindow;

static bool testIsProcessTrusted(void) { return trusted; }

static AXError testCopyAttributeValue(AXUIElementRef element, CFStringRef attribute, CFTypeRef *value) {
    (void)element;
    *value = NULL;
    if (CFEqual(attribute, kAXFocusedWindowAttribute)) {
        if (focusedWindowError == kAXErrorSuccess && focusedWindow) {
            *value = CFRetain(focusedWindow);
        }
        return focusedWindowError;
    }
    return kAXErrorAttributeUnsupported;
}

static AXError testPerformAction(AXUIElementRef element, CFStringRef action) {
    (void)element;
    (void)action;
    return kAXErrorActionUnsupported;
}

// Exercise the production focus guard without Accessibility permission or input events.
#define AXIsProcessTrusted testIsProcessTrusted
#define AXUIElementCopyAttributeValue testCopyAttributeValue
#define AXUIElementPerformAction testPerformAction
#include "../autotype.m"
#undef AXIsProcessTrusted
#undef AXUIElementCopyAttributeValue
#undef AXUIElementPerformAction

@interface TestRunningApplication : NSObject
@property(atomic, getter=isActive) BOOL active;
@property(atomic, getter=isTerminated) BOOL terminated;
@property(atomic) pid_t processIdentifier;
@property(nonatomic) NSUInteger activationRequests;
@end
@implementation TestRunningApplication
- (BOOL)activateFromApplication:(NSRunningApplication *)application
                        options:(NSApplicationActivationOptions)options {
    (void)application;
    (void)options;
    self.activationRequests++;
    return NO;
}
- (BOOL)activateWithOptions:(NSApplicationActivationOptions)options {
    (void)options;
    self.activationRequests++;
    return NO;
}
@end

int main(void) {
    @autoreleasepool {
        TestRunningApplication *app = [TestRunningApplication new];
        app.active = YES;
        app.processIdentifier = getpid();
        KGAutotypeTarget *target = [KGAutotypeTarget new];
        target.app = (NSRunningApplication *)app;
        target.element = AXUIElementCreateApplication(getpid());
        target.window = AXUIElementCreateApplication(getpid());
        focusedWindow = target.window;
        void *handle = (__bridge void *)target;

        assert(kg_autotype_focused(handle));

        // Refused raise and activation requests are hints, not failures:
        // the target may already be active. Focus polling confirms the outcome.
        assert(kg_autotype_activate(handle));
        assert(app.activationRequests == 1);

        // A different window must fail even when the same app owns it.
        AXUIElementRef otherWindow = AXUIElementCreateApplication(getpid() + 1);
        focusedWindow = otherWindow;
        assert(!kg_autotype_focused(handle));
        CFRelease(otherWindow);
        focusedWindow = target.window;

        app.active = NO;
        assert(!kg_autotype_focused(handle));
        app.active = YES;
        app.terminated = YES;
        assert(!kg_autotype_focused(handle));
        assert(!kg_autotype_activate(handle));
        assert(app.activationRequests == 1);
        app.terminated = NO;
        trusted = false;
        assert(!kg_autotype_focused(handle));
        assert(!kg_autotype_activate(handle));
        assert(app.activationRequests == 1);
        trusted = true;
        focusedWindowError = kAXErrorCannotComplete;
        assert(!kg_autotype_focused(handle));
        focusedWindowError = kAXErrorSuccess;
        focusedWindow = NULL;
        assert(!kg_autotype_focused(handle));
        focusedWindow = target.window;

        // Rust polls the focus probe while typing, so it must not need the AppKit
        // thread. This thread blocks without running its loop until the probe returns.
        __block bool probed = false;
        dispatch_semaphore_t probeDone = dispatch_semaphore_create(0);
        dispatch_async(dispatch_get_global_queue(QOS_CLASS_DEFAULT, 0), ^{
            probed = kg_autotype_focused(handle);
            dispatch_semaphore_signal(probeDone);
        });
        assert(dispatch_semaphore_wait(probeDone, dispatch_time(DISPATCH_TIME_NOW, NSEC_PER_SEC)) == 0);
        assert(probed);

        // AWT spins only its private mode while the AppKit thread waits on the
        // event thread. A shim call from the event thread must still complete.
        __block bool ranOnMain = false;
        dispatch_semaphore_t done = dispatch_semaphore_create(0);
        dispatch_async(dispatch_get_global_queue(QOS_CLASS_DEFAULT, 0), ^{
            kg_run_on_main(^{ ranOnMain = NSThread.isMainThread; });
            dispatch_semaphore_signal(done);
        });
        NSDate *deadline = [NSDate dateWithTimeIntervalSinceNow:5];
        while (dispatch_semaphore_wait(done, DISPATCH_TIME_NOW) != 0) {
            assert(deadline.timeIntervalSinceNow > 0);
            [NSRunLoop.currentRunLoop runMode:@"AWTRunLoopMode"
                                   beforeDate:[NSDate dateWithTimeIntervalSinceNow:0.01]];
        }
        assert(ranOnMain);

        // A stalled AppKit thread bounds the wait, and the abandoned block never runs.
        __block bool abandonedRan = false;
        __block bool abandonedReturned = true;
        dispatch_semaphore_t returned = dispatch_semaphore_create(0);
        dispatch_async(dispatch_get_global_queue(QOS_CLASS_DEFAULT, 0), ^{
            abandonedReturned = kg_run_on_main_within(50 * NSEC_PER_MSEC, ^{ abandonedRan = true; });
            dispatch_semaphore_signal(returned);
        });
        dispatch_semaphore_wait(returned, DISPATCH_TIME_FOREVER);
        assert(!abandonedReturned);
        // Blocks run in order, so the abandoned one has been dequeued once this one runs.
        __block bool boundedRan = false;
        __block bool boundedReturned = false;
        dispatch_async(dispatch_get_global_queue(QOS_CLASS_DEFAULT, 0), ^{
            boundedReturned = kg_run_on_main_within(5 * NSEC_PER_SEC, ^{ boundedRan = true; });
            dispatch_semaphore_signal(returned);
        });
        deadline = [NSDate dateWithTimeIntervalSinceNow:5];
        while (dispatch_semaphore_wait(returned, DISPATCH_TIME_NOW) != 0) {
            assert(deadline.timeIntervalSinceNow > 0);
            [NSRunLoop.currentRunLoop runMode:@"AWTRunLoopMode"
                                   beforeDate:[NSDate dateWithTimeIntervalSinceNow:0.01]];
        }
        assert(boundedRan && boundedReturned);
        assert(!abandonedRan);

        puts("Auto-type destination checks passed");
    }
    return 0;
}
