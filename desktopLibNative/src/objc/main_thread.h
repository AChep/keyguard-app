#pragma once

#import <Foundation/Foundation.h>

// Enqueues the block on the AppKit thread. While that thread waits in
// LWCToolkit.invokeAndWait, AWT spins only its private run loop mode, which
// never drains the main dispatch queue. Matching the JDK's own main-thread calls,
// also run in that mode, so a call from the AWT event thread cannot deadlock.
// Callers may have no autorelease pool of their own (JNA threads), and blocks
// run from the private mode get none from AppKit either.
static inline void kg_enqueue_on_main(void (^block)(void)) {
    @autoreleasepool {
        CFRunLoopRef loop = CFRunLoopGetMain();
        NSArray<NSString *> *modes = @[NSRunLoopCommonModes, @"AWTRunLoopMode"];
        CFRunLoopPerformBlock(loop, (__bridge CFArrayRef)modes, ^{
            @autoreleasepool {
                block();
            }
        });
        CFRunLoopWakeUp(loop);
    }
}

// Runs the block synchronously on the AppKit thread, but returns false without
// running it when that thread does not start it within the timeout. A started
// block always completes before this returns, so it may write to the caller's state.
static inline bool kg_run_on_main_within(int64_t timeout, void (^block)(void)) {
    if (NSThread.isMainThread) {
        block();
        return true;
    }
    dispatch_semaphore_t done = dispatch_semaphore_create(0);
    NSObject *lock = [NSObject new];
    __block bool started = false;
    __block bool abandoned = false;
    kg_enqueue_on_main(^{
        @synchronized(lock) {
            if (abandoned) return;
            started = true;
        }
        block();
        dispatch_semaphore_signal(done);
    });
    if (dispatch_semaphore_wait(done, dispatch_time(DISPATCH_TIME_NOW, timeout)) == 0) return true;
    @synchronized(lock) {
        if (!started) {
            abandoned = true;
            return false;
        }
    }
    dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER);
    return true;
}

// Runs the block synchronously on the AppKit thread. Never call this from the
// AWT event thread or while holding a lock that the AppKit thread may need;
// prefer kg_run_on_main_within.
static inline void kg_run_on_main(void (^block)(void)) {
    // dispatch_time saturates to DISPATCH_TIME_FOREVER.
    (void)kg_run_on_main_within(INT64_MAX, block);
}
