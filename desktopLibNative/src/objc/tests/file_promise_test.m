// Exercises production offer ownership without creating an OS drag session.
#import <AppKit/AppKit.h>
#include <assert.h>
#include <stdatomic.h>
#include <stdio.h>

#include "../file_promise.m"

typedef struct {
    atomic_uint writes;
    atomic_uint written;
    atomic_uint ends;
    atomic_uint retirements;
    atomic_bool accepted;
} TestContext;

static void wait_for(dispatch_semaphore_t signal) {
    assert(dispatch_semaphore_wait(signal, dispatch_time(DISPATCH_TIME_NOW, 5 * NSEC_PER_SEC)) == 0);
}

static bool test_write(void *raw, size_t index, const char *path) {
    assert(!NSThread.isMainThread);
    assert(path);
    TestContext *context = raw;
    atomic_fetch_add(&context->writes, 1);
    atomic_fetch_or(&context->written, 1u << index);
    return true;
}

static dispatch_semaphore_t write_entered;
static dispatch_semaphore_t write_gate;

static bool blocking_write(void *raw, size_t index, const char *path) {
    bool written = test_write(raw, index, path);
    dispatch_semaphore_signal(write_entered);
    wait_for(write_gate);
    return written;
}

static void test_ended(void *raw, bool accepted) {
    assert(NSThread.isMainThread);
    TestContext *context = raw;
    atomic_fetch_add(&context->ends, 1);
    atomic_store(&context->accepted, accepted);
}

static void test_retired(void *raw) {
    TestContext *context = raw;
    atomic_fetch_add(&context->retirements, 1);
}

static KGFilePromiseOffer *test_offer(TestContext *context, NSArray<NSString *> *names) {
    KGFilePromiseOffer *offer = [KGFilePromiseOffer new];
    offer.names = names;
    offer.identifier = kg_promise_next_identifier++;
    offer.context = context;
    offer.write = test_write;
    offer.ended = test_ended;
    offer.retired = test_retired;
    NSMutableArray<NSFilePromiseProvider *> *providers = [NSMutableArray new];
    for (NSUInteger index = 0; index < names.count; index++) {
        NSFilePromiseProvider *provider = [[NSFilePromiseProvider alloc] initWithFileType:@"public.data" delegate:offer];
        provider.userInfo = @(index);
        [providers addObject:provider];
    }
    offer.providers = providers;
    kg_promise_register(offer);
    return offer;
}

// Calls the delegate on a worker and returns the completion signal. The
// optional `returned` signal fires once the delegate call itself returns.
static dispatch_semaphore_t write_on_worker(KGFilePromiseOffer *offer, NSFilePromiseProvider *provider, bool success,
                                           dispatch_semaphore_t _Nullable returned) {
    dispatch_semaphore_t completed = dispatch_semaphore_create(0);
    dispatch_async(dispatch_get_global_queue(QOS_CLASS_DEFAULT, 0), ^{
        @autoreleasepool {
            [offer filePromiseProvider:provider writePromiseToURL:[NSURL fileURLWithPath:@"/unused-test-destination"]
                completionHandler:^(NSError *error) {
                    assert((error == nil) == success);
                    dispatch_semaphore_signal(completed);
                }];
            if (returned) dispatch_semaphore_signal(returned);
        }
    });
    return completed;
}

static dispatch_semaphore_t defer_write(KGFilePromiseOffer *offer, NSUInteger index, bool success) {
    NSUInteger pending = offer.pendingWrites.count;
    dispatch_semaphore_t deferred = dispatch_semaphore_create(0);
    dispatch_semaphore_t completed = write_on_worker(offer, offer.providers[index], success, deferred);
    wait_for(deferred);
    assert(offer.pendingWrites.count == pending + 1);
    return completed;
}

// Drains blocks that revoke enqueued on this, the AppKit, thread.
static void spin_until(bool (^done)(void)) {
    NSDate *deadline = [NSDate dateWithTimeIntervalSinceNow:5];
    while (!done()) {
        assert(deadline.timeIntervalSinceNow > 0);
        [NSRunLoop.currentRunLoop runMode:NSDefaultRunLoopMode beforeDate:[NSDate dateWithTimeIntervalSinceNow:0.01]];
    }
}

static void spin_until_count(atomic_uint *counter, unsigned expected) {
    spin_until(^bool {
        return atomic_load(counter) == expected;
    });
}

static void spin_briefly(void) {
    NSDate *deadline = [NSDate dateWithTimeIntervalSinceNow:0.05];
    while (deadline.timeIntervalSinceNow > 0) {
        [NSRunLoop.currentRunLoop runMode:NSDefaultRunLoopMode beforeDate:deadline];
    }
}

static bool names_valid(NSArray<NSString *> *names) {
    const char *raw[names.count + 1];
    for (NSUInteger i = 0; i < names.count; i++) raw[i] = names[i].UTF8String;
    return kg_promise_names(raw, names.count) != nil;
}

static NSArray<NSString *> *numbered_names(NSUInteger count) {
    NSMutableArray<NSString *> *names = [NSMutableArray arrayWithCapacity:count];
    for (NSUInteger i = 0; i < count; i++) [names addObject:[NSString stringWithFormat:@"file-%lu.bin", i]];
    return names;
}

static void revoke_on_worker(int64_t handle) {
    dispatch_semaphore_t done = dispatch_semaphore_create(0);
    dispatch_async(dispatch_get_global_queue(QOS_CLASS_DEFAULT, 0), ^{
        kg_file_promise_revoke(handle);
        dispatch_semaphore_signal(done);
    });
    wait_for(done);
}

int main(void) {
    @autoreleasepool {
        // Names follow MacFilePromise.kt: 1...64, unique ignoring case.
        assert(names_valid(@[ @"attachment.bin" ]));
        assert(names_valid(@[ @"report.pdf", @"report.pdf.txt", @".hidden" ]));
        assert(names_valid(numbered_names(64)));
        assert(!names_valid(@[]));
        assert(!names_valid(numbered_names(65)));
        assert(!names_valid(@[ @"" ]));
        assert(!names_valid(@[ @" \t" ]));
        assert(!names_valid(@[ @"." ]));
        assert(!names_valid(@[ @".." ]));
        assert(!names_valid(@[ @"a/b" ]));
        assert(!names_valid(@[ @"report.pdf", @"REPORT.PDF" ]));
        assert(!names_valid(@[ @"Ä.txt", @"a.txt", @"ä.TXT" ]));
        const char *nullName[] = {"a.txt", NULL};
        assert(!kg_promise_names(nullName, 2));
        const char *invalidUtf8[] = {"\xff.bin"};
        assert(!kg_promise_names(invalidUtf8, 1));
        assert(!kg_promise_names(NULL, 1));

        // Rejected arguments never take ownership or call back.
        TestContext rejected = {0};
        const char *duplicates[] = {"a.txt", "A.txt"};
        assert(kg_file_promise_start(1, duplicates, 2, &rejected, test_write, test_ended, test_retired) == 0);
        const char *single[] = {"a.txt"};
        assert(kg_file_promise_start(0, single, 1, &rejected, test_write, test_ended, test_retired) == 0);
        assert(kg_file_promise_start(1, single, 1, NULL, test_write, test_ended, test_retired) == 0);
        // No such window: the AppKit-thread part declines without an offer.
        assert(kg_file_promise_start(1, single, 1, &rejected, test_write, test_ended, test_retired) == 0);
        assert(atomic_load(&rejected.ends) == 0);
        assert(atomic_load(&rejected.retirements) == 0);

        // Unknown handles are ignored.
        kg_file_promise_revoke(0);
        kg_file_promise_revoke(-1);
        kg_file_promise_revoke(INT64_MAX);

        TestContext failed = {0};
        KGFilePromiseOffer *failure = test_offer(&failed, @[ @"first.bin", @"second.bin" ]);
        dispatch_semaphore_t failedFirst = defer_write(failure, 0, false);
        dispatch_semaphore_t failedSecond = defer_write(failure, 1, false);
        // A synchronous end followed by nil/throw must leave Rust as sole owner.
        NSDraggingSession *unusedSession = (NSDraggingSession *)[NSObject new];
        [failure draggingSession:unusedSession endedAtPoint:NSZeroPoint operation:NSDragOperationNone];
        [failure retire];
        assert(atomic_load(&failed.ends) == 0);
        assert(atomic_load(&failed.retirements) == 0);
        assert(failure.context == &failed);
        [failure settleOwnership:false];
        wait_for(failedFirst);
        wait_for(failedSecond);
        assert(failure.context == NULL);
        assert(!kg_promise_lookup(failure.identifier));
        assert(atomic_load(&failed.writes) == 0);
        assert(atomic_load(&failed.ends) == 0);
        assert(atomic_load(&failed.retirements) == 0);

        TestContext canceled = {0};
        KGFilePromiseOffer *cancel = test_offer(&canceled, @[ @"attachment.bin" ]);
        [cancel draggingSession:unusedSession endedAtPoint:NSZeroPoint operation:NSDragOperationNone];
        assert(atomic_load(&canceled.ends) == 0);
        [cancel settleOwnership:true];
        [cancel settleOwnership:true];
        assert(atomic_load(&canceled.ends) == 1);
        assert(!atomic_load(&canceled.accepted));
        assert(atomic_load(&canceled.retirements) == 1);
        assert(cancel.context == NULL);
        assert(!kg_promise_lookup(cancel.identifier));
        // A rejected drag already retired the offer.
        kg_file_promise_revoke((int64_t)cancel.identifier);
        spin_briefly();
        assert(atomic_load(&canceled.ends) == 1);
        assert(atomic_load(&canceled.retirements) == 1);

        // An accepted drop keeps every file writable until it is revoked.
        TestContext accepted = {0};
        KGFilePromiseOffer *success = test_offer(&accepted, @[ @"first.bin", @"second.bin" ]);
        assert([[success filePromiseProvider:success.providers[0] fileNameForType:@"public.data"]
            isEqualToString:@"first.bin"]);
        assert([[success filePromiseProvider:success.providers[1] fileNameForType:@"public.data"]
            isEqualToString:@"second.bin"]);
        dispatch_semaphore_t firstWrite = defer_write(success, 0, true);
        dispatch_semaphore_t secondWrite = defer_write(success, 1, true);
        [success draggingSession:unusedSession endedAtPoint:NSZeroPoint operation:NSDragOperationCopy];
        assert(atomic_load(&accepted.ends) == 0);
        assert(atomic_load(&accepted.writes) == 0);
        [success settleOwnership:true];
        wait_for(firstWrite);
        wait_for(secondWrite);
        assert(atomic_load(&accepted.ends) == 1);
        assert(atomic_load(&accepted.accepted));
        assert(atomic_load(&accepted.writes) == 2);
        assert(atomic_load(&accepted.written) == 0x3);
        assert(atomic_load(&accepted.retirements) == 0);
        // Nothing expires it: a later write still succeeds.
        NSArray<NSFilePromiseProvider *> *successProviders = success.providers;
        spin_briefly();
        wait_for(write_on_worker(success, successProviders[1], true, nil));
        assert(atomic_load(&accepted.writes) == 3);
        assert(kg_promise_lookup(success.identifier) == success);
        revoke_on_worker((int64_t)success.identifier);
        spin_until_count(&accepted.retirements, 1);
        assert(atomic_load(&accepted.ends) == 1);
        assert(success.context == NULL);
        assert(!kg_promise_lookup(success.identifier));
        wait_for(write_on_worker(success, successProviders[0], false, nil));
        revoke_on_worker((int64_t)success.identifier);
        [success revoke];
        spin_briefly();
        assert(atomic_load(&accepted.retirements) == 1);
        assert(atomic_load(&accepted.writes) == 3);

        // Revoking during the drag rejects new writes at once, reports the end,
        // and retires only after the in-flight writer returns.
        TestContext revoked = {0};
        write_entered = dispatch_semaphore_create(0);
        write_gate = dispatch_semaphore_create(0);
        KGFilePromiseOffer *revocation = test_offer(&revoked, @[ @"first.bin", @"second.bin" ]);
        revocation.write = blocking_write;
        [revocation settleOwnership:true];
        dispatch_semaphore_t inFlight = write_on_worker(revocation, revocation.providers[0], true, nil);
        wait_for(write_entered);
        kg_file_promise_revoke((int64_t)revocation.identifier);
        wait_for(write_on_worker(revocation, revocation.providers[1], false, nil));
        spin_until_count(&revoked.ends, 1);
        assert(!atomic_load(&revoked.accepted));
        assert(revocation.closed);
        assert(atomic_load(&revoked.retirements) == 0);
        assert(revocation.context == &revoked);
        // The session has not ended, so AppKit may still reach its source.
        assert(kg_promise_lookup(revocation.identifier) == revocation);
        dispatch_semaphore_signal(write_gate);
        wait_for(inFlight);
        assert(atomic_load(&revoked.retirements) == 1);
        assert(atomic_load(&revoked.written) == 0x1);
        assert(revocation.context == NULL);
        [revocation draggingSession:unusedSession endedAtPoint:NSZeroPoint operation:NSDragOperationCopy];
        assert(atomic_load(&revoked.ends) == 1);
        assert(!kg_promise_lookup(revocation.identifier));

        // A revocation that AppKit drains before ownership settles is deferred.
        TestContext early = {0};
        KGFilePromiseOffer *earlyRevocation = test_offer(&early, @[ @"attachment.bin" ]);
        dispatch_semaphore_t earlyWrite = defer_write(earlyRevocation, 0, false);
        [earlyRevocation revoke];
        spin_until(^bool {
            return earlyRevocation.pendingRetirement;
        });
        assert(atomic_load(&early.ends) == 0);
        assert(atomic_load(&early.retirements) == 0);
        [earlyRevocation settleOwnership:true];
        wait_for(earlyWrite);
        assert(atomic_load(&early.writes) == 0);
        assert(atomic_load(&early.ends) == 1);
        assert(atomic_load(&early.retirements) == 1);

        // Without a retention cap, several accepted offers stay live at once.
        TestContext manyContexts[6] = {0};
        TestContext *many = manyContexts;
        NSMutableArray<KGFilePromiseOffer *> *offers = [NSMutableArray new];
        for (size_t i = 0; i < 6; i++) {
            KGFilePromiseOffer *offer = test_offer(&many[i], @[ @"attachment.bin" ]);
            [offer settleOwnership:true];
            [offer draggingSession:unusedSession endedAtPoint:NSZeroPoint operation:NSDragOperationCopy];
            [offers addObject:offer];
        }
        for (size_t i = 0; i < 6; i++) {
            assert(kg_promise_lookup(offers[i].identifier) == offers[i]);
            assert(atomic_load(&many[i].retirements) == 0);
            kg_file_promise_revoke((int64_t)offers[i].identifier);
        }
        spin_until(^bool {
            for (size_t i = 0; i < 6; i++) {
                if (atomic_load(&many[i].retirements) != 1) return false;
            }
            return true;
        });
        puts("macOS file-promise ownership tests passed");
    }
    return 0;
}
